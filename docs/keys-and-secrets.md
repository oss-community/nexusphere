# <p align="center">Keys and Secrets</p>

<p align="center">Every key and secret of the ledger: its development value, how to generate it, and how to hand it over.</p>

## <p align="center">Table of Content</p>

* [What the Ledger Needs](#what-the-ledger-needs)
* [Development Values](#development-values)
* [Generate the Keys](#generate-the-keys)
* [Environment Variables](#environment-variables)
* [Secret Files](#secret-files)
* [Signing Inside Vault](#signing-inside-vault)
* [Keys of the Four Environments](#keys-of-the-four-environments)
* [Agent API Keys](#agent-api-keys)
* [Rotate a Key](#rotate-a-key)
* [Clean Up](#clean-up)

## What the Ledger Needs

<p style="text-align: justify;">

The ledger needs three secrets of its own: the operator API key, the Ed25519 signing key pair, and the password of its
PostgreSQL. In `dev` and `test` they have fixed, published values, so anyone can start the ledger without preparing
anything. In `staging`, `prod` and every other place they are generated once for that place and never shared. The
ledger refuses to start with the published values unless the `dev` profile is on, so a published key can never sign
real evidence by mistake.

</p>

| Secret              | Setting                                       | `dev` and `test`                                    | `staging` and `prod`                                     |
|---------------------|-----------------------------------------------|-----------------------------------------------------|----------------------------------------------------------|
| Operator API key    | `LEDGER_API_KEY`                              | Fixed, in [Development Values](#development-values) | Generated, as in [Generate the Keys](#generate-the-keys) |
| Signing private key | `LEDGER_SIGNING_PRIVATE_KEY`                  | Fixed                                               | Generated, or kept inside Vault                          |
| Signing public key  | `LEDGER_SIGNING_PUBLIC_KEY`                   | Fixed                                               | Generated with the private key                           |
| Database password   | `LEDGER_DATABASE_PASSWORD`                    | `ledger`                                            | Generated                                                |
| Vault token         | `LEDGER_SIGNING_VAULT_TOKEN` or `_TOKEN_FILE` | `nexusphere-vault-development-token`                | Created in Vault with a narrow policy                    |
| Agent API keys      | Issued by the ledger                          | Issued by the ledger                                | Issued by the ledger                                     |

<p style="text-align: justify;">

Other secrets belong to services outside the ledger and come from them, with no development value: the CIBA client
secret `LEDGER_APPROVAL_CIBA_CLIENT_SECRET`, the model key `LEDGER_ASK_MODEL_API_KEY`, the `Authorization` header of a
gateway target in `LEDGER_MCP_SERVERS_{NAME}_AUTHORIZATION` and `LEDGER_A2A_AGENTS_{NAME}_AUTHORIZATION`, and the alert
channels' `ALERT_SMTP_PASSWORD`, `ALERT_SLACK_WEBHOOK_URL` and `ALERT_TELEGRAM_BOT_TOKEN`. They are handed over exactly
like the ledger's own secrets below. Clients that call the ledger as the operator, such as the
[OpenTelemetry Exporter](../ledger/otel-exporter/README.md) with `NEXUSPHERE_LEDGER_API_KEY`, get the same operator
key as the ledger they call.

</p>

## Development Values

<p style="text-align: justify;">

These values are published in this repository and are the same on every machine. The `dev` profile of the ledger uses
them when no other value is set, and the compose file and the `dev` and `test` environments of the pipeline use them.
They are fine for a laptop or a shared test environment and never for real evidence.

</p>

```properties
LEDGER_PROFILES=postgresql,dev
LEDGER_API_KEY=nexusphere-ledger-development-key-change-me
LEDGER_SIGNING_PRIVATE_KEY=MC4CAQAwBQYDK2VwBCIEIDPXxsOX77t9k2XIr5aVh1AORIeW8w4rXooP+vMeOPKL
LEDGER_SIGNING_PUBLIC_KEY=MCowBQYDK2VwAyEAZAywHYwDVyxoGB6wLRHPgAv1MUe4LTjMnb7uYOZii3o=
LEDGER_DATABASE_PASSWORD=ledger
LEDGER_SIGNING_VAULT_TOKEN=nexusphere-vault-development-token
```

<p style="text-align: justify;">

The compose file also signs in to Keycloak as `admin` with the password `admin`, and to its PostgreSQL as `ledger`
with the password `ledger`; the pipeline's own Vault and databases get generated passwords, shown by
`mvn-devops/devops.sh get VAULT_ROOT_TOKEN` and `mvn-devops/devops.sh get DATABASE_<ENV>_PASSWORD`.

</p>

Step 1. Build the ledger; Maven must end with `BUILD SUCCESS`:

```shell
mvn -B install -DskipTests=true
```

Step 2. Start the ledger's PostgreSQL from the compose file and the ledger with the `dev` profile and no secrets at
all; the health check must answer `UP`:

```shell
docker compose --file ledger/compose.yaml --project-name ledger up -d --wait ledger-postgresql
LEDGER_PROFILES=postgresql,dev LEDGER_DATABASE_PORT=5433 java -jar ledger/server/target/server-1.0.0-SNAPSHOT-exec.jar > ledger.log 2>&1 &
echo $! > ledger.pid
until curl -sf http://localhost:8090/actuator/health; do sleep 1; done
```

Step 3. Read the active signing key with the development API key; it must be the development public key above:

```shell
curl -s http://localhost:8090/api/v1/keys -H "Authorization: Bearer nexusphere-ledger-development-key-change-me" | jq -r '.[] | select(.status == "ACTIVE") | .publicKey'
```

Step 4. Stop the ledger, and start it without the `dev` profile but with the development API key; it must stop at
once with this message, before it touches the database:

```shell
kill $(cat ledger.pid); while kill -0 $(cat ledger.pid) 2>/dev/null; do sleep 1; done
LEDGER_API_KEY=nexusphere-ledger-development-key-change-me java -jar ledger/server/target/server-1.0.0-SNAPSHOT-exec.jar 2>&1 | grep -m1 -o 'ledger.security.api-key uses .*'
```

```text
ledger.security.api-key uses the published development secret outside the dev profile
```

## Generate the Keys

<p style="text-align: justify;">

Generate the keys of an environment once, on a trusted machine, and keep them in a folder only you can read, outside
the repository. Each file is named after its setting, so the folder can be used directly as a secret folder, as in
[Secret Files](#secret-files). The steps use `staging`; set `ENV` to another name for another environment.

</p>

Step 1. Create the folder of the environment, readable only by you:

```shell
ENV=staging
mkdir -p ~/nexusphere-keys/$ENV && chmod 700 ~/nexusphere-keys ~/nexusphere-keys/$ENV
umask 077
```

Step 2. Generate the operator API key and the database password, 32 and 24 random bytes in hex:

```shell
openssl rand -hex 32 > ~/nexusphere-keys/$ENV/LEDGER_API_KEY
openssl rand -hex 24 > ~/nexusphere-keys/$ENV/LEDGER_DATABASE_PASSWORD
```

Step 3. Generate the Ed25519 signing key, write the private key as base64 PKCS#8 and the public key as base64 X.509,
and delete the binary key:

```shell
openssl genpkey -algorithm ed25519 -outform DER -out ~/nexusphere-keys/$ENV/signing.der
base64 -w0 ~/nexusphere-keys/$ENV/signing.der > ~/nexusphere-keys/$ENV/LEDGER_SIGNING_PRIVATE_KEY
openssl pkey -inform DER -in ~/nexusphere-keys/$ENV/signing.der -pubout -outform DER | base64 -w0 > ~/nexusphere-keys/$ENV/LEDGER_SIGNING_PUBLIC_KEY
rm ~/nexusphere-keys/$ENV/signing.der
```

Step 4. Check the folder; it must hold the four files, readable only by you, and the two keys must belong together:

```shell
ls -l ~/nexusphere-keys/$ENV
[ "$(openssl pkey -inform DER -in <(base64 -d ~/nexusphere-keys/$ENV/LEDGER_SIGNING_PRIVATE_KEY) -pubout -outform DER | base64 -w0)" = "$(cat ~/nexusphere-keys/$ENV/LEDGER_SIGNING_PUBLIC_KEY)" ] && echo "The keys match"
```

```text
-rw------- 1 user user 65 Oct 11 15:25 LEDGER_API_KEY
-rw------- 1 user user 49 Oct 11 15:25 LEDGER_DATABASE_PASSWORD
-rw------- 1 user user 64 Oct 11 15:25 LEDGER_SIGNING_PRIVATE_KEY
-rw------- 1 user user 60 Oct 11 15:25 LEDGER_SIGNING_PUBLIC_KEY
The keys match
```

<p style="text-align: justify;">

Keep a second copy of `LEDGER_SIGNING_PRIVATE_KEY` offline. A backup of the database cannot sign anything new
without it, and losing it means an unendorsed rotation, as in [Rotate a Key](#rotate-a-key).

</p>

## Environment Variables

<p style="text-align: justify;">

The simplest way to hand the keys over is one environment variable per setting. The steps run the ledger on this
machine with the `staging` keys against a PostgreSQL of its own, so it does not mix with the development database.

</p>

Step 1. Start a PostgreSQL on port 5434 whose password is the generated one; it must answer `accepting connections`:

```shell
ENV=staging
docker run -d --name ledger-keys-postgresql -e POSTGRES_DB=ledger -e POSTGRES_USER=ledger -e POSTGRES_PASSWORD="$(cat ~/nexusphere-keys/$ENV/LEDGER_DATABASE_PASSWORD)" -p 5434:5432 postgres:18-alpine
sleep 5 && docker exec ledger-keys-postgresql pg_isready -U ledger -d ledger
```

Step 2. Set the keys as environment variables and start the ledger; the health check must answer `UP`:

```shell
export LEDGER_API_KEY=$(cat ~/nexusphere-keys/$ENV/LEDGER_API_KEY)
export LEDGER_SIGNING_PRIVATE_KEY=$(cat ~/nexusphere-keys/$ENV/LEDGER_SIGNING_PRIVATE_KEY)
export LEDGER_SIGNING_PUBLIC_KEY=$(cat ~/nexusphere-keys/$ENV/LEDGER_SIGNING_PUBLIC_KEY)
export LEDGER_DATABASE_PASSWORD=$(cat ~/nexusphere-keys/$ENV/LEDGER_DATABASE_PASSWORD)
export LEDGER_DATABASE_PORT=5434
java -jar ledger/server/target/server-1.0.0-SNAPSHOT-exec.jar > ledger.log 2>&1 &
echo $! > ledger.pid
until curl -sf http://localhost:8090/actuator/health; do sleep 1; done
```

Step 3. Read the active key with the new API key; it must be the generated public key, and the development API key
must get `401`:

```shell
curl -s http://localhost:8090/api/v1/keys -H "Authorization: Bearer $LEDGER_API_KEY" | jq -r '.[] | select(.status == "ACTIVE") | .publicKey'
cat ~/nexusphere-keys/$ENV/LEDGER_SIGNING_PUBLIC_KEY; echo
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8090/api/v1/keys -H "Authorization: Bearer nexusphere-ledger-development-key-change-me"
```

Step 4. Stop the ledger and remove the variables from the shell:

```shell
kill $(cat ledger.pid); while kill -0 $(cat ledger.pid) 2>/dev/null; do sleep 1; done
unset LEDGER_API_KEY LEDGER_SIGNING_PRIVATE_KEY LEDGER_SIGNING_PUBLIC_KEY LEDGER_DATABASE_PASSWORD
```

<p style="text-align: justify;">

With Docker Compose the same variables go in the `environment` of the ledger service or in an `.env` file next to the
compose file, and in Kubernetes in the `env` of the container. Environment variables are visible to every process
that can read the ledger's environment, so outside a laptop prefer secret files or Vault.

</p>

## Secret Files

<p style="text-align: justify;">

The ledger also reads every file in `LEDGER_SECRETS_DIR`, `/run/secrets/` by default, as the setting named like the
file, with a trailing newline removed. The folder from [Generate the Keys](#generate-the-keys) is such a folder.
This is how Docker secrets, Kubernetes secrets, the Vault agent, the External Secrets Operator and the Secrets Store
CSI driver hand secrets over: they all write files. A missing folder is ignored.

</p>

Step 1. Start the ledger with the folder as its secret folder and no secret in the environment; the health check must
answer `UP`:

```shell
ENV=staging
LEDGER_DATABASE_PORT=5434 LEDGER_SECRETS_DIR=$HOME/nexusphere-keys/$ENV/ java -jar ledger/server/target/server-1.0.0-SNAPSHOT-exec.jar > ledger.log 2>&1 &
echo $! > ledger.pid
until curl -sf http://localhost:8090/actuator/health; do sleep 1; done
```

Step 2. Read the active key; it must again be the generated public key:

```shell
curl -s http://localhost:8090/api/v1/keys -H "Authorization: Bearer $(cat ~/nexusphere-keys/$ENV/LEDGER_API_KEY)" | jq -r '.[] | select(.status == "ACTIVE") | .publicKey'
```

Step 3. Stop the ledger:

```shell
kill $(cat ledger.pid); while kill -0 $(cat ledger.pid) 2>/dev/null; do sleep 1; done
```

<p style="text-align: justify;">

With Docker Compose, mount each file as a secret of the same name:

</p>

```yaml
services:
  ledger:
    secrets:
      - LEDGER_API_KEY
      - LEDGER_SIGNING_PRIVATE_KEY
      - LEDGER_SIGNING_PUBLIC_KEY
      - LEDGER_DATABASE_PASSWORD
secrets:
  LEDGER_API_KEY:
    file: ~/nexusphere-keys/staging/LEDGER_API_KEY
  LEDGER_SIGNING_PRIVATE_KEY:
    file: ~/nexusphere-keys/staging/LEDGER_SIGNING_PRIVATE_KEY
  LEDGER_SIGNING_PUBLIC_KEY:
    file: ~/nexusphere-keys/staging/LEDGER_SIGNING_PUBLIC_KEY
  LEDGER_DATABASE_PASSWORD:
    file: ~/nexusphere-keys/staging/LEDGER_DATABASE_PASSWORD
```

<p style="text-align: justify;">

In Kubernetes, create a Secret from the folder and mount it in a folder of its own, not on `/run/secrets` itself, so
the service account token Kubernetes mounts there stays in place. With Vault or a cloud secret manager, let the
External Secrets Operator create this Secret, or let the CSI driver mount the same files; the ledger does not change.

</p>

```shell
kubectl create secret generic ledger-secrets --namespace production --from-file=$HOME/nexusphere-keys/staging/
```

```yaml
          env:
            - name: LEDGER_SECRETS_DIR
              value: /run/secrets/ledger/
          volumeMounts:
            - name: ledger-secrets
              mountPath: /run/secrets/ledger
              readOnly: true
      volumes:
        - name: ledger-secrets
          secret:
            secretName: ledger-secrets
```

## Signing Inside Vault

<p style="text-align: justify;">

With Vault the signing key can be kept where nobody can copy it: the ledger signs through the transit engine and
never holds the private key, as described in [Keys in Vault](../ledger/README.md#keys-in-vault). The steps use the
Vault of the compose file, which runs in development mode with the root token `nexusphere-vault-development-token`
and already holds the transit key `nexusphere-ledger`; on a real Vault, run the same `vault` commands with your own
address and token. The ledger keeps its operator key and database password from the secret folder.

</p>

Step 1. Start Vault from the compose file and check the transit key; it must answer `ed25519`:

```shell
docker compose --file ledger/compose.yaml --project-name ledger up -d vault-init
vault() { docker exec -i -e VAULT_ADDR=http://127.0.0.1:8200 -e VAULT_TOKEN=nexusphere-vault-development-token vault vault "$@"; }
vault read -field=type transit/keys/nexusphere-ledger
```

<p style="text-align: justify;">

On a real Vault, create the key once with `vault secrets enable transit` and
`vault write -f transit/keys/nexusphere-ledger type=ed25519`; `exportable` stays false, so nobody can read the private
key.

</p>

Step 2. Give the ledger a policy that may only read the key and sign with it, and a token with that policy; the
token's policies must be `default` and `nexusphere-ledger`:

```shell
vault policy write nexusphere-ledger - <<'POLICY'
path "transit/keys/nexusphere-ledger" { capabilities = ["read"] }
path "transit/sign/nexusphere-ledger" { capabilities = ["update"] }
POLICY
(umask 077; vault token create -policy=nexusphere-ledger -period=768h -field=token > ~/nexusphere-keys/vault-token)
vault token lookup -format=json "$(cat ~/nexusphere-keys/vault-token)" | jq -c '.data.policies'
```

Step 3. Start the ledger with the Vault provider and the token file. It signed with the generated local key until now,
so that key goes in `LEDGER_SIGNING_PREVIOUS_PRIVATE_KEY` for this one start, to endorse the move; the health check
must answer `UP`:

```shell
ENV=staging
export LEDGER_DATABASE_PORT=5434 LEDGER_SECRETS_DIR=$HOME/nexusphere-keys/$ENV/
export LEDGER_SIGNING_PROVIDER=vault
export LEDGER_SIGNING_VAULT_ADDRESS=http://localhost:8200
export LEDGER_SIGNING_VAULT_TOKEN_FILE=$HOME/nexusphere-keys/vault-token
export LEDGER_SIGNING_PREVIOUS_PRIVATE_KEY=$(cat ~/nexusphere-keys/$ENV/LEDGER_SIGNING_PRIVATE_KEY)
java -jar ledger/server/target/server-1.0.0-SNAPSHOT-exec.jar > ledger.log 2>&1 &
echo $! > ledger.pid
until curl -sf http://localhost:8090/actuator/health; do sleep 1; done
```

Step 4. Check the keys; the Vault key must be `ACTIVE`, and its `previous` must be the local key, now `RETIRED`:

```shell
curl -s http://localhost:8090/api/v1/keys -H "Authorization: Bearer $(cat ~/nexusphere-keys/$ENV/LEDGER_API_KEY)" | jq -c '.[] | {keyId, status, previous: .rotation.previousKeyId}'
```

```text
{"keyId":"626b124730749a9b","status":"RETIRED","previous":null}
{"keyId":"dea48a68cbd010fe","status":"ACTIVE","previous":"626b124730749a9b"}
```

Step 5. Stop the ledger and remove the variables; from now on the local key is only needed to read old signatures,
which stay valid through the published public key:

```shell
kill $(cat ledger.pid); while kill -0 $(cat ledger.pid) 2>/dev/null; do sleep 1; done
unset LEDGER_DATABASE_PORT LEDGER_SECRETS_DIR LEDGER_SIGNING_PROVIDER LEDGER_SIGNING_VAULT_ADDRESS LEDGER_SIGNING_VAULT_TOKEN_FILE LEDGER_SIGNING_PREVIOUS_PRIVATE_KEY
```

<p style="text-align: justify;">

In production the Vault agent writes the token file and renews it; the ledger reads the file again on every call.

</p>

## Keys of the Four Environments

<p style="text-align: justify;">

The pipeline in [DevOps Step by Step](devops-guide.md) runs the ledger in `dev`, `test`, `staging` and `prod`, and
starts a Vault of its own on [http://localhost:8201](http://localhost:8201/ui/). Each environment's secrets are one
Vault secret, `secret/nexusphere-ledger/<environment>`; every deployment reads the secret of its environment and gives
each of its keys to the ledger as an environment variable. `dev` and `test` get the development values with the `dev`
profile, while `staging` and `prod` get generated keys. The database password comes from the pipeline itself.

</p>

Step 1. Set up the pipeline as in [Set Up the Pipeline](devops-guide.md#set-up-the-pipeline), and check that its
Vault runs; the line must name `secret/nexusphere-ledger/<environment>`:

```shell
mvn-devops/devops.sh urls | grep Vault
```

Step 2. Define a `vault` command that runs the Vault CLI in the pipeline's Vault with its root token:

```shell
vault() { mvn-devops/devops.sh compose exec -T -e VAULT_ADDR=http://127.0.0.1:8200 -e VAULT_TOKEN="$(mvn-devops/devops.sh get VAULT_ROOT_TOKEN)" vault vault "$@"; }
```

Step 3. Put the development values in `dev` and `test`:

```shell
for env in dev test; do
  vault kv put secret/nexusphere-ledger/$env LEDGER_PROFILES=postgresql,dev LEDGER_API_KEY=nexusphere-ledger-development-key-change-me LEDGER_SIGNING_PRIVATE_KEY=MC4CAQAwBQYDK2VwBCIEIDPXxsOX77t9k2XIr5aVh1AORIeW8w4rXooP+vMeOPKL LEDGER_SIGNING_PUBLIC_KEY=MCowBQYDK2VwAyEAZAywHYwDVyxoGB6wLRHPgAv1MUe4LTjMnb7uYOZii3o=
done
```

Step 4. Generate the keys of `staging` and `prod` as in [Generate the Keys](#generate-the-keys), with `ENV=staging`
and then `ENV=prod`, and put them in their secrets:

```shell
for env in staging prod; do
  vault kv put secret/nexusphere-ledger/$env LEDGER_API_KEY="$(cat ~/nexusphere-keys/$env/LEDGER_API_KEY)" LEDGER_SIGNING_PRIVATE_KEY="$(cat ~/nexusphere-keys/$env/LEDGER_SIGNING_PRIVATE_KEY)" LEDGER_SIGNING_PUBLIC_KEY="$(cat ~/nexusphere-keys/$env/LEDGER_SIGNING_PUBLIC_KEY)"
done
```

Step 5. Check the four secrets; `dev` and `test` must hold four keys, `staging` and `prod` three:

```shell
for env in dev test staging prod; do echo "$env $(vault kv get -format=json secret/nexusphere-ledger/$env | jq -c '.data.data | keys')"; done
```

```text
dev ["LEDGER_API_KEY","LEDGER_PROFILES","LEDGER_SIGNING_PRIVATE_KEY","LEDGER_SIGNING_PUBLIC_KEY"]
test ["LEDGER_API_KEY","LEDGER_PROFILES","LEDGER_SIGNING_PRIVATE_KEY","LEDGER_SIGNING_PUBLIC_KEY"]
staging ["LEDGER_API_KEY","LEDGER_SIGNING_PRIVATE_KEY","LEDGER_SIGNING_PUBLIC_KEY"]
prod ["LEDGER_API_KEY","LEDGER_SIGNING_PRIVATE_KEY","LEDGER_SIGNING_PUBLIC_KEY"]
```

Step 6. Run the pipeline as in [Run the Pipeline](devops-guide.md#run-the-pipeline), then read each environment's
active key with the API key from its secret; each must print `same`:

```shell
for pair in dev:8301 test:8302 staging:8303 prod:8304; do
  env=${pair%%:*}; port=${pair##*:}
  key=$(vault kv get -field=LEDGER_API_KEY secret/nexusphere-ledger/$env)
  public=$(vault kv get -field=LEDGER_SIGNING_PUBLIC_KEY secret/nexusphere-ledger/$env)
  active=$(curl -s http://localhost:$port/api/v1/keys -H "Authorization: Bearer $key" | jq -r '.[] | select(.status == "ACTIVE") | .publicKey')
  echo "$env $([ "$active" = "$public" ] && echo same || echo different)"
done
```

<p style="text-align: justify;">

A changed secret reaches the environment at its next deployment, such as `mvn-devops/devops.sh run --from
deploy-staging`. The secrets can also be read and changed in the Vault web console, signed in with the root token.

</p>

### Without Vault

<p style="text-align: justify;">

Without Vault, the same settings go in `app.env` in the environment's folder on the machine,
`~/mvn-devops/nexusphere-ledger-<environment>/`, which every deployment reads as environment variables and never
overwrites. A value in Vault wins over the same setting in `app.env`. The steps use `staging`; remove its Vault secret
first when it has one.

</p>

Step 1. Remove the Vault secret of `staging`, if it has one:

```shell
vault kv metadata delete secret/nexusphere-ledger/staging
```

Step 2. Write `app.env` of `staging` from its generated keys on the machine, readable only by its user:

```shell
ENV=staging
printf 'LEDGER_API_KEY=%s\nLEDGER_SIGNING_PRIVATE_KEY=%s\nLEDGER_SIGNING_PUBLIC_KEY=%s\n' "$(cat ~/nexusphere-keys/$ENV/LEDGER_API_KEY)" "$(cat ~/nexusphere-keys/$ENV/LEDGER_SIGNING_PRIVATE_KEY)" "$(cat ~/nexusphere-keys/$ENV/LEDGER_SIGNING_PUBLIC_KEY)" | ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR -i .devops/keys/deploy -p 2222 root@localhost "mkdir -p ~/mvn-devops/nexusphere-ledger-$ENV && umask 077 && cat > ~/mvn-devops/nexusphere-ledger-$ENV/app.env"
```

Step 3. Deploy `staging` again; it must end with `prod waits for approval`:

```shell
mvn-devops/devops.sh run --from deploy-staging
```

Step 4. Read the active key of `staging`; it must be the generated public key:

```shell
curl -s http://localhost:8303/api/v1/keys -H "Authorization: Bearer $(cat ~/nexusphere-keys/$ENV/LEDGER_API_KEY)" | jq -r '.[] | select(.status == "ACTIVE") | .publicKey'
cat ~/nexusphere-keys/$ENV/LEDGER_SIGNING_PUBLIC_KEY; echo
```

<p style="text-align: justify;">

For `dev` and `test` without Vault, `app.env` holds only `LEDGER_PROFILES=postgresql,dev`; the `dev` profile then
supplies the development values itself.

</p>

## Agent API Keys

<p style="text-align: justify;">

Each agent has its own API key, which the ledger generates when the operator registers the agent and shows once. The
ledger keeps only its hash, so a lost key cannot be read back, only replaced. Keep it where the agent runs, in the same
way as above: an environment variable, a secret file or a Vault secret of the agent.

</p>

Step 1. Start the ledger with the `dev` profile as in [Development Values](#development-values), steps 2 and 3.

Step 2. Register an agent and keep its key; the key must start with `nxl_`:

```shell
curl -s -X POST http://localhost:8090/api/v1/agents -H "Authorization: Bearer nexusphere-ledger-development-key-change-me" -H "Content-Type: application/json" -d '{"agentId":"keys-agent","name":"Keys agent","ownerId":"acme"}' | jq -r .apiKey > ~/nexusphere-keys/keys-agent
cut -c1-4 ~/nexusphere-keys/keys-agent
```

Step 3. Replace the key; the old key must get `401` and the new one `200`:

```shell
old=$(cat ~/nexusphere-keys/keys-agent)
curl -s -X POST http://localhost:8090/api/v1/agents/keys-agent/key -H "Authorization: Bearer nexusphere-ledger-development-key-change-me" | jq -r .apiKey > ~/nexusphere-keys/keys-agent
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8090/api/v1/agents/keys-agent -H "Authorization: Bearer $old"
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8090/api/v1/agents/keys-agent -H "Authorization: Bearer $(cat ~/nexusphere-keys/keys-agent)"
```

Step 4. Stop the ledger:

```shell
kill $(cat ledger.pid); while kill -0 $(cat ledger.pid) 2>/dev/null; do sleep 1; done
```

## Rotate a Key

<p style="text-align: justify;">

The operator API key and the database password change by changing the setting and restarting; nothing about them is
recorded. The signing key is the ledger's identity, so its change is recorded and signed: the new key is endorsed by
the old one, retired keys stay published in `/api/v1/keys` and in packages, and everything they signed still
verifies. The steps rotate the keys of `prod` in the pipeline's Vault.

</p>

Step 1. Generate new keys as in [Generate the Keys](#generate-the-keys) with `ENV=prod-next`.

Step 2. Put the new keys in the secret of `prod`, with the current private key as the previous key:

```shell
vault kv patch secret/nexusphere-ledger/prod LEDGER_API_KEY="$(cat ~/nexusphere-keys/prod-next/LEDGER_API_KEY)" LEDGER_SIGNING_PRIVATE_KEY="$(cat ~/nexusphere-keys/prod-next/LEDGER_SIGNING_PRIVATE_KEY)" LEDGER_SIGNING_PUBLIC_KEY="$(cat ~/nexusphere-keys/prod-next/LEDGER_SIGNING_PUBLIC_KEY)" LEDGER_SIGNING_PREVIOUS_PRIVATE_KEY="$(cat ~/nexusphere-keys/prod/LEDGER_SIGNING_PRIVATE_KEY)"
```

Step 3. Deploy `prod` again; it must end with `Pipeline finished`:

```shell
mvn-devops/devops.sh run --phase prod
```

Step 4. Check the keys of `prod` with the new API key; the new key must be `ACTIVE` with the old one as its
`previous`:

```shell
curl -s http://localhost:8304/api/v1/keys -H "Authorization: Bearer $(cat ~/nexusphere-keys/prod-next/LEDGER_API_KEY)" | jq -c '.[] | {keyId, status, previous: .rotation.previousKeyId}'
```

Step 5. Clear the previous key in the secret, and keep the new folder as the keys of `prod`:

```shell
vault kv patch secret/nexusphere-ledger/prod LEDGER_SIGNING_PREVIOUS_PRIVATE_KEY=
rm -rf ~/nexusphere-keys/prod && mv ~/nexusphere-keys/prod-next ~/nexusphere-keys/prod
```

| Situation  | What to do                                                                                                       |
|------------|------------------------------------------------------------------------------------------------------------------|
| Vault key  | `vault write -f transit/keys/nexusphere-ledger/rotate` and restart every instance; Vault keeps the old version   |
| Lost key   | Start with a new key and `LEDGER_SIGNING_UNENDORSED_ROTATION=true`; tell verifiers to pin the new public key     |
| Leaked key | Rotate, then revoke the leaked key with its compromise time; tell verifiers to pin the new key and pass `--keys` |
| Agent key  | `POST /api/v1/agents/{agentId}/key` as in [Agent API Keys](#agent-api-keys); the old key stops working at once   |

<p style="text-align: justify;">

The details of the rotation record are in [Key Rotation](../ledger/README.md#key-rotation), and those of revocation in
[Key Revocation](../ledger/README.md#key-revocation). Run witnesses before a leak happens: after it, only their
cosignatures prove which checkpoints of the leaked key are genuine.

</p>

## Clean Up

Step 1. Remove the PostgreSQL of these steps and stop the compose Vault:

```shell
docker rm -f ledger-keys-postgresql
docker compose --file ledger/compose.yaml --project-name ledger stop vault
rm -f ledger.log ledger.pid
```

Step 2. Keep `~/nexusphere-keys` only while you need it: once the keys are in Vault or in your secret manager, with
the signing key's offline copy, delete it; the listing must be empty:

```shell
rm -rf ~/nexusphere-keys && ls -d ~/nexusphere-keys 2>/dev/null
```

##

**<p align="center">[Top](#keys-and-secrets)</p>**
