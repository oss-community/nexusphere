# <p align="center">Operations</p>

<p align="center">Secrets, signing keys, backup and restore, monitoring, alerts and rate limits for the ledger.</p>

## <p align="center">Table of Content</p>

* [Secrets](#secrets)
* [Signing Keys](#signing-keys)
* [Backup and Restore](#backup-and-restore)
* [Monitoring](#monitoring)
* [Alert Channels](#alert-channels)
* [Rate Limits](#rate-limits)
* [Production Checklist](#production-checklist)

## Secrets

<p style="text-align: justify;">

The ledger reads secrets from environment variables or from files. Every file in `LEDGER_SECRETS_DIR`,
`/run/secrets/` by default, becomes a setting named like the file, so a file
`LEDGER_API_KEY` holding the key works exactly like the environment variable. A missing directory is ignored. This is
how a secret manager hands secrets over without putting them in the environment: Docker secrets, Kubernetes secrets
mounted as a volume, the Vault agent, the External Secrets Operator or the Secrets Store CSI driver all write files.

</p>

| Secret file                  | Holds                                 |
|------------------------------|---------------------------------------|
| `LEDGER_API_KEY`             | The operator key                      |
| `LEDGER_SIGNING_PRIVATE_KEY` | The base64 PKCS#8 Ed25519 signing key |
| `LEDGER_SIGNING_PUBLIC_KEY`  | The base64 X.509 public key           |
| `LEDGER_DATABASE_PASSWORD`   | The PostgreSQL password               |

### Docker Compose

```yaml
services:
  ledger:
    secrets:
      - LEDGER_API_KEY
      - LEDGER_SIGNING_PRIVATE_KEY
      - LEDGER_SIGNING_PUBLIC_KEY
secrets:
  LEDGER_API_KEY:
    file: ./secrets/ledger-api-key
  LEDGER_SIGNING_PRIVATE_KEY:
    file: ./secrets/ledger-signing-private-key
  LEDGER_SIGNING_PUBLIC_KEY:
    file: ./secrets/ledger-signing-public-key
```

### Kubernetes

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: ledger-secrets
  namespace: production
type: Opaque
stringData:
  LEDGER_API_KEY: "{operator key}"
  LEDGER_SIGNING_PRIVATE_KEY: "{base64 PKCS#8 Ed25519 key}"
  LEDGER_SIGNING_PUBLIC_KEY: "{base64 X.509 public key}"
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

<p style="text-align: justify;">

Mount the secrets in their own directory, not on `/run/secrets` itself, so the service account token Kubernetes
mounts there stays in place. With Vault or a cloud secret manager, let the External Secrets Operator create this
Secret, or let the CSI driver mount the same files; the application side does not change.

</p>

## Signing Keys

<p style="text-align: justify;">

The signing key is the ledger's identity: it signs checkpoints, mandates and receipts. The private key is never stored
in the database, only the public keys and their rotation history are. Generate it once, keep it in the secret manager,
and keep a second copy offline; a backup of the database without the key cannot sign anything new.

</p>

Step 1. Generate an Ed25519 key:

```shell
openssl genpkey -algorithm ed25519 -outform DER -out ledger-signing.der
```

Step 2. Write the private and the public key in the form the ledger reads:

```shell
base64 -w0 ledger-signing.der > ledger-signing-private-key
openssl pkey -inform DER -in ledger-signing.der -pubout -outform DER | base64 -w0 > ledger-signing-public-key
```

Step 3. Put both files in the secret manager as `LEDGER_SIGNING_PRIVATE_KEY` and `LEDGER_SIGNING_PUBLIC_KEY`, as in
[Secrets](#secrets), keep one copy offline, and delete `ledger-signing.der`.

Step 4. Start the ledger and check that `/api/v1/keys` shows the new key as `ACTIVE`.

| Situation        | What to do                                                                                                                          |
|------------------|-------------------------------------------------------------------------------------------------------------------------------------|
| Planned rotation | Put the new key in `LEDGER_SIGNING_PRIVATE_KEY`/`PUBLIC_KEY` and the old one in `LEDGER_SIGNING_PREVIOUS_PRIVATE_KEY`, then restart |
| Lost key         | Start with a new key and `LEDGER_SIGNING_UNENDORSED_ROTATION=true`; tell verifiers to pin the new public key                        |
| Leaked key       | Rotate, then revoke the leaked key with its compromise time; tell verifiers to pin the new key and pass `--keys`                    |
| After rotation   | Old evidence and checkpoints still verify: retired public keys stay published in `/api/v1/keys` and in packages                     |

<p style="text-align: justify;">

The details of the rotation record are in [Key Rotation](../ledger/README.md#key-rotation), and those of revocation in
[Key Revocation](../ledger/README.md#key-revocation). Run witnesses before a leak happens: after it, only their
cosignatures prove which checkpoints of the leaked key are genuine.

</p>

### Keys in Vault

<p style="text-align: justify;">

In production the key is better kept in HashiCorp Vault, where it cannot be copied out. The ledger signs through the
transit engine, as described in [Keys in Vault](../ledger/README.md#keys-in-vault).

</p>

Step 1. Create the transit key; `exportable` stays false, so nobody can read the private key:

```shell
vault secrets enable transit
vault write -f transit/keys/nexusphere-ledger type=ed25519
```

Step 2. Give the ledger a policy with only what it needs, and a token or an auth role bound to it:

```shell
vault policy write nexusphere-ledger - <<'POLICY'
path "transit/keys/nexusphere-ledger" { capabilities = ["read"] }
path "transit/sign/nexusphere-ledger" { capabilities = ["update"] }
POLICY
```

Step 3. Start the ledger with `LEDGER_SIGNING_PROVIDER=vault`, `LEDGER_SIGNING_VAULT_ADDRESS` and the token in
`LEDGER_SIGNING_VAULT_TOKEN_FILE`, written by the Vault agent; when the ledger signed with a local key before, add that
key as `LEDGER_SIGNING_PREVIOUS_PRIVATE_KEY` for this one start.

Step 4. Check that `/api/v1/keys` shows the Vault key as `ACTIVE`.

<p style="text-align: justify;">

To rotate, rotate the transit key in Vault and restart every instance; keep older versions in Vault, since the ledger
needs the previous version once to endorse the new one.

</p>

## Backup and Restore

### What to Back Up

| Data                                                                | Where                             | How                                                    |
|---------------------------------------------------------------------|-----------------------------------|--------------------------------------------------------|
| Ledger evidence, checkpoints, grants, mandates, agents, key history | PostgreSQL schema `ledger`        | Database backup                                        |
| Ledger signing private key                                          | Secret manager                    | The secret manager's own backup, plus one offline copy |
| Signed checkpoints                                                  | Outside the ledger, with auditors | Copy `/api/v1/checkpoints/latest` regularly            |
| Log checkpoints                                                     | With independent witnesses        | `LEDGER_LOG_WITNESSES_{NAME}_URL` and `_KEY`           |

### Logical Backup

Step 1. Dump the `ledger` schema:

```shell
docker exec ledger-postgresql pg_dump -U ledger -d ledger --schema=ledger --format=custom > ledger-$(date +%F).dump
```

### Restore

Step 1. Restore the dump into a new database:

```shell
docker exec ledger-postgresql createdb -U ledger ledger_restored
docker exec -i ledger-postgresql pg_restore -U ledger -d ledger_restored --no-owner < ledger-2026-10-09.dump
```

Step 2. Start the ledger on the restored database (`LEDGER_DATABASE_DB=ledger_restored`) with the same signing key.
The restore keeps the triggers that make evidence append-only.

Step 3. Check the whole chain:

```shell
curl -s http://localhost:8090/api/v1/verification -H "Authorization: Bearer {operator key}"
```

<p style="text-align: justify;">

The answer must be `"valid": true` with the `headSequence` of the backup. This was checked end to end: evidence and a
signed checkpoint were recorded, dumped, restored into a new PostgreSQL, and the restored ledger verified the whole
chain and checkpoint and still refused changes to evidence.

</p>

### No Lost Evidence

<p style="text-align: justify;">

A nightly dump loses what was recorded after it. For evidence, run PostgreSQL with continuous WAL archiving and
point-in-time recovery (for example pgBackRest, or a managed PostgreSQL with PITR) and keep the dump as a second line.
A restore can never hide a loss: a signed checkpoint held outside the ledger with a higher sequence than the restored
head shows that entries are missing, and any entry that differs fails verification against that checkpoint.
Witnesses give the same guarantee without anyone copying checkpoints by hand: each keeps the last log checkpoint it
cosigned and refuses a smaller or forked one, so a restored ledger that lost entries cannot get new cosignatures until
it is back on the history the witnesses saw.

</p>

## Monitoring

### Endpoints

| Health                                                                        | Metrics                |
|-------------------------------------------------------------------------------|------------------------|
| `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` | `/actuator/prometheus` |

<p style="text-align: justify;">

By default these share the application port. Set `LEDGER_MANAGEMENT_PORT` to serve them on a separate port that only the cluster can reach, and point probes and Prometheus at it.

</p>

### Metrics

| Metric                           | Meaning                                          |
|----------------------------------|--------------------------------------------------|
| `ledger_head_sequence`           | Sequence of the last evidence entry              |
| `ledger_checkpoint_sequence`     | Sequence covered by the latest signed checkpoint |
| `ledger_checkpoint_lag`          | Entries not yet covered by a signed checkpoint   |
| `ledger_checkpoint_age_seconds`  | Seconds since the latest signed checkpoint       |
| `ledger_evidence_recorded_total` | Entries appended, by `decision` and `outcome`    |
| `http_server_requests_seconds`   | Requests, latency and status                     |
| `jvm_*`, `hikaricp_*`            | Memory, threads and database connection pools    |

### Prometheus

<p style="text-align: justify;">

The ledger compose file runs Prometheus on http://localhost:9090. It scrapes the ledger with the configuration in
`ledger/prometheus/prometheus.yml`. In Kubernetes the ledger pod carries the `prometheus.io/scrape`, `prometheus.io/path` and `prometheus.io/port` annotations.

</p>

### Alerts

| Alert                   | Fires when                                                    |
|-------------------------|---------------------------------------------------------------|
| `LedgerDown`            | The ledger has not answered for 1 minute                      |
| `LedgerCheckpointStale` | New evidence has waited more than 10 minutes for a checkpoint |
| `LedgerHighErrorRate`   | The ledger answers more than one 5xx every ten seconds        |

<p style="text-align: justify;">

The rules are in `ledger/prometheus/alerts.yml`. Prometheus evaluates them and sends them to Alertmanager, which
delivers them on the channels below. Run a full chain verification (`/api/v1/verification`) on a schedule as well; it
reads the whole chain, so run it at a quiet hour rather than on every scrape.

</p>

## Alert Channels

<p style="text-align: justify;">

The ledger compose file runs Alertmanager on http://localhost:9093. No channel is on by default: alerts are visible
there and in Prometheus but go nowhere until a channel's settings are given. Any number of channels can be on at once;
each alert goes to all of them, again when it is resolved, and every 4 hours while it keeps firing.

</p>

| Channel  | Turned on by                                            | Optional                                                                                                          |
|----------|---------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------|
| Email    | `ALERT_EMAIL_TO` and `ALERT_SMTP_HOST`                  | `ALERT_SMTP_PORT` (587), `ALERT_SMTP_FROM`, `ALERT_SMTP_USERNAME`, `ALERT_SMTP_PASSWORD`, `ALERT_SMTP_TLS` (true) |
| Slack    | `ALERT_SLACK_WEBHOOK_URL`                               | `ALERT_SLACK_CHANNEL`                                                                                             |
| Telegram | `ALERT_TELEGRAM_BOT_TOKEN` and `ALERT_TELEGRAM_CHAT_ID` |                                                                                                                   |
| Webhook  | `ALERT_WEBHOOK_URL`                                     |                                                                                                                   |

```shell
ALERT_EMAIL_TO=ops@example.com ALERT_SMTP_HOST=smtp.example.com ALERT_SMTP_USERNAME=alerts ALERT_SMTP_PASSWORD=secret \
  docker compose --file ledger/compose.yaml --project-name ledger up -d alertmanager
docker logs alertmanager 2>&1 | head -1
```

<p style="text-align: justify;">

The first log line names the channels that are on, such as `Alert channels: email`. The settings can also live in a
`ledger/.env` file, and each one can come from a file of the same name in `/run/secrets`, so passwords and tokens stay
out of the environment. A Slack webhook URL comes from an incoming webhook of a Slack app. A Telegram bot token comes
from @BotFather, and the chat id is the user's or group's id after they send the bot a message. The webhook channel
posts the Alertmanager JSON to any URL, such as a ticketing system or an on-call service. `ALERT_GROUP_WAIT` (30s),
`ALERT_GROUP_INTERVAL` (5m) and `ALERT_REPEAT_INTERVAL` (4h) tune how often messages are sent.

</p>

<p style="text-align: justify;">

Delivery was checked end to end: with email and webhook on, Prometheus fired an alert, and the message reached a
test mail server and the webhook. In Kubernetes, give Alertmanager the same entrypoint `ledger/alertmanager/entrypoint.sh`
with these variables, or use the Alertmanager configuration of the cluster's own monitoring stack.

</p>

## Rate Limits

<p style="text-align: justify;">

The ledger limits requests per caller. A caller is its token or API key, or its address when it sends none.
Each caller has a bucket that holds the burst and refills at the per-minute rate; a request over it is answered 429
`RATE_LIMIT_EXCEEDED` with `Retry-After` in seconds. Health and metrics under `/actuator/` are never limited. The
limit is per instance and kept in memory, so with several replicas a caller can reach the limit once on each.

</p>

| Requests a minute                     | Burst                           |
|---------------------------------------|---------------------------------|
| `LEDGER_RATE_LIMIT_PER_MINUTE` (1200) | `LEDGER_RATE_LIMIT_BURST` (200) |

<p style="text-align: justify;">

`0` turns the limit off. Behind a reverse proxy the address comes from `X-Forwarded-For`, so anonymous callers are
told apart; let only the proxy set that header. Keep a limit at the proxy as well when the ledger is exposed
to the internet, since it can also stop floods before they reach Java.

</p>

## Production Checklist

* Profiles without `dev`: the ledger refuses to start with the published development secrets
* The operator key, the database password and the signing key from the secret manager, as files
* A signing key generated for this ledger, with an offline copy, or a non-exportable Vault transit key
* TLS at a reverse proxy or ingress in front of the ledger; it speaks plain HTTP
* Management port reachable only inside the cluster
* WAL archiving with point-in-time recovery, plus a regular logical dump, and a restore tested once
* Signed checkpoints copied outside the ledger, and at least one independent witness cosigning the log
* Prometheus scraping the ledger, and at least one alert channel on
* Rate limits sized for the expected traffic, plus a limit at the reverse proxy for internet-facing deployments
* `LEDGER_OIDC_*` set so principals approve grants with their own sign-in
* `LEDGER_COMPLIANCE_PROFILES` and `LEDGER_COMPLIANCE_REGION` set for the jurisdictions you answer to, reviewed with
  your legal team, as in [Compliance Profiles](../ledger/README.md#compliance-profiles)

##

**<p align="center">[Top](#operations)</p>**
