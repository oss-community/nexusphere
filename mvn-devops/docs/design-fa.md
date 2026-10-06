# طراحی mvn-devops

## ایده در یک جمله

به‌جای اینکه برای هر موتور (Maven، Jenkins، Concourse) یه دسته اسکریپت جدا داشته باشیم که همه‌ی ابزارها رو از قبل توش گذاشتیم، **هر ابزار یک ماژول مستقل با اسکریپت خودشه**. یک اسکریپت اصلی (`devops.sh`) می‌پرسه چه ابزاری از هر دسته می‌خوای، و بعد فقط اسکریپت‌های همون ابزارها رو به ترتیب اجرا می‌کنه تا همه‌چیز بالا بیاد، تنظیم بشه و pipeline آماده‌ی اجرا باشه.

## مقایسه با روش «یک اسکریپت برای هر موتور»

| | یک اسکریپت برای هر موتور | mvn-devops |
|---|---|---|
| واحد کد | یک اسکریپت برای هر **موتور** که همه‌ی ابزارها داخلشه | یک ماژول برای هر **ابزار** |
| انتخاب ابزار | ثابت (Sonar، JFrog و Nexus همیشه هستن) | منو؛ از هر دسته هرچی بخوای |
| تکرار | سه نسخه‌ی تقریباً یکسان از secrets، pipeline و tokens | هر ابزار فقط یک بار نوشته شده |
| docker-compose | سه فایل کامل | هر ماژول یه تکه compose داره و موقع اجرا روی هم ادغام می‌شن |
| Jenkinsfile و pipeline.yml | دستی نوشته شده | از روی stage های ماژول‌های انتخاب‌شده **تولید** می‌شه |
| توکن‌ها | بخش زیادیش دستی از UI | خودکار (عوض‌کردن رمز ادمین، ساخت توکن، ثبت deploy key) |
| رسوندن متغیرها | `~/.bashrc`، `setx /M`، و REST جنکینز | فایل‌های `.devops/env` که فقط به فرایند pipeline داده می‌شن؛ اسرار همه‌جا mask می‌شن |

## دسته‌ها و ماژول‌ها

| دسته | نوع انتخاب | ماژول‌ها |
|---|---|---|
| Source control | اجباری | github |
| Build | اجباری | maven (validate، package، test، checkstyle، install) |
| Pipeline orchestrator | یکی | maven (روی سیستم خودت)، jenkins، concourse |
| Code quality | چندتایی | sonarqube |
| Artifact repositories | چندتایی | jfrog، nexus، github-packages |
| Project site | چندتایی | github-pages |

برای اضافه‌کردن ابزار جدید فقط یه پوشه‌ی تازه زیر `modules/<دسته>/<ابزار>/` لازمه؛ منو خودش پیداش می‌کنه ([module-guide.md](module-guide.md)).

## ساختار یک ماژول

```
modules/artifact/nexus/
  module.conf    اسم، توضیح، و ماژول‌هایی که بهشون وابسته‌ست
  compose.yml    container های این ابزار
  module.sh      hook ها
```

هر hook یه مرحله از چرخه‌ی عمره و اختیاریه:

| hook | کار |
|---|---|
| `module_secrets` | پرسیدن مقادیر لازم (پورت، رمز و ...) |
| `module_prepare` | آماده‌سازی قبل از بالا اومدن container ها |
| `module_configure` | تنظیم بعد از بالا اومدن: عوض‌کردن رمز ادمین، ساخت توکن و repository |
| `module_env` | معرفی متغیرهایی که pipeline لازم داره |
| `module_stages` | اضافه‌کردن stage های Maven به pipeline |
| `module_render`، `module_publish`، `module_run` | فقط برای orchestrator ها: ساختن، نصب و اجرای pipeline |

## نکته‌ی کلیدی: stage ها از ماژول‌ها میان

هر ماژول stage های خودش رو با شماره‌ی ترتیب و فاز (`ci` یا `cd`) اعلام می‌کنه. مثلاً sonarqube میگه «stage 45 در فاز ci: `sonar:sonar -P sonar`». orchestrator لیست مرتب‌شده رو می‌گیره و به زبان خودش تبدیل می‌کنه:

