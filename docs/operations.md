# <p align="center">Operations</p>

<p align="center">Keys, backup and restore, monitoring, alerts and rate limits for the ledger.</p>

## <p align="center">Table of Content</p>

* [Keys and Secrets](#keys-and-secrets)
* [Backup and Restore](#backup-and-restore)
* [Monitoring](#monitoring)
* [Alert Channels](#alert-channels)
* [Capacity](#capacity)
* [Rate Limits](#rate-limits)
* [Production Checklist](#production-checklist)

## Keys and Secrets

<p style="text-align: justify;">

The operator key, the signing key, the database password and the Vault token, with their development values, how to
generate them and how to hand them over as environment variables, secret files or Vault secrets, are in
[Keys and Secrets](keys-and-secrets.md), step by step. Rotation and revocation of the signing key are in
[Rotate a Key](keys-and-secrets.md#rotate-a-key).

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
docker exec -i ledger-postgresql pg_restore -U ledger -d ledger_restored --no-owner < "$(ls -t ledger-*.dump | head -1)"
```

Step 2. Start the ledger on the restored database with the same signing key and operator key, from the environment
or `LEDGER_SECRETS_DIR` as in [Secret Files](keys-and-secrets.md#secret-files); the health check must answer `UP`:

```shell
LEDGER_DATABASE_PORT=5433 LEDGER_DATABASE_DB=ledger_restored java -jar ledger/server/target/server-1.0.0-SNAPSHOT-exec.jar > ledger.log 2>&1 &
until curl -sf http://localhost:8090/actuator/health; do sleep 1; done
```

<p style="text-align: justify;">

The restore keeps the triggers that make evidence append-only.

</p>

Step 3. Check the whole chain; the answer must be `"valid": true` with the `headSequence` of the backup:

```shell
curl -s http://localhost:8090/api/v1/verification -H "Authorization: Bearer {operator key}"
```

<p style="text-align: justify;">

A backup also holds the encrypted personal data and the principal keys that an [erasure](../ledger/README.md#erasure)
destroys in the live database. Keep backups and WAL archives no longer than the shortest erasure deadline of your
compliance profiles, one month with `eu`, so an erased principal is gone from every copy in time. A restore from an
older backup brings back principals erased since then: erase them again before the ledger serves anyone.

</p>

### No Lost Evidence

<p style="text-align: justify;">

A nightly dump loses what was recorded after it. For evidence, run PostgreSQL with continuous WAL archiving and
point-in-time recovery (for example pgBackRest, or a managed PostgreSQL with PITR) and keep the dump as a second line.
A restore can never hide a loss: a signed checkpoint held outside the ledger with a higher sequence than the restored
head shows that entries are missing, and any entry that differs fails verification against that checkpoint.
Witnesses give the same guarantee without anyone copying checkpoints by hand: each keeps the last log checkpoint it
cosigned and refuses a smaller or forked one, so a restored ledger that lost entries cannot get new cosignatures until
it is back on the history the witnesses saw. An independent copy of the whole log, kept current by the verifier's
`archive` command on a schedule outside the ledger's network, as in [Log
Archives](../ledger/README.md#log-archives), also shows which entries a restore lost.

</p>

## Monitoring

### Endpoints

| Health                                                                        | Metrics                |
|-------------------------------------------------------------------------------|------------------------|
| `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` | `/actuator/prometheus` |

<p style="text-align: justify;">

By default these share the application port. Set `LEDGER_MANAGEMENT_PORT` to serve them on a separate port that only the
cluster can reach, and point probes and Prometheus at it.

</p>

### Metrics

| Metric                           | Meaning                                           |
|----------------------------------|---------------------------------------------------|
| `ledger_head_sequence`           | Sequence of the last evidence entry               |
| `ledger_checkpoint_sequence`     | Sequence covered by the latest signed checkpoint  |
| `ledger_checkpoint_lag`          | Entries not yet covered by a signed checkpoint    |
| `ledger_checkpoint_age_seconds`  | Seconds since the latest signed checkpoint        |
| `ledger_evidence_recorded_total` | Entries appended, by `decision` and `outcome`     |
| `ledger_leader`                  | 1 on the instance that runs the scheduled work    |
| `ledger_anomaly_score`           | Score of each open anomaly, by `agent` and `kind` |
| `http_server_requests_seconds`   | Requests, latency and status                      |
| `jvm_*`, `hikaricp_*`            | Memory, threads and database connection pools     |

### Prometheus

<p style="text-align: justify;">

The ledger compose file runs Prometheus on http://localhost:9090. It scrapes the ledger with the configuration in
`ledger/prometheus/prometheus.yml`. In Kubernetes the ledger pod carries the `prometheus.io/scrape`,
`prometheus.io/path` and `prometheus.io/port` annotations.

</p>

### Alerts

| Alert                   | Fires when                                                                                                                    |
|-------------------------|-------------------------------------------------------------------------------------------------------------------------------|
| `LedgerDown`            | The ledger has not answered for 1 minute                                                                                      |
| `LedgerCheckpointStale` | New evidence has waited more than 10 minutes for a checkpoint                                                                 |
| `LedgerHighErrorRate`   | The ledger answers more than one 5xx every ten seconds                                                                        |
| `LedgerAgentAnomaly`    | An agent's volume, denials or failures are far above its baseline, as in [Anomaly Alerts](../ledger/README.md#anomaly-alerts) |

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

In Kubernetes, give Alertmanager the same entrypoint `ledger/alertmanager/entrypoint.sh`
with these variables, or use the Alertmanager configuration of the cluster's own monitoring stack.

</p>

## Capacity

<p style="text-align: justify;">

Every entry is appended under the lock of the chain head, so a deployment has one write ceiling however many
instances it runs. On the reference machine of the [benchmarks](../ledger/benchmarks/README.md) (4 vCPU, PostgreSQL
on the same host) that ceiling was about 330 entries a second; a decision is one entry and a call through a gateway
two. Run the benchmark on your own hardware before sizing, watch `ledger_evidence_recorded_total` against it, and
raise the per-caller rate limits so that the sum over busy agents stays below it.

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
* The operator key, the database password and the signing key generated for this ledger and handed over as files or
  Vault secrets, as in [Keys and Secrets](keys-and-secrets.md)
* An offline copy of the signing key, or a non-exportable Vault transit key
* TLS at a reverse proxy or ingress in front of the ledger; it speaks plain HTTP
* Management port reachable only inside the cluster
* WAL archiving with point-in-time recovery, plus a regular logical dump, and a restore tested once, kept no longer
  than the erasure deadline of your profiles
* Signed checkpoints copied outside the ledger, and at least one independent witness cosigning the log
* A scheduled `archive` of the log's tiles kept by someone outside the operating team
* `LEDGER_TIMESTAMP_URL` and `LEDGER_TIMESTAMP_CERTIFICATES` set to an RFC 3161 timestamp authority your auditors
  accept, as in [Checkpoint Timestamps](../ledger/README.md#checkpoint-timestamps)
* `LEDGER_REKOR_URL` and `LEDGER_REKOR_KEY` set to a Rekor v2 log outside your control, with the local signing
  provider, as in [Rekor Anchoring](../ledger/README.md#rekor-anchoring)
* Prometheus scraping the ledger, and at least one alert channel on
* At least two ledger instances behind the load balancer, each with its own `LEDGER_INSTANCE_ID`, as in
  [High Availability](../ledger/README.md#high-availability)
* Rate limits sized for the expected traffic, plus a limit at the reverse proxy for internet-facing deployments
* `LEDGER_OIDC_*` set so principals approve grants with their own sign-in
* `LEDGER_COMPLIANCE_PROFILES` and `LEDGER_COMPLIANCE_REGION` set for the jurisdictions you answer to, reviewed with
  your legal team, as in [Compliance Profiles](../ledger/README.md#compliance-profiles), and a legal hold placed for
  every principal in a dispute before any erasure or retention pass, as in
  [Retention and Legal Holds](../ledger/README.md#retention-and-legal-holds)

##

**<p align="center">[Top](#operations)</p>**
