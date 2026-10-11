# <p align="center">DevOps Step by Step</p>

<p align="center">Building, testing and deploying Nexusphere to four environments with mvn-devops.</p>

## <p align="center">Table of Content</p>

* [Pipeline](#pipeline)
* [Before You Start](#before-you-start)
* [Set Up the Pipeline](#set-up-the-pipeline)
* [Settings of Each Environment](#settings-of-each-environment)
* [Run the Pipeline](#run-the-pipeline)
* [Databases](#databases)
* [Real Machines](#real-machines)
* [Every Day](#every-day)
* [Stop and Clean Up](#stop-and-clean-up)
* [Upgrade](#upgrade)

## Pipeline

<p style="text-align: justify;">

The pipeline is the simplest one mvn-devops offers, its `maven-basic` pipeline with Maven running on this machine:
Maven builds and tests the project, the ledger becomes a container image in a Docker registry, and Docker Compose runs
that image with its own PostgreSQL in four environments on one machine. Without a machine of your own, the machine is
a container on this one that mvn-devops starts. The image is built once and goes through the environments in order;
`dev`, `test` and `staging` follow each other on every run, and `prod` waits until you approve it. The settings live
in [ci/nexusphere.conf](../ci/nexusphere.conf), and `setup` copies them into `devops.conf` in the root.

</p>

| Phase     | Stages                                  | What happens                                                   |
|-----------|-----------------------------------------|----------------------------------------------------------------|
| `ci`      | validate, build, test, migrate, install | Builds, runs the unit tests and applies the Flyway migrations  |
| `cd`      | image                                   | Builds the ledger image with Jib and pushes it to the registry |
| `dev`     | deploy-dev                              | Runs the image with its PostgreSQL, checks its health          |
| `test`    | deploy-test                             | The same image, in `test`                                      |
| `staging` | deploy-staging                          | The same image, in `staging`                                   |
| `prod`    | approval, deploy-prod                   | Waits for `run --phase prod`, then deploys the same image      |

| Environment | Ledger                                         | Adminer                                        | Approval |
|-------------|------------------------------------------------|------------------------------------------------|----------|
| `dev`       | [http://localhost:8301](http://localhost:8301) | [http://localhost:8311](http://localhost:8311) | no       |
| `test`      | [http://localhost:8302](http://localhost:8302) | [http://localhost:8312](http://localhost:8312) | no       |
| `staging`   | [http://localhost:8303](http://localhost:8303) | [http://localhost:8313](http://localhost:8313) | no       |
| `prod`      | [http://localhost:8304](http://localhost:8304) | [http://localhost:8314](http://localhost:8314) | yes      |

<p style="text-align: justify;">

Checkstyle is turned off, because the Checkstyle version of that stage cannot parse some of the sources. The `migrate`
stage runs the migrations of `ledger/server` in the `ledger` schema, as the ledger does when it starts. The ports stay
clear of the services in [Service URLs](../README.md#service-urls), so the pipeline and the compose file can run on one
machine.

</p>

## Before You Start

<p style="text-align: justify;">

Every step runs in the root of the repository with Docker running. The machine needs the tools in
[Local Environment Setup](local-setup.md). On Windows use Git Bash and `mvn-devops\devops.bat` instead of
`mvn-devops/devops.sh`.

</p>

Step 1. Create a GitHub personal access token with the `repo` scope, as in
[mvn-devops/docs/github-setup.md](../mvn-devops/docs/github-setup.md).

Step 2. Check the prerequisites; it must end with `All good`:

```shell
mvn-devops/devops.sh doctor
```

## Set Up the Pipeline

Step 1. Set up the pipeline from its file; it starts the registry, the machine and the ci database:

```shell
mvn-devops/devops.sh setup --pipeline ci/nexusphere.conf
```

| Question           | Answer                                        |
|--------------------|-----------------------------------------------|
| GitHub username    | your GitHub user (asked only if git lacks it) |
| GitHub token       | the token from step 1 of Before You Start     |
| Any other question | Enter, to keep the default                    |

Setup ends with `Setup finished` and the machine's lines `dev: root@localhost can run Docker compose` up to `prod`.

Step 2. Show the stages; they must be the ones in [Pipeline](#pipeline), with the approval before `deploy-prod`:

```shell
mvn-devops/devops.sh stages
```

Step 3. Commit `devops.conf`, so a teammate gets the same pipeline (`.devops/` with the passwords stays out of git):

```shell
git add devops.conf
git commit -m "Set up the pipeline"
```

## Settings of Each Environment

<p style="text-align: justify;">

The ledger refuses to start without its own API key and signing key outside the `dev` profile. Each environment keeps
them in `app.env` on the machine, in `~/mvn-devops/nexusphere-ledger-<environment>/`, which deployments read and never
overwrite. The database settings come from the pipeline.

</p>

Step 1. Write `app.env` with a new API key and Ed25519 signing key for each environment on the machine:

```shell
for env in dev test staging prod; do
  openssl genpkey -algorithm ed25519 -outform DER -out ledger-$env.der
  printf 'LEDGER_API_KEY=%s\nLEDGER_SIGNING_PRIVATE_KEY=%s\nLEDGER_SIGNING_PUBLIC_KEY=%s\n' "$(openssl rand -hex 32)" "$(base64 -w0 ledger-$env.der)" "$(openssl pkey -inform DER -in ledger-$env.der -pubout -outform DER | base64 -w0)" | ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR -i .devops/keys/deploy -p 2222 root@localhost "mkdir -p ~/mvn-devops/nexusphere-ledger-$env && umask 077 && cat > ~/mvn-devops/nexusphere-ledger-$env/app.env"
  rm ledger-$env.der
done
```

Step 2. Check the files; each must hold the three settings:

```shell
ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR -i .devops/keys/deploy -p 2222 root@localhost 'for f in ~/mvn-devops/*/app.env; do echo "$f $(cut -d= -f1 $f | tr "\n" " ")"; done'
```

```text
/root/mvn-devops/nexusphere-ledger-dev/app.env LEDGER_API_KEY LEDGER_SIGNING_PRIVATE_KEY LEDGER_SIGNING_PUBLIC_KEY
/root/mvn-devops/nexusphere-ledger-prod/app.env LEDGER_API_KEY LEDGER_SIGNING_PRIVATE_KEY LEDGER_SIGNING_PUBLIC_KEY
/root/mvn-devops/nexusphere-ledger-staging/app.env LEDGER_API_KEY LEDGER_SIGNING_PRIVATE_KEY LEDGER_SIGNING_PUBLIC_KEY
/root/mvn-devops/nexusphere-ledger-test/app.env LEDGER_API_KEY LEDGER_SIGNING_PRIVATE_KEY LEDGER_SIGNING_PUBLIC_KEY
```

## Run the Pipeline

Step 1. Run ci, cd, `dev`, `test` and `staging`; it must end with `prod waits for approval`:

```shell
mvn-devops/devops.sh run
```

Step 2. Approve `prod` and deploy the same image to it; it must end with `Pipeline finished`:

```shell
mvn-devops/devops.sh run --phase prod
```

Step 3. Check the four environments; each must answer `UP`:

```shell
for port in 8301 8302 8303 8304; do echo "$port $(curl -s http://localhost:$port/actuator/health | jq -r .status)"; done
```

Step 4. Call the `prod` ledger with its API key; the answer must be its head:

```shell
KEY=$(ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR -i .devops/keys/deploy -p 2222 root@localhost 'grep ^LEDGER_API_KEY= ~/mvn-devops/nexusphere-ledger-prod/app.env | cut -d= -f2')
curl -s http://localhost:8304/api/v1/ledger/head -H "Authorization: Bearer $KEY" | jq -c 'keys'
```

```text
["hash","sequence"]
```

## Databases

<p style="text-align: justify;">

Each environment's PostgreSQL runs as the `db` service of the environment's compose project on the machine, with the
database and user `ledger` and a password the pipeline generated. An Adminer per environment joins that project's
network.

</p>

Step 1. Start an Adminer for each environment on the machine:

```shell
for pair in dev:8311 test:8312 staging:8313 prod:8314; do
  env=${pair%%:*}; port=${pair##*:}
  ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR -i .devops/keys/deploy -p 2222 root@localhost "docker run -d --name nexusphere-ledger-$env-adminer --restart unless-stopped --network nexusphere-ledger-${env}_default -p $port:8080 -e ADMINER_DEFAULT_SERVER=db adminer php -S 0.0.0.0:8080 -t /var/www/html"
done
```

Step 2. Print the password of the `prod` database:

```shell
mvn-devops/devops.sh get DATABASE_PROD_PASSWORD
```

Step 3. Open [http://localhost:8314](http://localhost:8314) and sign in with system `PostgreSQL`, server `db`, user
and database `ledger` and that password; the `ledger` schema must hold `evidence_record`.

## Real Machines

<p style="text-align: justify;">

The four environments can run on machines of your own, one per environment or several on one, instead of the
machine in Docker. Set `DEPLOY_SERVER_URL` for `dev` and `DEPLOY_TEST_SERVER_URL`, `DEPLOY_STAGING_SERVER_URL` and
`DEPLOY_PROD_SERVER_URL` for the others in [ci/nexusphere.conf](../ci/nexusphere.conf), as
`ssh://deploy@machine.example.com:22`, and run `setup --pipeline ci/nexusphere.conf` again. Each machine needs Docker
with compose and the deploy user in the `docker` group; add the key from `mvn-devops/devops.sh get
DEPLOY_SSH_PUBLIC_KEY` to that user's `~/.ssh/authorized_keys`, and `mvn-devops/devops.sh configure` records the host
keys and checks the login. The machines pull the image from the registry, so they need a registry they can reach, set
with `REGISTRY_SERVER_URL`. The ssh commands above then use the machine's user, host and port instead of
`root@localhost` and `-p 2222`. The details are in
[mvn-devops/docs/deployment.md](../mvn-devops/docs/deployment.md#deploying-to-a-machine).

</p>

## Every Day

| Command                                       | Does                                                     |
|-----------------------------------------------|----------------------------------------------------------|
| `mvn-devops/devops.sh run`                    | Builds, tests and deploys to `dev`, `test` and `staging` |
| `mvn-devops/devops.sh run --phase prod`       | Approves `prod` and deploys the image `staging` runs     |
| `mvn-devops/devops.sh run --from deploy-test` | Runs again from one stage                                |
| `mvn-devops/devops.sh rollback staging`       | Puts the previous image back in an environment           |
| `mvn-devops/devops.sh rollback prod --to TAG` | Deploys the image of an earlier commit tag               |
| `mvn-devops/devops.sh stages`                 | Prints the stages and the exact commands they run        |
| `mvn-devops/devops.sh status`                 | Shows the registry, the machine and the ci database      |
| `mvn-devops/devops.sh urls`                   | Prints the URLs of the registry and the environments     |

## Stop and Clean Up

Step 1. Stop the Adminers and the four environments on the machine; their databases are kept:

```shell
ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR -i .devops/keys/deploy -p 2222 root@localhost 'for env in dev test staging prod; do docker rm -f nexusphere-ledger-$env-adminer; docker compose -p nexusphere-ledger-$env down; done'
```

Step 2. To remove everything, delete the four databases too:

```shell
ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR -i .devops/keys/deploy -p 2222 root@localhost 'for env in dev test staging prod; do docker volume rm nexusphere-ledger-${env}_db-data; done'
```

Step 3. Stop the registry, the machine and the ci database; their data is kept:

```shell
mvn-devops/devops.sh down
```

Step 4. Remove them with their data; it asks before removing the containers and volumes, and again before deleting
`.devops/`:

```shell
mvn-devops/devops.sh destroy
```

## Upgrade

Step 1. Check whether a newer mvn-devops release exists:

```shell
mvn-devops/devops.sh upgrade --check
```

Step 2. Replace the `mvn-devops` folder with it, checked against the release's `SHA256SUMS`, then commit the folder:

```shell
mvn-devops/devops.sh upgrade
git add mvn-devops
git commit -m "Upgrade mvn-devops"
```

##

**<p align="center">[Top](#devops-step-by-step)</p>**