- **maven**: هر stage رو با `mvn` روی سیستم خودت اجرا می‌کنه.
- **jenkins**: یه Jenkinsfile و فایل `casc.yaml` می‌سازه که کاربر ادمین، credential ها و خود job رو تعریف می‌کنه. نه wizard داره، نه کار دستی توی UI.
- **concourse**: یه `pipeline.yml` می‌سازه با job `ci` که با هر push اجرا می‌شه، و job `cd` که بعد از موفقیت ci دستی اجرا می‌شه.

پس اگه فردا ابزار جدیدی اضافه کنی، هر سه orchestrator خودبه‌خود stage اون رو دارن.

## پروژه‌ی Maven به پروفایل نیاز نداره

فایل `profiles.xml` فقط در Maven 2 وجود داشت و از Maven 3 حذف شد. پروفایل‌های داخل `settings.xml` هم فقط property، repository و شرط فعال‌شدن رو قبول می‌کنن، نه plugin یا `distributionManagement`.

برای همین stage ها هر plugin رو مستقیم با مختصات کاملش صدا می‌زنن و تنظیماتش رو با `-D` می‌دن. مثلاً `maven-deploy-plugin:3.1.3:deploy -DaltSnapshotDeploymentRepository=nexus-snapshots::<url>`. اطلاعات ورود هم از `templates/settings.xml` خود فریمورک میاد که با `-gs` (global settings) پاس داده می‌شه. نتیجه اینکه developer به pom پروژه‌اش هیچ پروفایل، `distributionManagement` یا settings اضافه نمی‌کنه. اگه پروژه پروفایل یا settings خودش رو داشته باشه، از طریق مقادیر `MAVEN_PROFILES` و `MAVEN_SETTINGS` هنوز می‌شه ازشون استفاده کرد.

## فایل docker-compose ابزارها

بعد از انتخاب ابزارها، `up` (یا `devops.sh export-compose [پوشه]`) یه `docker-compose.yml` کامل از ابزارهای انتخاب‌شده می‌سازه، به‌همراه یه `.env` کنارش. پیش‌فرض توی `.devops/compose` هست. با این فایل‌ها developer می‌تونه بدون devops.sh و فقط با `docker compose up -d` ابزارها رو بالا بیاره. رمزها داخل yml نیستن و از `.env` خونده می‌شن. `.env` دسترسی 600 داره و بیرون از `.devops` به `.gitignore` اضافه می‌شه.

## ایمیج‌های داکر

همه‌ی ابزارها (سونار، نکسوس، جی‌فراگ، کانکورس و پستگرس) ایمیج رسمی خودشون رو بدون تغییر استفاده می‌کنن. کارهای کانکورس هم داخل ایمیج رسمی میون اجرا می‌شن.

تنها استثنا جنکینزه. ایمیج رسمی جنکینز جاوا داره ولی میون نداره، و مراحل پایپ‌لاین دستورهای میون هستن که داخل خود جنکینز اجرا می‌شن. برای همین یه داکرفایل کوچیک داریم که فقط از ایمیج‌های رسمی ساخته می‌شه: از ایمیج رسمی جنکینز شروع می‌کنه، میون رو از ایمیج رسمی میون کپی می‌کنه، و گیت، اس‌اس‌اچ و پلاگین‌های لازم رو اضافه می‌کنه. این ایمیج موقع بالا اومدن روی همون سیستم ساخته می‌شه و جایی منتشر نمی‌شه. نسخه‌ی جاوا (پیش‌فرض ۲۱) و میون (پیش‌فرض ۳.۹) یک بار پرسیده می‌شه و همه‌جا یکسانه.

## ابزارها لزوماً روی localhost نیستن

هر ابزاری که سرور داره (SonarQube، Nexus، Artifactory، Jenkins، Concourse) دو حالت داره:

