# CISpaces Web Service Calls

Paths below are the ones the servlets on this tree actually register. The context path is the war `finalName`. Commented-out methods are not routes.

## VC

Source: `VC/src/main/java/vcservlet/VCServlet.java`. Jersey package `vcservlet`, mapped to `/rest/*` in `VC/WebContent/WEB-INF/web.xml`.

`POST VC/rest/getAnalysis` is commented out and is not served. There is no `browseAnalysis` route. `GET VC/rest/analysis/{analysisID}/export` is commented out and is not served.

- `GET VC/rest/getInfo`

  Returns the text `Hello Jersey`.

- `GET VC/rest/history`

  Requires a JWT. Graph id is the `graph_id` cookie. Returns the saved history for that analysis as JSON.

- `GET VC/rest/analyses/user/{userID}/meta`

  Requires a JWT. The caller must be `{userID}`. Returns a JSON array of graph metadata for that user.

- `GET VC/rest/analysis/{graphID}/meta`

  Requires a JWT. Returns JSON metadata for one graph.

- `GET VC/rest/analysis/{graphID}`

  Requires a JWT. Returns the graph JSON (metadata, nodes, and edges).

- `DELETE VC/rest/analysis/{graphID}`

  Requires a JWT. The caller must own the graph. Deletes that analysis.

- `POST VC/rest/login`

  JSON body `{"username":"...","password":"..."}`. On success, plain-text user info and an `Authorization: Bearer` token.

- `POST VC/rest/user/add`

  Requires a JWT. JSON body `{"username":"...","password":"...","affiliation":"..."}`.

- `POST VC/rest/new`

  Requires a JWT. JSON body with the new graph, including `userID`. The caller must be that user.

- `POST VC/rest/save`

  Requires a JWT. JSON body with `graphID` (caller must own it), the user id, and the title. Saves a history version.

- `POST VC/rest/edge/{edgeid}`

  Requires a JWT. JSON body for a new edge.

- `PUT VC/rest/edge/{edgeid}`

  Requires a JWT. JSON body for an edge update.

- `DELETE VC/rest/edge/{edgeid}`

  Requires a JWT. Deletes the edge. Returns a boolean.

- `POST VC/rest/node/{nodeid}`

  Requires a JWT. JSON body for a new node.

- `PUT VC/rest/node/{nodeid}`

  Requires a JWT. JSON body for a node update.

- `DELETE VC/rest/node/{nodeid}`

  Requires a JWT. Deletes the node. Returns a boolean.

- `POST VC/rest/updateAnalysis`

  Requires a JWT. JSON body of a history snapshot. `graphID` must be a graph the caller owns, and the embedded node and edge records must use that same graph id.

## ERS

Source: `ers/src/ers/ERSServlet.java`. Jersey package `ers`, mapped to `/rest/*`.

There is no `action: nlg`. Anything other than `eval` is rejected with "action not recognised". Report text is the separate NLG service below.

- `GET ers/rest/WriteRules`

  HTML hello page.

- `POST ers/rest/WriteRules`

  Requires a JWT. JSON body must include `action` and, for `eval`, `graph` (nodes and edges). `action` must be `eval`. Returns the evaluation JSON, or a JSON object with `fail` and `cause`.

## NLG

Source: `nlg/src/main/java/org/cispaces/nlg/NLG.java`. Jersey package `org.cispaces.nlg`, mapped to `/rest/*`.

- `POST nlg/rest/NLG`

  Requires a JWT. Body is the graph text passed to `ExtensionsBulletPoints`. Response is `text/plain`.

## FEWS

Source: `FEWS/src/main/java/fewsservlet/FEWSServlet.java`. Jersey package `fewsservlet`, mapped to `/*` (there is no `/rest` prefix). War name is `fewsservlet`.

`negated` and `genuine` on a topic are integers. `-1` means unknown.

- `GET fewsservlet/hello`

  Plain-text hello. No JWT.

