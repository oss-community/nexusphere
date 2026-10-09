#!/bin/sh
set -eu

CONFIG=/tmp/alertmanager.yml
SECRETS_DIR=${ALERT_SECRETS_DIR:-/run/secrets}

value() {
  eval "current=\${$1:-}"
  if [ -z "$current" ] && [ -f "$SECRETS_DIR/$1" ]; then
    current=$(cat "$SECRETS_DIR/$1")
  fi
  printf '%s' "$current"
}

quote() {
  printf '"%s"' "$(printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g')"
}

EMAIL_TO=$(value ALERT_EMAIL_TO)
SMTP_HOST=$(value ALERT_SMTP_HOST)
SMTP_PORT=$(value ALERT_SMTP_PORT)
SMTP_FROM=$(value ALERT_SMTP_FROM)
SMTP_USERNAME=$(value ALERT_SMTP_USERNAME)
SMTP_PASSWORD=$(value ALERT_SMTP_PASSWORD)
SMTP_TLS=$(value ALERT_SMTP_TLS)
SLACK_WEBHOOK_URL=$(value ALERT_SLACK_WEBHOOK_URL)
SLACK_CHANNEL=$(value ALERT_SLACK_CHANNEL)
TELEGRAM_BOT_TOKEN=$(value ALERT_TELEGRAM_BOT_TOKEN)
TELEGRAM_CHAT_ID=$(value ALERT_TELEGRAM_CHAT_ID)
WEBHOOK_URL=$(value ALERT_WEBHOOK_URL)

ACTIVE=""

{
  echo "route:"
  echo "  receiver: channels"
  echo "  group_by: [ alertname ]"
  echo "  group_wait: ${ALERT_GROUP_WAIT:-30s}"
  echo "  group_interval: ${ALERT_GROUP_INTERVAL:-5m}"
  echo "  repeat_interval: ${ALERT_REPEAT_INTERVAL:-4h}"
  echo "receivers:"
  echo "  - name: channels"

  if [ -n "$EMAIL_TO" ]; then
    if [ -z "$SMTP_HOST" ]; then
      echo "ALERT_EMAIL_TO is set without ALERT_SMTP_HOST; email stays off" >&2
    else
      ACTIVE="$ACTIVE email"
      echo "    email_configs:"
      echo "      - to: $(quote "$EMAIL_TO")"
      echo "        from: $(quote "${SMTP_FROM:-$EMAIL_TO}")"
      echo "        smarthost: $(quote "$SMTP_HOST:${SMTP_PORT:-587}")"
      echo "        require_tls: ${SMTP_TLS:-true}"
      echo "        send_resolved: true"
      if [ -n "$SMTP_USERNAME" ]; then
        echo "        auth_username: $(quote "$SMTP_USERNAME")"
        echo "        auth_password: $(quote "$SMTP_PASSWORD")"
      fi
    fi
  fi

  if [ -n "$SLACK_WEBHOOK_URL" ]; then
    ACTIVE="$ACTIVE slack"
    echo "    slack_configs:"
    echo "      - api_url: $(quote "$SLACK_WEBHOOK_URL")"
    echo "        send_resolved: true"
    if [ -n "$SLACK_CHANNEL" ]; then
      echo "        channel: $(quote "$SLACK_CHANNEL")"
    fi
  fi

  if [ -n "$TELEGRAM_BOT_TOKEN" ] || [ -n "$TELEGRAM_CHAT_ID" ]; then
    if [ -z "$TELEGRAM_BOT_TOKEN" ] || [ -z "$TELEGRAM_CHAT_ID" ]; then
      echo "Telegram needs both ALERT_TELEGRAM_BOT_TOKEN and ALERT_TELEGRAM_CHAT_ID; Telegram stays off" >&2
    else
      ACTIVE="$ACTIVE telegram"
      echo "    telegram_configs:"
      echo "      - bot_token: $(quote "$TELEGRAM_BOT_TOKEN")"
      echo "        chat_id: $TELEGRAM_CHAT_ID"
      echo "        send_resolved: true"
    fi
  fi

  if [ -n "$WEBHOOK_URL" ]; then
    ACTIVE="$ACTIVE webhook"
    echo "    webhook_configs:"
    echo "      - url: $(quote "$WEBHOOK_URL")"
    echo "        send_resolved: true"
  fi
} > "$CONFIG"

echo "Alert channels:${ACTIVE:- none}" >&2

exec /bin/alertmanager --config.file="$CONFIG" --storage.path=/alertmanager "$@"
