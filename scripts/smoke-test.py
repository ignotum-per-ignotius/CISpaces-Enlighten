#!/usr/bin/env python3
"""End-to-end smoke test for a running CISpaces stack.

Drives the same HTTP calls the web UI makes, against a stack started with
`docker compose up`. Every check runs even when an earlier one fails, so one
run lists everything that is broken. Exit status is 1 when any check fails.

Usage: scripts/smoke-test.py [base_url]     (default http://127.0.0.1:8080)

Standard library only.
"""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080").rstrip("/")
REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
results = []


def call(method, path, body=None, token=None, headers=None, raw=None, timeout=120):
    """Return (status, headers, text). Status 0 means the request never completed."""
    data = raw
    hdrs = dict(headers or {})
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        hdrs.setdefault("Content-Type", "application/json")
    if token:
        hdrs["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BASE + path, data=data, method=method, headers=hdrs)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.headers, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as err:
        return err.code, err.headers, err.read().decode("utf-8", "replace")
    except Exception as err:  # connection refused, timeout, reset
        return 0, {}, "%s: %s" % (type(err).__name__, err)


def check(name, ok, detail=""):
    results.append((name, bool(ok)))
    line = "[%s] %s" % ("PASS" if ok else "FAIL", name)
    if detail and not ok:
        line += "\n       " + str(detail).replace("\n", "\n       ")[:1500]
    print(line, flush=True)
    return bool(ok)


def as_json(text):
    try:
        return json.loads(text)
    except Exception:
        return None


def login(username, password="password"):
    status, headers, text = call("POST", "/VC/rest/login",
                                 {"username": username, "password": password})
    token = None
    auth = headers.get("Authorization") if headers else None
    if auth and auth.startswith("Bearer "):
        token = auth[len("Bearer "):].strip()
    return status, token, as_json(text), text


def user_id_of(user):
    if not isinstance(user, dict):
        return None
    for key in ("user_id", "userID", "USER_ID", "userid"):
        if user.get(key):
            return str(user[key])
    return None


def now():
    return time.strftime("%Y-%m-%d %H:%M:%S")


# ---------------------------------------------------------------- static UI
status, _, text = call("GET", "/")
check("UI: GET / serves the workbench page", status == 200 and "<html" in text.lower(),
      "status %s" % status)
status, _, _ = call("GET", "/login.html")
check("UI: GET /login.html", status == 200, "status %s" % status)

# ---------------------------------------------------------------- hello pages
# FEWS answers its hello route with 418 on purpose (FEWSServlet.hello).
for name, path, expected in [("VC", "/VC/rest/getInfo", 200), ("ers", "/ers/rest/WriteRules", 200),
                             ("PROVSIMP", "/PROVSIMP/rest/ProcProv", 200),
                             ("FEWS", "/fewsservlet/hello", 418)]:
    status, _, text = call("GET", path)
    check("%s: service answers its hello route" % name, status == expected,
          "GET %s -> %s %s" % (path, status, text[:300]))

# ---------------------------------------------------------------- auth
status, _, _, text = login("Ella", "not-the-password")
check("VC: wrong password is refused", status in (401, 403), "status %s %s" % (status, text[:200]))

status, ella_token, ella, text = login("Ella")
ella_id = user_id_of(ella)
check("VC: Ella logs in and receives a bearer token",
      status == 200 and ella_token and ella_id,
      "status %s, body %s" % (status, text[:300]))
check("VC: login response does not include the password hash",
      isinstance(ella, dict) and not any("pass" in k.lower() for k in ella),
      text[:300])

status, joe_token, joe, text = login("Joe")
joe_id = user_id_of(joe)
check("VC: Joe logs in", status == 200 and joe_token and joe_id, "status %s %s" % (status, text[:200]))

status, _, _ = call("GET", "/VC/rest/analyses/user/%s/meta" % (ella_id or "x"))
check("VC: listing analyses without a token is refused", status == 401, "status %s" % status)

if not (ella_token and ella_id):
    print("\nCannot continue without a login.", flush=True)
    sys.exit(1)

status, _, text = call("GET", "/VC/rest/analyses/user/%s/meta" % ella_id, token=ella_token)
check("VC: Ella lists her analyses", status == 200 and isinstance(as_json(text), list),
      "status %s %s" % (status, text[:300]))

# ---------------------------------------------------------------- build a graph
with open(os.path.join(REPO, "example_cis_files", "scheme1.cis")) as handle:
    example = json.load(handle)

graph_id = str(uuid.uuid4())
node_ids = {n["nodeID"]: str(uuid.uuid4()) for n in example["nodes"]}
nodes = []
for node in example["nodes"]:
    node = dict(node)
    node["nodeID"] = node_ids[node["nodeID"]]
    node["graphID"] = graph_id
    nodes.append(node)
edges = []
for edge in example["edges"]:
    edge = dict(edge)
    edge["edgeID"] = str(uuid.uuid4())
    edge["source"] = node_ids.get(edge["source"], edge["source"])
    edge["target"] = node_ids.get(edge["target"], edge["target"])
    edge["graphID"] = graph_id
    edges.append(edge)

status, _, text = call("POST", "/VC/rest/new", {
    "graphID": graph_id, "userID": ella_id, "timest": now(),
    "title": "Smoke test analysis", "description": "Created by scripts/smoke-test.py",
    "isshared": False, "parentgraphid": None}, token=ella_token)
check("VC: create a new analysis", 200 <= status < 300, "status %s %s" % (status, text[:300]))

failed = []
for node in nodes:
    status, _, text = call("POST", "/VC/rest/node/" + node["nodeID"], node, token=ella_token)
    if not 200 <= status < 300:
        failed.append("%s %s" % (status, text[:200]))
check("VC: add %d nodes" % len(nodes), not failed, "\n".join(failed[:3]))

failed = []
for edge in edges:
    status, _, text = call("POST", "/VC/rest/edge/" + edge["edgeID"], edge, token=ella_token)
    if not 200 <= status < 300:
        failed.append("%s %s" % (status, text[:200]))
check("VC: add %d edges" % len(edges), not failed, "\n".join(failed[:3]))

status, _, text = call("GET", "/VC/rest/analysis/" + graph_id, token=ella_token)
stored = as_json(text) or {}
check("VC: read the analysis back with every node and edge",
      status == 200 and len(stored.get("nodes", [])) == len(nodes)
      and len(stored.get("edges", [])) == len(edges),
      "status %s, nodes %s/%d, edges %s/%d, body %s" % (
          status, len(stored.get("nodes", [])), len(nodes),
          len(stored.get("edges", [])), len(edges), text[:300]))

status, _, text = call("GET", "/VC/rest/analysis/%s/meta" % graph_id, token=ella_token)
check("VC: read analysis metadata", status == 200, "status %s %s" % (status, text[:300]))

changed = dict(nodes[0])
changed["text"] = "Edited by the smoke test"
status, _, text = call("PUT", "/VC/rest/node/" + changed["nodeID"], changed, token=ella_token)
check("VC: update a node", 200 <= status < 300, "status %s %s" % (status, text[:300]))

if joe_token:
    status, _, _ = call("GET", "/VC/rest/analysis/" + graph_id, token=joe_token)
    check("VC: Joe cannot read Ella's unshared analysis", status == 403, "status %s" % status)
    status, _, _ = call("DELETE", "/VC/rest/analysis/" + graph_id, token=joe_token)
    check("VC: Joe cannot delete Ella's analysis", status == 403, "status %s" % status)

status, _, text = call("POST", "/VC/rest/save",
                       {"graphID": graph_id, "userID": ella_id, "title": "smoke snapshot"},
                       token=ella_token)
check("VC: save a history snapshot", status == 200, "status %s %s" % (status, text[:300]))

status, _, text = call("GET", "/VC/rest/history", token=ella_token,
                       headers={"Cookie": "graph_id=" + graph_id})
history = as_json(text)
check("VC: history lists the snapshot", status == 200 and history,
      "status %s %s" % (status, text[:300]))

# ---------------------------------------------------------------- reasoning
status, _, text = call("POST", "/ers/rest/WriteRules", {"action": "eval", "graph": {
    "uncert": "OFF", "nodes": nodes, "edges": edges}}, token=ella_token)
evaluation = as_json(text)
check("ers: evaluate the argument graph",
      status == 200 and isinstance(evaluation, dict) and not evaluation.get("fail"),
      "status %s %s" % (status, text[:600]))

status, _, text = call("POST", "/ers/rest/WriteRules", {"action": "eval", "graph": {
    "uncert": "OFF", "nodes": nodes, "edges": edges}})
check("ers: evaluation without a token is refused", status == 401, "status %s" % status)

status, _, text = call("POST", "/nlg/rest/NLG", {
    "action": "nlg", "graph": {"nodes": nodes, "edges": edges}, "eval": evaluation},
    token=ella_token)
report = as_json(text)
check("nlg: generate a report from the evaluation",
      status == 200 and isinstance(report, dict) and report.get("text"),
      "status %s %s" % (status, text[:600]))

# ---------------------------------------------------------------- provenance
status, _, text = call("POST", "/PROVSIMP/rest/ProcProv",
                       {"action": "save", "user": ella_id, "nodes": nodes}, token=ella_token)
check("PROVSIMP: save node provenance", status == 200 and "fail" not in text.lower(),
      "status %s %s" % (status, text[:600]))

status, _, _ = call("GET", "/provgraphs/Prov?action=printprov")
check("provgraphs: provenance plot without a token is refused", status == 401, "status %s" % status)

# ---------------------------------------------------------------- info (unused service)
status, _, text = call("POST", "/info/rest/GetInfo", {"action": "getinfo"})
check("info: POST without a token is refused", status == 401,
      "status %s (the info war is deployed and answers unauthenticated callers) %s" % (status, text[:200]))
status, _, _ = call("GET", "/info/InfoServQueryHttp")
check("info: annotated servlet without a token is refused", status == 401, "status %s" % status)

# ---------------------------------------------------------------- AI endpoint
status, _, text = call("POST", "/VC/ai/complete", {"prompt": "Say hello."})
check("ai: completion without a token is refused", status == 401, "status %s" % status)
status, _, text = call("POST", "/VC/ai/complete", {"prompt": "Say hello."}, token=ella_token)
check("ai: completion with no key configured reports 503, not a crash",
      status == 503 and as_json(text) and "error" in as_json(text),
      "status %s %s" % (status, text[:300]))

# ---------------------------------------------------------------- FEWS
status, _, text = call("GET", "/fewsservlet/topics", token=ella_token)
check("FEWS: list topics (PostgreSQL)", status == 200 and isinstance(as_json(text), list),
      "status %s %s" % (status, text[:300]))

status, _, text = call("GET", "/fewsservlet/vocab", token=ella_token)
vocab = as_json(text)
check("FEWS: list vocabulary (PostgreSQL)", status == 200 and isinstance(vocab, list),
      "status %s %s" % (status, text[:300]))

topic = "smoke" + uuid.uuid4().hex[:8]
status, _, text = call("POST", "/fewsservlet/vocab/%s/schema/smoke" % topic, token=ella_token, raw=b"")
check("FEWS: create a vocabulary topic", 200 <= status < 300, "status %s %s" % (status, text[:300]))
status, _, text = call("POST", "/fewsservlet/vocab/%s/keywords/convoy" % topic, token=ella_token, raw=b"")
check("FEWS: add a keyword (publishes to RabbitMQ)", 200 <= status < 300,
      "status %s %s" % (status, text[:300]))
status, _, text = call("GET", "/fewsservlet/vocab/" + topic, token=ella_token)
check("FEWS: read the new topic back", status == 200 and "convoy" in text,
      "status %s %s" % (status, text[:300]))

if isinstance(vocab, list) and vocab:
    names = [{"name": v.get("topic"), "negated": -1, "genuine": -1} for v in vocab[:1]]
    status, _, text = call("POST", "/fewsservlet/tweets", names, token=ella_token)
    check("FEWS: query tweets for a topic", status == 200 and isinstance(as_json(text), list),
          "status %s %s" % (status, text[:300]))

# ---------------------------------------------------------------- fact extraction
sentence = ("On Tuesday a convoy of trucks crossed the northern border near Kabul. "
            "The police arrested three smugglers in Kabul on Tuesday.")
dropped = False
if os.environ.get("SMOKE_COMPOSE") == "1":
    name = "smoke-%s.txt" % uuid.uuid4().hex[:8]
    proc = subprocess.run(
        ["docker", "compose", "exec", "-T", "fact-extraction", "sh", "-c",
         "cat > /var/fact-inbox/.%s.tmp && mv /var/fact-inbox/.%s.tmp /var/fact-inbox/%s" % (name, name, name)],
        input=sentence.encode("utf-8"), cwd=REPO, capture_output=True)
    dropped = check("fact-extraction: drop a text file in the inbox", proc.returncode == 0,
                    proc.stderr.decode("utf-8", "replace")[:500])

if dropped:
    situations = []
    deadline = time.time() + 180
    while time.time() < deadline:
        status, _, text = call("GET", "/fewsservlet/situations", token=ella_token)
        situations = as_json(text) or []
        if status == 200 and situations:
            break
        time.sleep(5)
    check("fact-extraction: inbox text becomes situation facts",
          bool(situations), "status %s %s" % (status, text[:400]))
    status, _, text = call("GET", "/fewsservlet/scenario", token=ella_token)
    scenario = as_json(text) or {}
    check("fact-extraction: scenario graph is available",
          status == 200 and scenario.get("nodes"), "status %s %s" % (status, text[:400]))
    if situations:
        print("       sample situation: %s" % json.dumps(situations[0])[:300], flush=True)
else:
    status, _, text = call("GET", "/fewsservlet/situations", token=ella_token)
    check("FEWS: situations route answers", status == 200, "status %s %s" % (status, text[:300]))

status, _, text = call("DELETE", "/fewsservlet/vocab/" + topic, token=ella_token)
check("FEWS: delete the vocabulary topic", 200 <= status < 300, "status %s %s" % (status, text[:300]))

# ---------------------------------------------------------------- clean up
status, _, text = call("DELETE", "/VC/rest/analysis/" + graph_id, token=ella_token)
check("VC: delete the analysis", status == 200, "status %s %s" % (status, text[:300]))
status, _, _ = call("GET", "/VC/rest/analysis/" + graph_id, token=ella_token)
check("VC: deleted analysis is gone", status == 404, "status %s" % status)

passed = sum(1 for _, ok in results if ok)
print("\n%d of %d checks passed" % (passed, len(results)), flush=True)
for name, ok in results:
    if not ok:
        print("  failed: " + name, flush=True)
sys.exit(0 if passed == len(results) else 1)
