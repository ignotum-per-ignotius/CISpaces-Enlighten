"""Drop-folder source adapter.

A text file placed in the inbox is published to the FEWS fanout as JSON.
Nothing here calls Twitter. The consumer turns the message into facts the
same way it treats a message FEWS or another adapter published.
"""

import json
import os
import time
from datetime import datetime, timezone

INBOX_DIR = os.environ.get("FACT_EXTRACTION_INBOX", "/var/fact-inbox")
SEEN_PATH = "/tmp/fact-extraction.inbox"
POLL_SECONDS = 2
_SUFFIXES = (".txt", ".json")


def message_from_file(name, raw):
    """Return JSON bytes to publish, or None when the file is empty."""
    text = raw.decode("utf-8", errors="replace").strip()
    if not text:
        return None
    source = "cispaces:file:%s" % _safe_name(name)
    if name.lower().endswith(".json"):
        try:
            payload = json.loads(text)
        except json.JSONDecodeError:
            payload = None
        if isinstance(payload, (dict, list)):
            return json.dumps(payload).encode("utf-8")
    body = {
        "text": text[:20000],
        "source_uri": source,
        "created_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }
    return json.dumps(body).encode("utf-8")


def poll_forever(cfg, publish):
    os.makedirs(INBOX_DIR, exist_ok=True)
    while True:
        try:
            poll_once(cfg, publish)
        except Exception as exc:
            print("# fact-extraction inbox failed error=%s" % type(exc).__name__, flush=True)
        time.sleep(POLL_SECONDS)


def poll_once(cfg, publish):
    seen = _load_seen()
    try:
        names = sorted(os.listdir(INBOX_DIR))
    except FileNotFoundError:
        return
    for name in names:
        if name.startswith(".") or name == "done":
            continue
        if not name.lower().endswith(_SUFFIXES):
            continue
        path = os.path.join(INBOX_DIR, name)
        if not os.path.isfile(path):
            continue
        stamp = _stamp(path)
        if seen.get(name) == stamp:
            continue
        with open(path, "rb") as handle:
            raw = handle.read(20001)
        body = message_from_file(name, raw)
        if body is None:
            _remember(seen, name, stamp)
            continue
        publish(cfg, body)
        _remember(seen, name, stamp)
        _archive(path, name)


def publish_message(cfg, body):
    import pika

    credentials = pika.PlainCredentials(
        cfg.get("rabbitmq", "rmq_username"),
        cfg.get("rabbitmq", "rmq_password"),
    )
    params = pika.ConnectionParameters(
        host=cfg.get("rabbitmq", "rmq_host"),
        port=cfg.getint("rabbitmq", "rmq_port"),
        credentials=credentials,
        connection_attempts=3,
        retry_delay=1,
        heartbeat=30,
    )
    connection = pika.BlockingConnection(params)
    try:
        channel = connection.channel()
        channel.basic_publish(
            exchange=cfg.get("rabbitmq", "rmq_exchange_name"),
            routing_key=cfg.get("rabbitmq", "rmq_routing"),
            body=body,
            properties=pika.BasicProperties(content_type="application/json"),
        )
    finally:
        if connection.is_open:
            connection.close()


def _safe_name(name):
    base = os.path.basename(name).replace(" ", "_")
    cleaned = "".join(char for char in base if char.isalnum() or char in "._-")
    return cleaned[:180] or "dropped.txt"


def _stamp(path):
    stat = os.stat(path)
    return "%s:%s" % (stat.st_mtime_ns, stat.st_size)


def _load_seen():
    seen = {}
    try:
        with open(SEEN_PATH, encoding="utf-8") as handle:
            for line in handle:
                name, _sep, stamp = line.rstrip("\n").partition("\t")
                if name and stamp:
                    seen[name] = stamp
    except FileNotFoundError:
        return {}
    return seen


def _remember(seen, name, stamp):
    seen[name] = stamp
    tmp = SEEN_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as handle:
        for key in sorted(seen):
            handle.write("%s\t%s\n" % (key, seen[key]))
    os.replace(tmp, SEEN_PATH)


def _archive(path, name):
    done = os.path.join(INBOX_DIR, "done")
    try:
        os.makedirs(done, exist_ok=True)
        os.replace(path, os.path.join(done, name))
    except OSError:
        return