- `POST fewsservlet/tweets`

  Requires a JWT. JSON array of topics, each with `name`, `negated`, and `genuine`. Returns a JSON array of tweets (`id`, `extract`, `uri`, `text`, `created`).

- `GET fewsservlet/topics`

  Requires a JWT. JSON array of topics.

- `GET fewsservlet/vocab`

  Requires a JWT. JSON array of vocabulary topics (`topic`, `schema`, `keywords`).

- `GET fewsservlet/vocab/{vocabTopic}`

  Requires a JWT. One vocabulary topic, or 404.

- `POST fewsservlet/vocab/{vocabTopic}/schema/{vocabSchema}`

  Requires a JWT. Creates that vocabulary topic. 409 if the name already exists.

- `POST fewsservlet/vocab/{vocabTopic}/keywords/{vocabKeyword}`

  Requires a JWT. Adds a keyword and asks fact-extraction to refresh. 404 if the topic is missing, 409 if the keyword exists.

- `DELETE fewsservlet/vocab/{vocabTopic}`

  Requires a JWT. Deletes the topic and refreshes fact-extraction.

- `DELETE fewsservlet/vocab/{vocabTopic}/keywords/{vocabKeyword}`

  Requires a JWT. Deletes that keyword and refreshes fact-extraction.

## PROVSIMP

Source: `PROVSIMP/src/provsimp/ProvSimpServlet.java`, actions in `ProvForkControl`. Jersey package `provsimp`, mapped to `/rest/*`.

- `GET PROVSIMP/rest/ProcProv`

  HTML hello page.

- `POST PROVSIMP/rest/ProcProv`

  Requires a JWT. JSON body with an `action` field. Handled values:

  - `addnodes` — `user`, and `nodes` keyed by node id
  - `save` — `user`, `nodes`
  - `load` — `prov`, `nodes`
  - `copynode` — `from`, `to`
  - `getnode` — `user`, `nodeID`, `obf`
  - `getpaths` — `user`, `nodeID`, `obf`
  - `getpathnodes` — `user`, `nodeID`, `pathID`
  - `getprovelems` — `user`, `nodes`, `obf`
  - `getprefnodes` — `user`, `obf`, `graph`, `elements`
  - `loadpartpref` — accepted, and the handler returns null

  There is no `addnode` action.

## provgraphs

Source: `provgraphs/src/plots/PlotProvServlet.java`, mapped in `provgraphs/WebContent/WEB-INF/web.xml` to both `/Prov` and `/PlotProvServlet`. The commented `byloc`, `byday`, `byquest`, and `byans` actions are not implemented.

- `GET` or `POST provgraphs/Prov?action=printprov&json=...`
- `GET` or `POST provgraphs/PlotProvServlet?action=printprov&json=...`

  `json` is the node-provenance JSON. The response is `image/png` when a graph file is produced. `web.xml` names `filters.JWTTokenNeededFilter` on both patterns. The servlet class does not read a token itself.

## info

Source: `info/src/info/INFOServlet.java`, `INFInputServlet.java`, `InfoServHttp.java`, and `InfoServQueryHttp.java`. Jersey package `info`, mapped to `/rest/*`. The two `HttpServlet` classes are also mapped by `@WebServlet`.

- `GET info/rest/GetInfo`

  HTML hello page.

- `POST info/rest/GetInfo`

  JSON body. Reads the info service and returns JSON.

- `GET info/rest/PostInfo`

  HTML hello page.

- `POST info/rest/PostInfo`

  JSON body posted into the info store. Returns JSON.

- `GET info/InfoServHttp`

  Writes `Served at GET:` plus the context path.

- `POST info/InfoServHttp`

  Form parameters `dest`, `stream`, `text`, `source`, `dids`, and `lprov`.

- `GET info/InfoServQueryHttp?action=death|trespass|others`

  `dest` is also read. `death`, `trespass`, and `others` are the only actions implemented. `byloc`, `byday`, `byquest`, and `byans` appear only in a comment.

- `POST info/InfoServQueryHttp`

  Writes `Served at POST:` plus the context path. It does not run a query.
