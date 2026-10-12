#!/usr/bin/env python3
"""FB-SMP uptime alert - deterministic, no LLM.

Checks the public Java and Bedrock addresses from a third party every run and
posts to a webhook when the server goes down or comes back up. Alerts are
de-duplicated with the automation KV store, so a prolonged outage produces one
"down" message and one "recovered" message, not one per run.

Configuration (agent-server secret names, optional):
    FBSMP_ALERT_WEBHOOK   full webhook URL (Discord, Slack, or any endpoint)
    FBSMP_JAVA_ADDR       host:port for Java   (default below)
    FBSMP_BEDROCK_ADDR    host:port for Bedrock (default below)

Discord webhooks receive {"content": ...}; Slack and others receive {"text": ...}.
"""
import json
import os
import sys
import urllib.error
import urllib.request

JAVA = "cairo-paste.tun.ply.gg:25565"
BEDROCK = "fried-toilette.tun.ply.gg:37298"
UA = "FB-SMP-uptime-alert/1.0"

_KV_TOKEN = os.environ.get("AUTOMATION_KV_TOKEN", "")
_KV_BASE = os.environ.get("AUTOMATION_API_URL", "").rstrip("/")
_STATE_KEY = "fbsmp_uptime"


def get_secret(name):
    """Fetch a named secret: agent server locally, OpenHands API on cloud."""
    try:
        url = os.environ.get("AGENT_SERVER_URL", "").rstrip("/")
        key = os.environ.get("SESSION_API_KEY") or os.environ.get("OH_SESSION_API_KEYS_0", "")
        if url:
            url = f"{url}/api/settings/secrets/{name}"
        else:
            api = os.environ["OPENHANDS_CLOUD_API_URL"].rstrip("/")
            url = f"{api}/api/v1/sandboxes/{os.environ['SANDBOX_ID']}/settings/secrets/{name}"
        with urllib.request.urlopen(urllib.request.Request(
            url, headers={"X-Session-API-Key": key}
        )) as r:
            return r.read().decode().strip()
    except Exception:
        return ""


def fire_callback(status="COMPLETED", error=None):
    url = os.environ.get("AUTOMATION_CALLBACK_URL", "")
    if not url:
        return
    body = {"status": status, "run_id": os.environ.get("AUTOMATION_RUN_ID", "")}
    if error:
        body["error"] = error
    try:
        urllib.request.urlopen(urllib.request.Request(
            url, data=json.dumps(body).encode(),
            headers={"Content-Type": "application/json",
                     "Authorization": f"Bearer {os.environ.get('AUTOMATION_CALLBACK_API_KEY', '')}"}))
    except Exception as exc:
        print(f"callback error: {exc}")


def kv_get(key):
    if not (_KV_TOKEN and _KV_BASE):
        return None
    req = urllib.request.Request(f"{_KV_BASE}/v1/kv/{key}",
                                 headers={"Authorization": f"Bearer {_KV_TOKEN}"})
    try:
        with urllib.request.urlopen(req) as r:
            return json.loads(r.read())["value"]
    except urllib.error.HTTPError as exc:
        if exc.code == 404:
            return None
        raise


def kv_set(key, value):
    if not (_KV_TOKEN and _KV_BASE):
        return
    req = urllib.request.Request(
        f"{_KV_BASE}/v1/kv/{key}", data=json.dumps(value).encode(),
        headers={"Authorization": f"Bearer {_KV_TOKEN}", "Content-Type": "application/json"},
        method="PUT")
    with urllib.request.urlopen(req) as r:
        r.read()


def online(kind, addr):
    req = urllib.request.Request(
        f"https://api.mcstatus.io/v2/status/{kind}/{addr}",
        headers={"User-Agent": UA, "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return json.loads(r.read()).get("online") is True
    except Exception:
        return False


def post(url, text):
    payload = json.dumps({"content": text, "text": text}).encode()
    req = urllib.request.Request(url, data=payload,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=20) as r:
        r.read()


def main():
    webhook = get_secret("FBSMP_ALERT_WEBHOOK")
    java = get_secret("FBSMP_JAVA_ADDR") or JAVA
    bedrock = get_secret("FBSMP_BEDROCK_ADDR") or BEDROCK

    java_ok = online("java", java)
    bedrock_ok = online("bedrock", bedrock)
    now_up = java_ok and bedrock_ok

    state = kv_get(_STATE_KEY) or {}
    was_up = state.get("up")
    print(f"java={java_ok} bedrock={bedrock_ok} was_up={was_up}")

    if webhook:
        if now_up and was_up is False:
            post(webhook, f"\u2705 FB-SMP is back online. Java `{java}` | Bedrock `{bedrock}`")
        elif not now_up and was_up is not False:
            down = []
            if not java_ok:
                down.append(f"Java `{java}`")
            if not bedrock_ok:
                down.append(f"Bedrock `{bedrock}`")
            post(webhook, "\U0001F534 FB-SMP is DOWN: " + ", ".join(down)
                 + " unreachable from a third-party check.")
    else:
        print("no FBSMP_ALERT_WEBHOOK secret set - not posting")

    kv_set(_STATE_KEY, {"up": now_up, "java": java_ok, "bedrock": bedrock_ok})
    fire_callback("COMPLETED")


try:
    main()
except Exception as exc:
    print(f"ERROR: {exc}", file=sys.stderr)
    fire_callback("FAILED", str(exc))
    sys.exit(1)
