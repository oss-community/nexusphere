# Container images and deployment

These modules turn the build into something that runs: an image in a
registry, and later a running application. They are capabilities for your
project; select the ones you want in `init`.

The [hello-api example](../examples/hello-api) is a small web application
with a health endpoint (`/actuator/health`) that the end-to-end tests deploy;
[hello-data](../examples/hello-data) adds a PostgreSQL database with Flyway
migrations.

## Environments

The image is built once and the same image goes through the deployment
environments one after the other. `ENVIRONMENTS` in `devops.conf` lists them
in that order; a project that does not set it has `staging production`. The
names commonly used for four environments are:

```properties
ENVIRONMENTS=dev test staging prod
```

An environment either follows the one before it on its own, or waits until
someone approves it (`ENV_<NAME>_APPROVAL=yes`; by default only the last one
waits). `secrets` asks for each. The environments after an approved one
follow it up to the next one that needs approval, so the pipeline falls into
parts:

| `ENVIRONMENTS` | Approval | What runs |
|---|---|---|
| `staging production` | production | `run`: ci, cd, staging. `run --phase production`: production |
| `dev test staging prod` | prod | `run`: ci, cd, dev, test, staging. `run --phase prod`: prod |
| `dev test staging prod` | staging, prod | `run`: ci, cd, dev, test. `run --phase staging`: staging. `run --phase prod`: prod |