- **در Docker:** خود devops.sh بالاش میاره، یا روی همین سیستم یا روی ماشینی که `DOCKER_HOST` بهش اشاره می‌کنه. آدرسش از `DEVOPS_HOST` میاد.
- **سرور موجود:** هر جایی با URL خودش. در `secrets` مقدار `<TOOL>_SERVER_URL` و اطلاعات ورود پرسیده می‌شه، container ای براش ساخته نمی‌شه، و `configure` فقط اطلاعات ورود و repository ها رو چک می‌کنه.

هر ابزار جدا تصمیم گرفته می‌شه، پس هر ترکیبی ممکنه. GitHub Enterprise هم با `GITHUB_URL` پشتیبانی می‌شه. برای Jenkins موجود، `publish` job و credential ها رو با REST API می‌سازه یا به‌روز می‌کنه. برای Concourse موجود، pipeline در team خودت ست می‌شه.

## نصب و انتشار

هر بار که یه tag مثل `v0.1.0` push بشه، workflow ِ `release` چک‌ها رو اجرا می‌کنه، بسته‌ها رو با `packaging/build.sh` می‌سازه و در GitHub Releases منتشر می‌کنه. بسته‌ها:
- zip برای ویندوز (با `devops.bat`)
- tar.gz برای macOS و هر لینوکسی
- deb برای Debian و Ubuntu
- rpm برای Fedora و RHEL

بسته‌های لینوکسی فایل‌ها رو در `/usr/share/mvn-devops` نصب می‌کنن و دستور `mvn-devops` رو اضافه می‌کنن.

## چرخه‌ی کار

```
init  →  secrets  →  up  →  configure  →  publish  →  run
منو      پرسیدن     Docker   توکن‌ها      نصب pipeline   اجرا
```

`devops.sh setup` پنج قدم اول رو پشت‌سرهم اجرا می‌کنه.

## گام‌های پیاده‌سازی (همونی که انجام شد)

1. **هسته** (`lib/`): لاگ و منو، مخزن مقادیر (`.devops/values`)، تولید فایل‌های env، جمع‌کردن stage ها، wrapper ِ docker compose.
2. **قرارداد ماژول**: `module.conf`، `compose.yml`، و hook های `module.sh`؛ هر hook در یه subshell جدا اجرا می‌شه تا ماژول‌ها با هم تداخل نداشته باشن.
3. **ماژول‌های ابزار**: github، maven build، sonarqube، nexus، jfrog، github-packages، github-pages.
4. **orchestrator ها**: maven (محلی)، jenkins (Configuration as Code)، concourse (quickstart و fly).
5. **اسکریپت اصلی** `devops.sh` و لانچر ویندوز `devops.bat` که Git Bash رو پیدا می‌کنه.
6. **تست**: `tests/smoke.sh` برای هر سه orchestrator، shellcheck، و workflow ِ GitHub Actions.
7. **مستندات**: README، راهنمای نوشتن ماژول، و نیازمندی‌های پروژه‌ی Maven.

## راهنماهای جانبی

- [github-setup.md](github-setup.md): ساخت توکن‌های GitHub با scope های لازم، و کلید SSH
- [prerequisites.md](prerequisites.md): نصب پیش‌نیازها برای هر سیستم‌عامل
- [ngrok.md](ngrok.md): در دسترس گذاشتن Jenkins ِ محلی برای webhook ِ GitHub
- [ide.md](ide.md): تنظیمات IntelliJ (Checkstyle، coverage)

Jenkins به لاگین در UI یا ساختن دستی API token نیاز نداره. devops.sh با رمز admin و REST API باهاش کار می‌کنه. اجرای خودکار با هر push با `JENKINS_TRIGGER` تنظیم می‌شه (`poll`، `webhook` یا `none`).

دستور `release` نسخه‌ی release رو تنظیم می‌کنه، commit و tag می‌زنه، stage های deploy رو اجرا می‌کنه، نسخه‌ی SNAPSHOT ِ بعدی رو می‌ذاره و push می‌کنه. اگه وسط کار خطا بده، همه‌چیز برمی‌گرده.

## گام‌های بعدی پیشنهادی

- ماژول‌های بیشتر: GitLab در دسته‌ی scm، GitHub Actions به‌عنوان orchestrator، OWASP Dependency-Check در quality، Reposilite در artifact.
- پشتیبانی از Podman.
