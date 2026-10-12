# FB-SMP uptime alert (optional)

`main.py` checks the public Java and Bedrock addresses from a third party and
posts to a webhook when the server goes down or recovers. It is deterministic -
no LLM, no SDK - so a run is cheap and cannot hallucinate.

There are two ways to use it. Pick one.

## Where the webhook URL goes

Create a Discord channel webhook (Channel settings -> Integrations -> Webhooks)
and copy its URL. Then store it as a secret - never in the repo:

- This container / agent-server:
  ```bash
  curl -X PUT "$AGENT_SERVER_URL/api/settings/secrets" \
    -H "X-Session-API-Key: $SESSION_API_KEY" -H 'Content-Type: application/json' \
    -d '{"name":"DISCORD_WEBHOOK_URL","value":"https://discord.com/api/webhooks/..."}'
  ```
- VPS: `printf '%s' '<url>' | sudo tee /opt/fb-smp/.fbsmp-alert-webhook >/dev/null`
  then `sudo chmod 600 /opt/fb-smp/.fbsmp-alert-webhook`.

Payloads set `allowed_mentions: {"parse": []}`, so an alert can never ping
`@everyone` or roles.

## 1. In-process (recommended)

The external health watchdog (`fbsmp-health.sh`) already checks the same
addresses every 60s. Give it the webhook and it sends de-duplicated down/up
alerts with no extra moving parts:

```bash
# on the host that runs the watchdog
printf '%s' 'https://discord.com/api/webhooks/...' > /opt/fb-smp/.fbsmp-alert-webhook
chmod 600 /opt/fb-smp/.fbsmp-alert-webhook
sudo systemctl restart fb-smp-health
```

It reads the URL from `FBSMP_ALERT_WEBHOOK` or the file above. Discord reads
`content`; Slack/generic collectors read `text` - both keys are sent.

## 2. OpenHands scheduled automation

Deploy `main.py` as a cron automation. It stores its state in the automation KV
store, so a prolonged outage yields one alert, not one per run.

```bash
tar -czf uptime-alert.tar.gz -C deploy/uptime-alert .
TARBALL=$(curl -s -X POST "https://app.all-hands.dev/api/automation/v1/uploads?name=uptime-alert" \
  -H "Authorization: Bearer ${OPENHANDS_API_KEY}" \
  -H "Content-Type: application/gzip" --data-binary @uptime-alert.tar.gz | jq -r .tarball_path)

curl -X POST "https://app.all-hands.dev/api/automation/v1" \
  -H "Authorization: Bearer ${OPENHANDS_API_KEY}" -H "Content-Type: application/json" \
  -d "{\"name\":\"FB-SMP uptime alert\",\"trigger\":{\"type\":\"cron\",\"schedule\":\"*/5 * * * *\",\"timezone\":\"UTC\"},\"tarball_path\":\"$TARBALL\",\"entrypoint\":\"python3 main.py\"}"
```

Set the `FBSMP_ALERT_WEBHOOK` secret (agent-server settings) before enabling it.
Optional overrides: `FBSMP_JAVA_ADDR`, `FBSMP_BEDROCK_ADDR`.

Note: a scheduled automation runs in its own sandbox and cannot restart the
server. It only observes and alerts - the watchdog does the restarting.