`prod` also stands for the last environment, whatever it is called, in
`run --phase prod` and `rollback prod`. `devops.sh stages` shows each
environment's stages and where an approval comes. How each orchestrator asks
for approval is in [orchestrators.md](orchestrators.md#approvals).

Everything that belongs to an environment is per environment: its machine or
namespace, its port, its secrets in Vault, its database and password, its
metrics and logs. Values follow the pattern `<PREFIX>_<NAME>_<SETTING>`, for
example `DEPLOY_DEV_PORT` or `DATABASE_PROD_PASSWORD`. Default ports count up
from the last environment: with `dev test staging prod` the simulated machine
serves prod on 8180, staging on 8181, test on 8182 and dev on 8183 (k3s: 8280
and up). Names are lowercase letters and digits, starting with a letter.

## Container image

Choose one module of the *Container image* category:

| Module | Registry |
|---|---|
| `docker-registry` | The Distribution registry in Docker (port 5000), or an existing registry given as `REGISTRY_SERVER_URL`: `https://docker.io`, Harbor, a Docker repository of Nexus or Artifactory |
| `github-container` | The GitHub Container Registry (`ghcr.io/<owner>/<image>`), with the GitHub user and token. The token needs `write:packages` ([github-setup.md](github-setup.md)) |

The `image` stage (cd phase, order 75) builds the application and pushes it
with two tags:

- the commit, `git rev-parse --short=12 HEAD`. It never changes, and it is
  what deployments use, so a rollback is just the previous tag;
- `latest`, for people.

| Question | Default | Meaning |
|---|---|---|
| `IMAGE_NAME` | the project name | repository name in the registry; add the namespace where the registry needs one (`acme/app` on Docker Hub) |
| `IMAGE_MODULE` | empty | Maven module that is the application, in a multi-module project |
| `IMAGE_BUILDER` | `jib` | `jib`, or `dockerfile` to build the project's own `Dockerfile` |
| `IMAGE_BASE` | `eclipse-temurin:<java>-jre` | base image (Jib) |
| `IMAGE_PORT` | `8080` | port the application listens on (Jib) |

**Jib** (the default) builds the image from the Maven build without Docker and
without a Dockerfile, so it works in every orchestrator. **A Dockerfile** is
used by default when the project has one in its root and the pipeline runs on
this machine (the `maven` orchestrator); Jenkins and Concourse run without a
Docker daemon and need Jib.

The pipeline gets these variables:

| Variable | Meaning |
|---|---|
| `IMAGE_REPOSITORY` | where the pipeline pushes, as it reaches the registry |
| `IMAGE_DEPLOY_REPOSITORY` | the same repository as the machines that run the image pull it |
| `IMAGE_REGISTRY_USERNAME`, `IMAGE_REGISTRY_PASSWORD` | credentials, empty for the local registry |

The local registry has no TLS and no login. Docker accepts it without
configuration only as `localhost:<port>`, which is why the deploy repository
uses `localhost`. To pull from it on another machine, add it to
`insecure-registries` in that machine's `/etc/docker/daemon.json`, or use a
registry with TLS.

Check the result:

```bash
curl -s http://localhost:5000/v2/hello-api/tags/list
docker run --rm -p 8080:8080 localhost:5000/hello-api:latest
```

## Image security

The *Image security* modules work on the pushed image, between the image
stage and the deployment:

| Module | Stage | What it does |
|---|---|---|
| `syft` | `sbom` (76, cd) | writes the software bill of materials of the image to `target/sbom.spdx.json` (SPDX) and `target/sbom.cdx.json` (CycloneDX) |
| `trivy` | `scan-image` (77, cd) | scans the operating system packages and the jars in the image, writes `target/trivy-report.json` and prints the findings of `TRIVY_SEVERITY` (HIGH,CRITICAL). Fails when there are vulnerabilities of `TRIVY_FAIL_ON` (CRITICAL) that have a fix; empty means report only |
| `cosign` | `sign-image` (78, cd) | signs the image by digest with the project's key and, with `syft`, attaches the SBOM as a signed attestation |
| `cosign` | `verify-image-<environment>` (79) | with a deployment module, in each environment that needs approval: it only gets an image signed with that key |

The tools are not installed by hand: `templates/scripts/tool.sh` downloads the
pinned release where the pipeline runs (your machine, the Jenkins container or
the Concourse task), checks it against the release's checksum file and caches
it in `~/.cache/mvn-devops/tools` (`DEVOPS_TOOLS`). Trivy's vulnerability
database is cached there too. `TRIVY_VERSION`, `SYFT_VERSION` and
`COSIGN_VERSION` override a version.

**The Cosign key** is generated by `setup` (`.devops/keys/cosign.key` and
`cosign.pub`, protected by `COSIGN_PASSWORD`), or taken from
`COSIGN_KEY_FILE`. The pipeline gets it as a secret. Keep a copy of the key:
images signed with it can only be verified with its public key. Signatures go
to the image's registry (OCI referrers); nothing is sent to the public
Sigstore transparency log. Check a signature yourself with:

```bash
cosign verify --key .devops/keys/cosign.pub --insecure-ignore-tlog=true --allow-http-registry localhost:5000/hello-api:latest
```

## Deploying to a machine

The `docker-host` module (*Deployment* category) runs the image with Docker
Compose on a machine it reaches over SSH:

| Stage | Phase | What happens |
|---|---|---|
| `deploy-<environment>` (80) | the environment | deploys the image of the commit to the environment's machine, after its approval when it needs one |

Each deployment:

1. writes `compose.yml` and the tag to `~/mvn-devops/<image name>-<environment>/`
   on the machine and runs `docker compose up`;
2. calls the health endpoint (`DEPLOY_HEALTH_PATH`, default
   `/actuator/health`) for up to two minutes;
3. when it does not answer, puts back the image that ran before and fails the
   stage.

The container gets `DEPLOY_ENVIRONMENT` (the environment's name), the
environment's secrets from Vault (see [Secrets](#secrets)), written to
`secrets.yml` in that directory, and the settings in `app.env` there, which
you keep on the machine; it is never overwritten.

### The machines

| Question | Default | Meaning |
|---|---|---|
| `DEPLOY_SERVER_URL` | empty | machine of the first environment, e.g. `ssh://deploy@staging.example.com:22`; empty: a simulated machine in Docker for all of them |
| `DEPLOY_<NAME>_SERVER_URL` | the first machine | machine of each other environment, e.g. `DEPLOY_PRODUCTION_SERVER_URL` |
| `DEPLOY_SSH_KEY_FILE` | empty | private key that may log in; empty: `setup` generates `.devops/keys/deploy` |
| `DEPLOY_<NAME>_PORT` | 8180 for the last, counting up | port of the application on the machine, e.g. `DEPLOY_STAGING_PORT` 8181 |
| `DEPLOY_HEALTH_PATH` | `/actuator/health` | health check |

A real machine needs Docker with the compose plugin, curl or wget, and a user
in the `docker` group whose `~/.ssh/authorized_keys` holds the public key
(`devops.sh get DEPLOY_SSH_PUBLIC_KEY`). `configure` records the machines' SSH
host keys, which the pipeline then checks, and tells you when the key cannot
log in yet. The machine pulls `IMAGE_DEPLOY_REPOSITORY`, logging in with the
registry user when there is one.

**The simulated machine** is a container with an SSH server and the Docker
CLI, using the Docker daemon of your machine. The applications it starts are
ordinary containers on your machine:

```bash
curl http://localhost:8181/hello     # staging
curl http://localhost:8180/hello     # production
```

### Rollback

```bash
devops.sh rollback                     # the last environment: the image that ran before
devops.sh rollback staging
devops.sh rollback prod --to 3f2a9c1d4e5b
```

Rolling back twice returns to where you started. Every image stays in the
registry under its commit tag, so `--to` can go further back.

Each deployment replaces the container, so the application is down for the
few seconds it takes to start. Zero-downtime updates come with Kubernetes.

## Deploying to Kubernetes

The `kubernetes` module (*Deployment* category) installs the application with
Helm, one release per environment:

| Stage | Phase | What happens |
|---|---|---|
| `deploy-<environment>` (80) | the environment | `helm upgrade --install` in the namespace `<app>-<environment>`, after its approval when it needs one |

Helm waits until the new pods pass their readiness probe (the health path).
The update is rolling: a new pod must be ready before an old one stops, so the
application stays up. When the new version does not become ready within
`KUBERNETES_TIMEOUT` (5m), Helm rolls the release back and the stage fails.

| Question | Default | Meaning |
|---|---|---|
| `KUBERNETES_SERVER_URL` | empty | an existing cluster; empty: k3s in Docker |
| `KUBERNETES_KUBECONFIG` | `~/.kube/config` | kubeconfig of the existing cluster, passed to the pipeline as a secret |
| `KUBERNETES_<NAME>_PORT` | 8280 for the last, counting up | k3s: the application on this machine, e.g. `KUBERNETES_STAGING_PORT` 8281 |
| `KUBERNETES_<NAME>_NODE_PORT` | 30080 for the last, counting up, with k3s | node port of the service; empty: a ClusterIP service for your ingress |
| `KUBERNETES_CHART` | empty | the project's own chart, e.g. `deploy/chart`; empty: the generic chart |
| `KUBERNETES_REPLICAS` | 2 | pods per environment |
| `DEPLOY_HEALTH_PATH` | `/actuator/health` | readiness and liveness probe |

**The generic chart** ([templates/helm/app](../templates/helm/app)) has a
Deployment with readiness and liveness probes, resource requests, a rolling
update strategy and a Service. The container gets `DEPLOY_ENVIRONMENT`, and
every key of the Secret `<app>-env` in its namespace. With Vault (see
[Secrets](#secrets)) the release creates that Secret from the environment's
secrets (`secretEnv`) and the pods wait for it; without, it is used when it
exists:

```bash
kubectl --namespace hello-api-production create secret generic hello-api-env --from-literal=GREETING=hello
```

A private registry gets an image pull secret from the registry credentials.
**A chart of your own** receives the same values: `image.repository`,
`image.tag`, `registryAuth`, `environment`, `replicas`, `containerPort`,
`healthPath`, `service.nodePort`, and `secretEnv` or `sealedSecretEnv`.

**k3s in Docker** is a one-node cluster on your machine. It pulls from the
local registry through its service name, so the image reference stays
`localhost:5000/<image>`. Use it from your machine with:

```bash
export KUBECONFIG=.devops/k3s/kubeconfig-host.yaml
kubectl get pods --namespace hello-api-staging
curl http://localhost:8281/hello
```

`devops.sh rollback` goes back to the previous Helm revision (`helm rollback`);
`--to TAG` deploys an earlier image again.

## GitOps with Argo CD

With the `argocd` module (*GitOps* category, it adds `kubernetes`) the
pipeline does not deploy to the cluster itself. It writes the desired state
to git, and Argo CD, running in the cluster, makes the cluster match it:

| Stage | Phase | What happens |
|---|---|---|
| `deploy-<environment>` (80) | the environment | commits `environments/<environment>/` to the GitOps branch and waits until Argo CD reports the application synced and healthy |

Each environment directory holds the chart and `environment.yaml` with the
image tag and the environment's values, so the branch's history is the
history of every release, and a pull request against it is a release
proposal. When a release does not become healthy within `GITOPS_TIMEOUT`
(600 seconds), the script commits the previous release again and the stage
fails. `devops.sh rollback` is such a commit too.

| Question | Default | Meaning |
|---|---|---|
| `GITOPS_BRANCH` | `gitops` | branch of the project's repository with the desired state; created by the first release |
| `GITOPS_CANARY` | `yes` | the last environment as a canary with Argo Rollouts |
| `ARGOCD_HOST_PORT` | 8443 | k3s: Argo CD's web console on this machine (`admin`, `devops.sh get ARGOCD_ADMIN_PASSWORD`) |

`configure` installs Argo CD and Argo Rollouts from their release manifests,
adds the repository with the GitHub token, and creates one Application
`<app>-<environment>` per environment, which syncs automatically, prunes what
was removed from git and undoes changes made by hand.

**The canary** replaces the Deployment with an Argo Rollouts `Rollout`: the
new version first gets a quarter, then half of the pods, with a pause after
each step, and then all of them. Its pods must become ready within three
minutes, or Argo Rollouts aborts and keeps the stable version. Change the
steps with `canary.steps` in a chart of your own.

Images from a private registry need an image pull secret in the namespace;
GitOps never writes credentials to git. Create it once, or keep it in git
encrypted with Sealed Secrets.

## Secrets

The `vault` module (*Secrets* category) keeps the application's secrets in
HashiCorp Vault, one KV secret per environment:

```
secret/<app>/staging      e.g. GREETING=..., SPRING_DATASOURCE_PASSWORD=...
secret/<app>/production
```

The deploy stage reads the secret of its environment
([app-secrets.sh](../templates/scripts/app-secrets.sh)) and the application
gets each key as an environment variable, which Spring Boot maps to its
properties. Where they go depends on the deployment:

| Deployment | Where the secrets are |
|---|---|
| `docker-host` | `secrets.yml` next to the compose file on the machine, readable only by the deploy user |
| `kubernetes` | the Secret `<app>-env` of the Helm release; new pods when it changes |
| `argocd` with `sealed-secrets` | a SealedSecret in `environments/<environment>/environment.yaml` of the GitOps branch, encrypted for the cluster |
| `argocd` alone | the Secret `<app>-env`, put in the cluster by the pipeline, not in git |

A deployment picks up changed secrets the next time it runs; a rollback with
Helm (`rollback` without `--to`) brings back the secrets of that revision.

| Question | Default | Meaning |
|---|---|---|
| `VAULT_SERVER_URL` | empty | an existing Vault; empty: Vault in Docker |
| `VAULT_TOKEN` | | existing Vault: a token that may read `<mount>/data/<app>/*`; it should be periodic or long-lived |
| `VAULT_HOST_PORT` | 8200 | Vault in Docker: its port on this machine |
| `VAULT_KV_MOUNT` | `secret` | mount path of the KV version 2 secrets engine |

**Vault in Docker** keeps its data in a volume. `configure` initialises it
once (one unseal key; the key and the root token stay in `.devops/values`),
enables the KV engine, and gives the pipeline a token that may only read the
application's secrets and is renewed on every run. Vault starts sealed after
every restart: run `devops.sh configure` to unseal it. Write secrets in the
web console (`devops.sh urls`) or with the Vault CLI in the container:

```bash
./devops.sh compose exec -e VAULT_ADDR=http://127.0.0.1:8200 \
  -e VAULT_TOKEN="$(./devops.sh get VAULT_ROOT_TOKEN)" vault \
  vault kv put secret/hello-api/staging GREETING=Hi
```

**Sealed Secrets** (`sealed-secrets`, adds `kubernetes`): `configure`
installs the controller in `kube-system`. With Argo CD the pipeline encrypts
each secret with `kubeseal` for its namespace and name (strict scope), so the
release in git carries it, and only the controller in the cluster can decrypt
it. Seal secrets of your own the same way:

```bash
kubectl create secret generic db --dry-run=client --output yaml --from-literal=password=... \
  | kubeseal --controller-namespace kube-system --format yaml > sealed-db.yaml
```

## Database

The `postgresql` module (*Database* category) gives the application a
PostgreSQL database in each environment and checks its schema migrations
before anything is deployed. The schema lives in Flyway migrations in the
project (`src/main/resources/db/migration/V1__....sql`), and the application
applies them when it starts, as Spring Boot does with `flyway-core` on the
classpath.

| Stage | Phase | What happens |
|---|---|---|
| `migrate` (35) | ci | empties the ci database and applies every migration from scratch with the Flyway Maven plugin, then validates them; a broken migration fails the build |

Each deployment runs a PostgreSQL next to the application, its data kept
across releases and rollbacks:

| Deployment | The database |
|---|---|
| `docker-host` | a `db` container in the application's compose project on the machine, its data in a volume there |
| `kubernetes`, `argocd` | a StatefulSet `<app>-db` with a persistent volume in the environment's namespace |

The application gets `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and
`SPRING_DATASOURCE_PASSWORD` with the environment's secrets, so a Spring Boot
application needs no settings of its own. With Vault, the same names in the
environment's secret win: set them there to use a managed database instead.
When a release does not become healthy because a migration fails, the
previous version runs on: PostgreSQL rolls the failed migration back.
Write migrations the previous release can live with (add a column first,
remove the old one a release later), because a rollback does not undo them.

| Question | Default | Meaning |
|---|---|---|
| `DATABASE_NAME` | the image name, e.g. `hello_data` | database and user name in every environment |
| `DATABASE_HOST_PORT` | 5433 | the ci database on this machine |
| `FLYWAY_VERSION` | 12.4.0 | the Flyway version of the project, which Spring Boot manages (4.1: 12.4.0) |
| `FLYWAY_LOCATIONS` | `filesystem:src/main/resources/db/migration` | where the migrations are |

The passwords of the ci database and of each environment's database are
generated (`devops.sh get DATABASE_PRODUCTION_PASSWORD`). A database keeps the
password it was created with: after changing one, change it in the database
too (`ALTER USER`).

## Monitoring

The *Monitoring* modules watch the deployed application in each environment.
They run in Docker next to the other tools.

**Prometheus and Grafana** (`prometheus`): Prometheus scrapes the
application's metrics in each environment every 15 seconds, labelled
`application` and `environment`, and keeps them for 15 days. Grafana opens on
the dashboard *Application*: whether each environment is up, requests per
second, failed requests, response times (95th percentile and average), JVM
heap, CPU and database connections. Prometheus raises two alerts, visible at
`/alerts`: *ApplicationDown* when an environment does not answer for two
minutes, and *HighErrorRate* when more than 5% of its requests fail.

The application needs Micrometer's Prometheus registry, as in the examples:

```xml
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-registry-prometheus</artifactId>
  <scope>runtime</scope>
</dependency>
```

The deployments expose its endpoint (`/actuator/prometheus`) and turn on the
response time histograms through the application's environment
(`MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE`). Prometheus finds the
environments from the deployment: the machines and ports of `docker-host`, or
the ports of k3s. A cluster of your own is out of its reach; run Prometheus in
the cluster there.

| Question | Default | Meaning |
|---|---|---|
| `PROMETHEUS_HOST_PORT` | 9090 | Prometheus on this machine |
| `GRAFANA_HOST_PORT` | 3000 | Grafana on this machine (`admin`, `devops.sh get GRAFANA_ADMIN_PASSWORD`) |
| `METRICS_PATH` | `/actuator/prometheus` | where the application's metrics are |

**Loki** (`loki`, adds `prometheus`): Loki keeps the application's logs and
Grafana searches them, on the dashboard *Application logs* or in Explore with
queries such as `{environment="production"} |= "ERROR"`. Grafana Alloy
collects them with the labels `application`, `environment`, `container`
(`app`, `db`) and `source`: from the containers of the simulated machine,
which run on the same Docker, and from the pods of k3s. Machines and clusters
of your own need an agent there, such as Alloy, that pushes to Loki's push
URL (`devops.sh urls`).

| Question | Default | Meaning |
|---|---|---|
| `LOKI_HOST_PORT` | 3100 | Loki on this machine |

The configuration of Prometheus, Grafana and Alloy is written to
`.devops/monitoring` by `devops.sh up`; run `up` and `configure` again after
changing the deployment.

## Load test

The `k6` module (*Load test* category) load tests one environment
(`LOAD_TEST_ENVIRONMENT`, by default the one before the last, e.g. staging)
with [k6](https://k6.io) right after each deployment there:

| Stage | Phase | What happens |
|---|---|---|
| `load-test` (85) | that environment | `LOAD_TEST_VUS` virtual users call it for `LOAD_TEST_DURATION`; the stage fails when a threshold fails |

Without a script of its own, the project gets
[load-test.js](../templates/scripts/load-test.js): each virtual user calls
every path of `LOAD_TEST_PATHS` and pauses a second. It fails when more than
`LOAD_TEST_MAX_ERROR_RATE` of the requests fail or the 95th percentile of the
response times is above `LOAD_TEST_P95_MS`. A failed load test stops the
release there: the environments after it keep the version they have.

| Question | Default | Meaning |
|---|---|---|
| `LOAD_TEST_ENVIRONMENT` | the one before the last | environment the load test calls |
| `LOAD_TEST_URL` | found from the deployment | that environment as the pipeline reaches it; asked for a cluster of its own |
| `LOAD_TEST_SCRIPT` | empty | the project's k6 script, e.g. `src/test/k6/load.js`; it gets `BASE_URL` and the values below as `__ENV` |
| `LOAD_TEST_PATHS` | `/actuator/health` | paths the generic script calls, comma separated, e.g. `/hello,/actuator/health` |
| `LOAD_TEST_VUS` | 10 | virtual users |
| `LOAD_TEST_DURATION` | `30s` | how long |
| `LOAD_TEST_P95_MS` | 500 | highest 95th percentile response time in milliseconds |
| `LOAD_TEST_MAX_ERROR_RATE` | 0.01 | highest share of failed requests |

The summary is written to `target/load-test.json`. With the `prometheus`
module k6 also sends its metrics to Prometheus (`k6_http_reqs_total`,
`k6_http_req_duration_p95` and others, labelled `environment`), so a load
test shows next to the application's own metrics in Grafana. k6 is downloaded
where the pipeline runs, like the other tools.
