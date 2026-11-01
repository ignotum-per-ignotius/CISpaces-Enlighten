"""Scenario graph for facts this extractor stored.

The graph is one workbench analysis: an argument node, a relation node, and
an object node for each fact, plus place and time nodes when the sentence
has them. FEWS reads the same JSON from factextract.phase1_scenario. The
workbench copy is written through the existing version-control API for the
seeded sample user.
"""

import hashlib
import json
import os
import urllib.error
import urllib.request

GRAPH_ID = "fact-extract-scenario"
SCENARIO_TITLE = "Extracted facts"
_MAX_FACTS = 100
_DTG = "2017-08-25 10:00:00"


def build_graph(rows):
    nodes = {}
    edges = {}
    kept = 0
    for source_uri, extract_uri, encoded in rows:
        triple = _triple(encoded)
        if triple is None:
            continue
        if kept >= _MAX_FACTS:
            break
        kept += 1
        relation = _node(
            nodes,
            "rel:" + extract_uri,
            text=triple.relation or _label(triple),
            source=source_uri,
            node_type="RA",
            uncert=_uncert(triple),
        )
        subject = _node(nodes, "arg:" + triple.subject.lower(), text=triple.subject, node_type="I")
        _edge(edges, subject, relation)
        if triple.object:
            obj = _node(nodes, "arg:" + triple.object.lower(), text=triple.object, node_type="I")
            _edge(edges, relation, obj)
        for place in triple.places:
            place_id = _node(nodes, "place:" + place.lower(), text=place, node_type="I")
            _edge(edges, relation, place_id)
        for moment in triple.times:
            time_id = _node(nodes, "time:" + moment.lower(), text=moment, node_type="I")
            _edge(edges, relation, time_id)
    payload = {
        "graphID": GRAPH_ID,
        "title": SCENARIO_TITLE,
        "nodes": list(nodes.values()),
        "edges": list(edges.values()),
    }
    payload["stored"] = json.dumps(
        {key: value for key, value in payload.items() if key != "stored"},
        ensure_ascii=False,
    )
    return payload


def publish_scenario(graph):
    """Copy the graph into the workbench. Failure leaves the PostgreSQL copy in place."""
    client = _Workbench()
    try:
        client.login()
        client.ensure_graph(graph["title"])
        for node in graph["nodes"]:
            client.post_json("/VC/rest/node/%s" % node["nodeID"], node)
        for edge in graph["edges"]:
            client.post_json("/VC/rest/edge/%s" % edge["edgeID"], edge)
    except Exception as exc:
        return type(exc).__name__
    return "ok"


class _Workbench:
    def __init__(self):
        self.base = os.environ.get("CISPACES_WORKBENCH_URL", "http://127.0.0.1:8080").rstrip("/")
        self.username = os.environ.get("CISPACES_SCENARIO_USER", "Ella")
        # Documented sample account from DATABASE/create-cispaces-tables.sql.
        self.password = os.environ.get("CISPACES_SCENARIO_PASSWORD", "password")
        self.token = ""
        self.user_id = ""

    def login(self):
        status, headers, body = self._request(
            "POST",
            "/VC/rest/login",
            {"username": self.username, "password": self.password},
            auth=False,
        )
        if status != 200:
            raise RuntimeError("login status %s" % status)
        header = headers.get("Authorization") or headers.get("authorization") or ""
        self.token = header.split(" ", 1)[-1].strip()
        if not self.token:
            raise RuntimeError("login missing token")
        user = json.loads(body.decode("utf-8"))
        self.user_id = _user_id(user)
        if not self.user_id:
            raise RuntimeError("login missing user id")

    def ensure_graph(self, title):
        status, _headers, _body = self._request("GET", "/VC/rest/analysis/%s" % GRAPH_ID)
        if status == 200:
            return
        created, _headers, _body = self._request(
            "POST",
            "/VC/rest/new",
            {
                "graphID": GRAPH_ID,
                "userID": self.user_id,
                "title": title,
                "description": "Facts extracted from text messages and dropped files",
                "timest": _DTG,
                "isshared": True,
                "parentgraphid": "",
            },
        )
        if created not in (200, 204):
            raise RuntimeError("graph status %s" % created)

    def post_json(self, path, payload):
        status, _headers, body = self._request("POST", path, payload)
        if status >= 400:
            raise RuntimeError("write status %s" % status)
        if body and b'"response":"fail"' in body.replace(b" ", b""):
            raise RuntimeError("write rejected")

    def _request(self, method, path, payload=None, auth=True):
        data = None
        headers = {}
        if payload is not None:
            data = json.dumps(payload).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if auth:
            headers["Authorization"] = "Bearer %s" % self.token
        request = urllib.request.Request(self.base + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                return response.status, response.headers, response.read()
        except urllib.error.HTTPError as exc:
            return exc.code, exc.headers, exc.read()


def _triple(encoded):
    from extractor import decode_triple
    return decode_triple(encoded)


def _label(triple):
    return " ".join(part for part in (triple.subject, triple.relation, triple.object) if part)


def _uncert(triple):
    if triple.negated:
        return "Negated"
    if triple.genuine is False:
        return "False"
    if triple.genuine is True:
        return "Confirmed"
    return "Unconfirmed"


def _node(nodes, key, text, source="", node_type="I", uncert="Unconfirmed"):
    node_id = hashlib.sha256(key.encode("utf-8")).hexdigest()[:32]
    if node_id not in nodes:
        nodes[node_id] = {
            "nodeID": node_id,
            "graphID": GRAPH_ID,
            "source": source or "fact-extraction",
            "uncert": uncert,
            "eval": "N/A",
            "text": text or key,
            "input": "Pro" if node_type == "RA" else "Info",
            "dtg": _DTG,
            "commit": "N/A",
            "type": node_type,
            "annot": "N/A",
            "islocked": "false",
        }
    return node_id


def _edge(edges, source, target):
    if not source or not target or source == target:
        return
    edge_id = hashlib.sha256(("%s>%s" % (source, target)).encode("utf-8")).hexdigest()[:32]
    edges[edge_id] = {
        "edgeID": edge_id,
        "source": source,
        "target": target,
        "graphID": GRAPH_ID,
        "islocked": "false",
    }


def _user_id(user):
    if not isinstance(user, dict):
        return ""
    for key, value in user.items():
        if key.lower().replace("_", "") == "userid" and isinstance(value, str):
            return value.strip()
    return ""
