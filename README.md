# nuxeo-graph-storage

Prototype storage backends that keep Nuxeo's **document and folder nodes** (the whole content tree) and their metadata
in a **property graph database**, **Neo4j** or **ArcadeDB**. A Nuxeo folder is a document with the `Folderish` facet,
so files, folders, workspaces and the root are all `NxDocument` nodes, linked by `CHILD_OF` relationships. The
backends store everything Nuxeo would store in MongoDB: document and folder state (metadata), hierarchy, versions and
proxies. Sibling project of [oak-graph-store](https://github.com/stevereiner/oak-graph-store), which does the same for
Apache Jackrabbit Oak.

Nuxeo stays the content services and application layer, and the rest of Nuxeo stays where it is. Every write goes
through Nuxeo: there is no second store to synchronize.

| Data | Store |
|---|---|
| Document and folder nodes + metadata (DBS state) | **Neo4j** or **ArcadeDB** (this project) |
| File content (blobs) | Nuxeo's blob providers (file system, S3, ...) |
| Users, groups, directories, audit, key/value | PostgreSQL (unchanged) |
| Search index (optional, as with MongoDB) | OpenSearch or Elasticsearch (unchanged; the Docker Composes have either) |

Built and tested against Nuxeo LTS 2025 from the public GitHub repository [nuxeo/nuxeo](https://github.com/nuxeo/nuxeo),
its default branch `2025` (version `2025.22-SNAPSHOT`, commit `00b5bed6`, pinned in `docker/.env`): the snapshots
Nuxeo publishes there, not a released Nuxeo LTS build.

Status: **prototype**. Both backends run Nuxeo's own core repository test suite, unchanged (see
[Current status](#current-status)), and Nuxeo's Web UI works with either one (see [Using the Web UI](#using-the-web-ui)).

## Why

A graph-backed Nuxeo repository brings together what each side does best:

- **Content services, from Nuxeo:** document types, schemas and facets, versioning, inherited permissions, locking,
  workflows, audit, search, previews and renditions, digital asset management, publishing, the REST API and Web UI.
- **Connected data, from the graph database:** typed, directed relationships traversed directly (paths,
  neighborhoods, dependencies several hops away), where a document store or SQL needs a lookup or join per hop. The
  gain is in relationship questions, not in every lookup or write.
- **Business entities, not only files:** a Nuxeo document doesn't need a file, so products, contracts, suppliers,
  cases or policies can be typed documents with the same permissions, versions, audit, search and API. Where an
  application would use MongoDB for flexible records, this keeps the flexibility (growing schemas, nested values) and
  adds typed entities, relationships and content services.
- **Knowledge graphs and ontologies:** enterprise knowledge graphs (often handcrafted) and ontologies managed as
  content: versioned, permission-controlled, reviewed in workflows, audited. Document types can map to ontology
  classes, metadata to properties, relation predicates (URIs in Nuxeo relations) to ontology predicates; extracted
  entities link to the documents and versions that are their evidence. The repository graph (folders, versions, ACLs:
  written by Nuxeo) and a semantic graph (entities, concepts: written by pipelines) can share the database, each with
  its own update rules.
- **AI and agents:** the graph gives an agent context, paths and provenance; Nuxeo controls what it may see and do.
- **Neo4j or ArcadeDB:** Neo4j's Cypher ecosystem, or ArcadeDB's multi-model engine (graph, documents, vectors,
  fulltext) with Cypher or SQL. Apache 2.0, like Nuxeo.

For example, a manufacturer keeps products, assemblies, components, suppliers, specifications and change orders as
typed documents. With document references as edges (a [possible feature](#edge-types)), one graph query finds every
product affected by a recalled component, through any number of subassemblies; Nuxeo then serves the approved
specifications the user may read, starts a remediation workflow and records it all in the audit.

## Uses

| Use case | Example question | Nuxeo's part |
|---|---|---|
| Product structures, bills of materials | Which released products depend on this component, directly or through subassemblies? | Specifications, drawings, approvals, revisions |
| Contracts and obligations | Which active supplier contracts have obligations affected by this policy change? | Versions, approvals, signed files, retention, audit |
| Digital assets | Which approved assets may be used for this product, in this territory, now? | Renditions, previews, rights metadata, lifecycle |
| Regulations and policies | Which controls, procedures and evidence does this amended regulation affect? | Controlled versions, review cycles, attestations |
| Knowledge hub, GraphRAG | Which approved documents support this decision, and who owns the systems it affects? | Search, previews, permissions, publishing |

With the repository in a graph database:

- **GraphRAG pipelines:** knowledge graphs built from the documents (for example by
  [Flexible GraphRAG](https://github.com/stevereiner/flexible-graphrag), which has Nuxeo and Alfresco as data sources)
  can link their entities to the source document nodes, in the folder tree, with their versions, and share the
  database: with Neo4j, the repository's nodes carry an `Nx_<repository>` label; with ArcadeDB, a database holds one
  repository, with other types next to it.
- **Permissions:** Nuxeo filters every NXQL query and API call by the user's permissions; a direct graph query
  (privileged, service-level access) doesn't. Each document node carries its read ACL (`ecm:racl`: the users and groups
  that can read it, inherited permissions included), so a graph query can filter on the user's principals, or return
  candidates that are then read through Nuxeo.
- **Agents:** find candidates and paths with a graph query, read the documents through Nuxeo (permission-checked), act
  through Nuxeo's REST API or Automation (workflows, locks and audit apply).
- **Change sync:** the audit records creations, modifications and deletions, in PostgreSQL and, in the Docker Composes,
  live on the Kafka topic `nuxeo-audit-audit` ([Audit and change events](#audit-and-change-events-postgresql-and-kafka)),
  to keep knowledge graphs and indexes in sync.
- **Exploration:** Neo4j Browser or ArcadeDB Studio show the content tree and version chains as a graph (and other
  relationships, once relations and references are edges). A Cypher query picks the part to see (a folder's subtree, a
  document's neighborhood: a whole repository is too large), there or in a purpose-built graph UI.

## Developer: How to use

Applications read and write the repository through Nuxeo, unchanged from its MongoDB storage:

- **The REST API** ([documentation](https://doc.nuxeo.com/rest-api/1/)), under `/nuxeo/api/v1`: documents by path
  (`/path/default-domain/workspaces`) or id (`/id/<id>`), their files, NXQL searches
  (`/search/lang/NXQL/execute?query=...`, examples in [docs/queries.md](docs/queries.md)), batch uploads, and
  Automation operations (`/automation/<operation>`: `Document.Create`, `Repository.Query`, `Search.Index`, ...);
  content enrichers add related data to the responses, on request (an HTTP header).
- **Client SDKs for the REST API** ([Client SDKs](https://doc.nuxeo.com/nxdoc/client-sdks/)):
  - **Java:** the [Nuxeo Java Client](https://doc.nuxeo.com/client-java/4.0/) (`org.nuxeo.client:nuxeo-java-client`,
    Java 11+, from Nuxeo's Maven repository): a `NuxeoClient.Builder` with `.url("http://localhost:8083/nuxeo")` and
    `.authentication("Administrator", "Administrator")`, then `.connect()`; for example
    `client.repository().fetchDocumentByPath("/default-domain")`.
  - **Python:** the [Nuxeo Python Client](https://doc.nuxeo.com/nxdoc/python-client/)
    ([nuxeo/nuxeo-python-client](https://github.com/nuxeo/nuxeo-python-client), Apache 2.0):
    `Nuxeo(host="http://localhost:8083/nuxeo/", auth=("Administrator", "Administrator"))`, then for example
    `nuxeo.documents.get(path="/default-domain")` or
    `nuxeo.client.query("SELECT * FROM Note WHERE ecm:isVersion = 0")`. Its version 7.1.0 imports two libraries it
    doesn't declare: install it with `pip install "nuxeo[oauth2]" packaging`.
  - **JavaScript:** the [Nuxeo JavaScript Client](https://doc.nuxeo.com/nxdoc/javascript-client/).
  - **.NET:** the [Nuxeo .NET Client](https://doc.nuxeo.com/nxdoc/net-client/).
  - **PHP:** the [PHP Automation Client](https://doc.nuxeo.com/nxdoc/php-automation-client/).
- **CMIS** ([CMIS](https://doc.nuxeo.com/nxdoc/cmis/)): the OASIS standard for content repositories, CMIS 1.1, built
  into Nuxeo (Apache Chemistry OpenCMIS). The Browser binding (JSON), `http://localhost:8083/nuxeo/json/cmis`, is the
  recommended one; AtomPub is `/nuxeo/atom/cmis`. Clients: CMIS Workbench, or the Apache Chemistry libraries (Java,
  Python, PHP, .NET). CMIS queries (CMIS QL) are translated to NXQL, so they run in the graph database too. Without
  `searchAllVersions=true` they only find the latest versions of versioned documents; with it, the live documents
  too (and all the versions):
  `curl -u Administrator:Administrator -G http://localhost:8083/nuxeo/json/cmis/default --data-urlencode cmisselector=query --data-urlencode searchAllVersions=true --data-urlencode "q=SELECT cmis:name FROM cmis:document"`.
- **The Web UI** ([user documentation](https://doc.nuxeo.com/userdoc/web-ui/),
  [developer documentation](https://doc.nuxeo.com/nxdoc/web-ui/)), a web application above the REST API (see
  [Using the Web UI](#using-the-web-ui)).

All of them check the user's permissions, apply versioning, locks and workflows, and record the audit.

Besides Nuxeo's APIs, the content tree can be read directly with Cypher (Neo4j), or Cypher or SQL (ArcadeDB): see
[Queries](#queries). That access is privileged: it isn't checked against the user's permissions (filter on
`ecm:racl`, see [Uses](#uses)).

Write through Nuxeo (the REST API, its SDKs, CMIS, the Web UI), not directly to the repository's nodes: Nuxeo maintains
derived values (read ACLs, ancestors, change tokens), caches documents and records the audit. Other nodes, and edges
between them and the document nodes, are fine: deleting a document removes its edges. With ArcadeDB, the repository's
write lock then covers the other data's types too (see [Limitations](#limitations-and-next-steps)).

## How Nuxeo concepts are stored

| Nuxeo | Stored here as |
|---|---|
| Document (file, note, ...) | `NxDocument` node / vertex |
| Folderish document (folder, workspace, domain, root) | `NxDocument` node / vertex |
| Document type (`File`, `Folder`...) | `ecm:primaryType` property |
| Facet (`Folderish`, `Versionable`...) | `ecm:mixinTypes` property (instance facets) |
| Schema property (`dc:title`...) | node property |
| Complex property (blob info, lists of structures) | Neo4j: `NxComplex` child node; ArcadeDB: embedded map |
| Parent / child (`ecm:parentId`) | `CHILD_OF` relationship + property |
| Proxy (published view of a document) | `PROXY_OF` relationship |
| Version | `NxDocument` node + `VERSION_OF` relationship |
| Blob (file content) | Not in the graph: Nuxeo's blob providers (file system, S3...), the node keeps the blob key |
| ACL / ACE, inherited | Embedded in the node (`ecm:acp`), read ACL `ecm:racl` |
| Lock | Separate `NxLock` record |
| NXQL | Translated to Cypher (Neo4j) or SQL (ArcadeDB) |
| DBS storage (MongoDB) | This project: a graph database |

## Modules

| Module | Package | Content |
|---|---|---|
| `nuxeo-core-storage-graph` | `org.integratedsemantics.nuxeo.graph` | Backend-independent code: NXQL translation with exactness tracking, query execution (pushdown or prefilter + Java evaluation), keyset scrolls, update diffs |
| `nuxeo-core-storage-neo4j` | `org.integratedsemantics.nuxeo.graph.neo4j` | Neo4j backend: hybrid graph, Neo4j Java driver + Cypher-DSL |
| `nuxeo-core-storage-arcadedb` | `org.integratedsemantics.nuxeo.graph.arcadedb` | ArcadeDB backend: one vertex per document with embedded complex values, ArcadeDB Java remote API + SQL |
| `nuxeo-graph-storage-core-tests` | `org.integratedsemantics.nuxeo.graph.coretests` | Runs Nuxeo's core repository tests against a backend (profile `core-tests`) |

Group id `org.integratedsemantics.nuxeo.graph`; the parent POM is Nuxeo's `org.nuxeo:nuxeo-ecm`, from the local Nuxeo
build (see [Build](#build)).

## Edge types

Edges are directed from source to target, with the same names in Neo4j and ArcadeDB:

| Edge type | Direction | Meaning | Status |
|---|---|---|---|
| `CHILD_OF` | child -> parent | Containment | both backends |
| `PROXY_OF` | proxy -> target | Proxy (a "symbolic link" to a document or version) | both backends |
| `VERSION_OF` | version -> live document | Version | both backends |
| `REFERENCES` | source -> target | Properties with a document resolver. Edge properties: `name` (the property), `ordinal` (position in a multi-valued property) | possible feature |
| `RELATED_TO` | source -> target | Relations. Edge properties: `predicate`, `inverse`, `relationId` | possible feature |
| `IN_COLLECTION` | member -> collection | Collections | possible feature |

Each link keeps its native meaning (predicate, order) rather than becoming a generic edge, so a GraphRAG pipeline
reading the graph can use it. Relations use one `RELATED_TO` type with a `predicate` property, not a type per
predicate: predicates are URIs, and Neo4j can't take a relationship type as a query parameter. The edge types are
shared with the other graph projects, so graphs from several content repositories can be queried alike.

### Possible features: relations, collections, document references

All three are document ids in properties, which the backends see as plain values on every write (like the parent,
proxy target and version series ids already turned into edges):

- **Relations:** each relation is a hidden `DefaultRelation` document (schema `relation`: `source`, `target`,
  `predicate`, and `sourceUri`, `targetUri`, `targetString` for external resources and literals); it would also be a
  `RELATED_TO` edge from source to target, with its predicate.
- **Collections:** `collectionMember:collectionIds` on the member (and `collection:documentIds` on the collection):
  `IN_COLLECTION` edges.
- **Document references:** schema fields declared with `ref:resolver="documentResolver"`: `REFERENCES` edges named
  after the property.

## How it plugs into Nuxeo

Nuxeo has two storage families: VCS (SQL) and DBS (document stores: MongoDB and in-memory). Each backend here is
another **DBS backend**, built like `nuxeo-core-storage-mongodb`: a `*RepositoryService` component with a `repository`
extension point, registering with `DBSRepositoryService`; a `*Repository extends DBSRepositoryBase`; a
`*Connection extends GraphConnectionBase` (the `DBSConnection` SPI). Nuxeo's `DBSSession` and `DBSTransactionState`
keep doing the hard parts: path resolution, ACL inheritance (`ecm:racl`), versioning, proxies, change tokens, the
transaction cache. The backends only store and query DBS `State`s (nested maps).

- **Neo4j:** a Nuxeo transaction is a database transaction (an explicit driver transaction per connection). Outside
  Nuxeo transactions (startup, auto-commit statements), `Neo4jStatementExecutor` uses driver-managed transactions,
  retried on transient errors. All driver sessions share a bookmark manager, for causal consistency on clusters.
- **ArcadeDB:** each connection uses its own `RemoteDatabase` (HTTP session) from a small pool. By default, as in
  Nuxeo's MongoDB repository, each write is one server-side SQL script, its own ArcadeDB transaction, and DBS rolls
  back with its undo log; `nuxeo.arcadedb.transactions=true` makes a Nuxeo transaction a database transaction (see
  [Limitations](#limitations-and-next-steps)).

**Locks** are not transactional in Nuxeo (a lock must be visible to other transactions immediately): they are separate
`NxLock` records (keyed by `repository/docId`) written outside the document transaction, like VCS's lock table, and
merged back into the document state for NXQL.

## Data model

- **Neo4j: hybrid graph.** Each document is an `NxDocument` node (also labeled `Nx_<repository>`) with its scalars and
  arrays as properties; complex values (blobs, ACLs, lists of structures) are child `NxComplex` nodes linked by `PROP`
  relationships. Neo4j therefore has more nodes than ArcadeDB for the same documents (a fresh stack's 31 documents:
  125 nodes).
- **ArcadeDB: documents with embedded values, plus edges.** Each document is one `NxDocument` vertex holding the whole
  state, with complex values embedded as in MongoDB.

Both link the documents with `CHILD_OF`/`PROXY_OF`/`VERSION_OF` edges and keep locks as `NxLock` records. Details (the
graph pattern, nulls, dates, isolation; ArcadeDB's lossy JSON types, null semantics, 20,000-row cap, descendants):
[docs/data-model.md](docs/data-model.md).

## Queries

`GraphQueryTranslator` translates NXQL into Cypher (Neo4j) or SQL (ArcadeDB). Each condition is either **exact**, so
sorting, paging and the total count run in the database, or a **superset**, whose candidates Nuxeo's
`DBSExpressionEvaluator` filters in Java. Fulltext on one field (what the Web UI's suggestion box sends without a
search engine) is rewritten into word-start matches; scrolls use keyset pagination. Details: what is translated and
what isn't, the fulltext rewrite, example NXQL queries, and examples reading the graph directly with Cypher (Neo4j)
and SQL (ArcadeDB): [docs/queries.md](docs/queries.md).

## Configuration (nuxeo.conf)

**Neo4j**

| Property | Default |
|---|---|
| `nuxeo.neo4j.uri` | `bolt://localhost:7687` |
| `nuxeo.neo4j.username` | `neo4j` |
| `nuxeo.neo4j.password` | *(blank = no auth)* |
| `nuxeo.neo4j.database` | *(server default database)* |
| `nuxeo.neo4j.transactionTimeout` | `300` (seconds; the server terminates transactions left open longer) |

**Neo4j AuraDB** (or any Neo4j cluster) works with the same driver, the `neo4j+s://<id>.databases.neo4j.io` URI and
the instance credentials. Reads go to the cluster leader (write sessions); the shared bookmark manager gives causal
consistency between the backend's sessions. Aura Free has node and relationship limits, and every complex value (blob,
list element, ACL entry) is a node. Tests against Aura: `-Dnuxeo.test.neo4j.uri=neo4j+s://...
-Dnuxeo.test.neo4j.username=neo4j -Dnuxeo.test.neo4j.password=...` (they only create and delete `Nx_test` nodes).

**ArcadeDB**

| Property | Default |
|---|---|
| `nuxeo.arcadedb.host` | `localhost` |
| `nuxeo.arcadedb.port` | `2480` (HTTP) |
| `nuxeo.arcadedb.database` | `nuxeo` (must exist; one repository per database) |
| `nuxeo.arcadedb.username` | `root` |
| `nuxeo.arcadedb.password` | |
| `nuxeo.arcadedb.buckets` | `8` (buckets of the document type: spreads concurrent inserts) |
| `nuxeo.arcadedb.writeLock` | `always` (each write, or with `transactions=true` each writing transaction, holds a type lock); `fallback` (optimistic, the lock after conflicts); `never` (optimistic only). See Limitations |
| `nuxeo.arcadedb.optimisticReplays` | `3` (with `fallback`: optimistic replays of a conflicting transaction before the locked one) |
| `nuxeo.arcadedb.transactions` | `false` (no repository transactions, like Nuxeo's MongoDB repository: each write commits on its own, updates applied by the database); `true`: a Nuxeo transaction is an ArcadeDB transaction. See Limitations |

On the ArcadeDB server, raise the HTTP transaction session timeout (default 5 s), for example
`-Darcadedb.server.httpSessionExpireTimeout=300` in the Docker `JAVA_OPTS`.

Repository contribution, here for Neo4j (`ArcadeDBRepositoryService` for ArcadeDB):

```xml
<extension target="org.integratedsemantics.nuxeo.graph.neo4j.Neo4jRepositoryService" point="repository">
  <repository name="default" label="Neo4j Repository" isDefault="true">
    <fulltext disabled="false" />
  </repository>
</extension>
```

## Docker Composes

`docker/` has two Docker Compose files, one per graph database. Each runs a Nuxeo LTS 2025 server with the Web UI: its
default repository (documents and folders) in the graph database, everything else (users and groups, directories,
audit, key/value store) in PostgreSQL, search in OpenSearch (or Elasticsearch), and Nuxeo Stream on Kafka (with the
audit stream, for change sync). Each file is a separate Compose project with its own containers, network and volumes,
so both can run at once. The services they share (Nuxeo, PostgreSQL, the search engine and its dashboard, Kafka and
Kafka UI) are defined once in `docker/nuxeo-base.yaml`, which both files extend, and both build the same Nuxeo image,
`nuxeo-graph-storage:local`. The versions, passwords, host ports and each stack's search engine are in `docker/.env`.

| | Neo4j stack | ArcadeDB stack | Login |
|---|---|---|---|
| Nuxeo Web UI | http://localhost:8083/nuxeo/ui | http://localhost:8084/nuxeo/ui | `Administrator` / `Administrator` |
| Graph database UI | Neo4j Browser, http://localhost:7476 (Bolt 7691) | ArcadeDB Studio, http://localhost:2491 | `neo4j` / `password`; `root` / `playwithdata` |
| Search engine / its dashboard (OpenSearch / OpenSearch Dashboards, or Elasticsearch / Kibana) | http://localhost:9204 / http://localhost:5605 | http://localhost:9205 / http://localhost:5606 | none (security disabled) |
| The dashboard's index list (OpenSearch Dashboards; with Elasticsearch, Kibana's `/app/management/data/index_management/indices`) | http://localhost:5605/app/opensearch_index_management_dashboards#/indices | http://localhost:5606/app/opensearch_index_management_dashboards#/indices | none |
| Kafka (host listener) / Kafka UI | `localhost:9092` / http://localhost:8092 | `localhost:9093` / http://localhost:8093 | none |

The graph databases' passwords are in `docker/.env`; `Administrator` is Nuxeo's default administrator (change its
password in the Web UI). The other services have no login: development only. With both stacks running, open one with
`localhost` and the other with `127.0.0.1` (on `localhost` they would share their session cookie).
[docs/port-mappings.md](docs/port-mappings.md) lists the host ports and the ports they avoid (other local stacks,
Windows' reserved range 9248-9347).

### Neo4j: `docker/compose.neo4j.yaml`

Compose project `nuxeo-graph-neo4j`: Neo4j Community (`NEO4J_VERSION` in `docker/.env`: 2026.09.0), with Neo4j
Browser on port 7476 and Bolt on 7691, and the shared Nuxeo services. Nuxeo uses the `graph-neo4j` template and
reaches Neo4j over Bolt (`NEO4J_URI`, `NEO4J_USERNAME`, `NEO4J_PASSWORD`). Search engine: `NUXEO_NEO4J_SEARCH_ENGINE`.

```
docker compose -f docker/compose.neo4j.yaml up --build -d    # build the Nuxeo image (the first time: long) and start
docker compose -f docker/compose.neo4j.yaml logs -f nuxeo    # follow Nuxeo's startup
docker compose -f docker/compose.neo4j.yaml stop             # stop the containers
docker compose -f docker/compose.neo4j.yaml up -d            # start again
docker compose -f docker/compose.neo4j.yaml down             # remove the containers and network, keep the volumes
docker compose -f docker/compose.neo4j.yaml down -v          # also delete the volumes: all of the stack's content
```

### ArcadeDB: `docker/compose.arcadedb.yaml`

Compose project `nuxeo-graph-arcadedb`: ArcadeDB (`ARCADEDB_VERSION` in `docker/.env`: 26.10.1), with ArcadeDB Studio
and the HTTP API on port 2491, started with the database `nuxeo` and a 300 s HTTP session timeout, and the shared Nuxeo
services. Nuxeo uses the `graph-arcadedb` template and reaches ArcadeDB over HTTP (`ARCADEDB_HOST`, `ARCADEDB_DATABASE`,
`ARCADEDB_USERNAME`, `ARCADEDB_PASSWORD`). Search engine: `NUXEO_ARCADEDB_SEARCH_ENGINE`.

```
docker compose -f docker/compose.arcadedb.yaml up --build -d    # build the Nuxeo image (if not built yet) and start
docker compose -f docker/compose.arcadedb.yaml logs -f nuxeo    # follow Nuxeo's startup
docker compose -f docker/compose.arcadedb.yaml stop             # stop the containers
docker compose -f docker/compose.arcadedb.yaml up -d            # start again
docker compose -f docker/compose.arcadedb.yaml down             # remove the containers and network, keep the volumes
docker compose -f docker/compose.arcadedb.yaml down -v          # also delete the volumes: all of the stack's content
```

A stack's volumes belong together: a document's metadata is in the graph database, its file content in Nuxeo's data
volume, its history in PostgreSQL and its index in the search engine. Delete all of them (`down -v`) or none. The
services, their volumes and what each holds: [docs/docker.md](docs/docker.md).

### Using the Web UI

Log in to the Web UI (http://localhost:8083/nuxeo/ui, or 8084 for the ArcadeDB stack) as `Administrator`. Nuxeo's
[Web UI user documentation](https://doc.nuxeo.com/userdoc/web-ui/) covers it all; to get started:

1. **Create a workspace first:** the side menu's **Browse** opens the navigation tree: the domain, then
   **Workspaces**. In **Workspaces**, the **+** button at the bottom right creates a workspace: choose **Workspace**,
   give it a title, **Create**.
2. **Then folders and documents inside it:** in the workspace (or a folder), the bottom right **+** creates a folder
   (**Folder**, on its **Create** tab).
3. **Documents from files:** in the workspace or folder, the bottom right **+**, then switch from the **Create** tab to
   the **Import** tab. Click "Drag and drop, or click to select files to upload." and choose the files in the file
   upload dialog (or drop them there). Then **ADD PROPERTIES**, select the type (**File**, or **Note** for a text
   file), edit the properties (title, description, ...), and click **CREATE**. Dropping files straight onto a
   workspace or folder's listing also imports them, each titled with its file name.
4. **Preview:** a document's **View** tab previews its file: PDF and images directly, office and text files converted
   to PDF (by LibreOffice, in the image).
5. **Search** ([Search](https://doc.nuxeo.com/userdoc/search/)): the quick search box at the upper right suggests
   documents, users and groups as you type; the side menu's **Search** combines a text search with filters on
   metadata, each value with its count of documents, and **Save as** keeps a search. Both are answered by the search
   engine (see [Search](#search-opensearch-or-elasticsearch)).

The workspaces, folders, documents and their versions are then `NxDocument` nodes in the graph database (see
[Queries](#queries) to look at them there).

### The Nuxeo image: `docker/Dockerfile`

- **Built from public source,** with no Nuxeo Connect account: the image builds the Nuxeo server distribution and the
  Web UI marketplace package from the public `nuxeo/nuxeo` and `nuxeo/nuxeo-web-ui` repositories, then installs the
  Web UI with `nuxeoctl mp-install` (the approach of
  [aborroy/nuxeo-deployment](https://github.com/aborroy/nuxeo-deployment), Apache 2.0). The graph storage and the
  audit and search packages below are built in the same image build, against that Nuxeo.
- **Versions in `docker/.env`:** the Nuxeo commit (`NUXEO_GIT_REF`), the Web UI commit (`NUXEO_WEBUI_GIT_REF`) and the
  other images' versions. Nuxeo's public repository is a delayed mirror (commits published almost daily, about 3
  months after they were made): pin the commit of the local Nuxeo clone the graph storage is built and tested against
  (see [Building Nuxeo](#building-nuxeo)).
- **Rebuilds:** the first build is long (Nuxeo and the Web UI from source); later ones reuse Docker's layers and Maven
  cache. The Nuxeo stage hands its artifacts to the graph storage stage through that cache, which Docker can clear
  (pruning, Docker Desktop's cleanup) while keeping the layers: the build then stops with a message, and the Nuxeo
  stage must run again (about 35 minutes), for example
  `docker build --no-cache-filter nuxeo-build -f docker/Dockerfile -t nuxeo-graph-storage:local` with the
  `--build-arg`s of `docker/.env` (`NUXEO_GIT_REF`, `NUXEO_WEBUI_GIT_REF`, ...), from the repository root.
- **Libraries:** the image adds the graph storage bundles and only the libraries the server doesn't have, in the
  versions the build resolved (Nuxeo's managed versions: Netty, Gson, ... are shared with the server).
- **Converters:** LibreOffice (previews and PDF conversions of office and text files: the Web UI previews them through
  PDF), the version of Nuxeo's own image (`LIBREOFFICE_VERSION` in `docker/.env`), from The Document Foundation's
  archive; amd64 only, like Nuxeo's image. ImageMagick, Ghostscript and Poppler (thumbnails, PDF previews). Not
  included: FFmpeg (video).

### Nuxeo configuration: `config/` and `templates/`

- **`config/conf.d/`:** `nuxeo.conf` fragments, appended when the container is created, with values from environment
  variables set in the Compose files: `10-graph-storage.conf` (`NUXEO_TEMPLATES`, `NUXEO_DB_*`, `NEO4J_*`,
  `ARCADEDB_*`, `OPENSEARCH_URL`) and `20-kafka.conf` (`KAFKA_ENABLED`, `KAFKA_BOOTSTRAP_SERVERS`). Add your own files
  there. The folder is mounted: a change needs no image rebuild, only a new container
  (`docker compose -f docker/compose.neo4j.yaml up -d --force-recreate nuxeo`). `/etc/nuxeo` isn't a volume: Nuxeo's
  entrypoint generates `nuxeo.conf` only when there is none, so a persisted one would ignore later changes.
- **`config/models/`:** custom content models (schemas, facets, document types: XML contributions and their XSD
  files), packaged into a bundle when the image is built, as aborroy/nuxeo-deployment does for its Content Lake facets.
  See [config/models/README.md](config/models/README.md).
- **`templates/`:** `graph-neo4j` and `graph-arcadedb`, Nuxeo configuration templates, usable outside Docker too (copy
  them to the server's `templates/`, set `nuxeo.templates=graph-neo4j`): each includes the `postgresql` template and
  replaces only its repository configuration (`default-repository-config.xml`), as Nuxeo's `mongodb` template does for
  MongoDB.

### Search: OpenSearch, or Elasticsearch

Each stack chooses its search engine in `docker/.env` (`NUXEO_NEO4J_SEARCH_ENGINE`, `NUXEO_ARCADEDB_SEARCH_ENGINE`):
`opensearch` (the default: OpenSearch 1.3 with OpenSearch Dashboards) or `elasticsearch` (Elasticsearch 8.19 with
Kibana). The image installs Nuxeo's search client (`nuxeo-search-client-opensearch1`): the search engine answers the
Web UI's folder listings, searches and suggestion box, while document reads and writes, and NXQL queries through the
REST API, Automation or `CoreSession.query`, still run in the graph database. To index existing content:
`curl -u Administrator:Administrator -H 'Content-Type: application/json' -d '{"params":{}}' http://localhost:8083/nuxeo/api/v1/automation/Search.Index`.

Details: switching engines, the engines the client works with (not OpenSearch 2 or Elasticsearch 9), another search
server, running without one: [docs/search.md](docs/search.md).

### Audit and change events: PostgreSQL and Kafka

- **Audit in PostgreSQL:** Nuxeo 2025 keeps the audit (document history) in memory unless an audit package is
  installed: the image installs `nuxeo-audit-sql` (built from the same source) and enables its `sql-audit` template.
  Older settings such as `audit.elasticsearch.enabled` don't apply. Many Nuxeo deployments put the audit in the search
  engine instead, with `nuxeo-audit-opensearch1` (the search client's engines: OpenSearch 1, Elasticsearch 7 and 8);
  PostgreSQL keeps this setup's audit next to Nuxeo's other data.
- **Nuxeo Stream on Kafka** (`config/conf.d/20-kafka.conf`, Kafka 3.9.2, the version of Nuxeo's client): bulk
  commands, works and the audit go through Kafka topics instead of memory, as in production. With
  `nuxeo.stream.audit.enabled=true`, every audit entry (`documentCreated`, `documentModified`, `documentRemoved`, ...)
  is on the topic `nuxeo-audit-audit` before it is written to PostgreSQL, for external consumers to follow the
  repository's changes: for example Flexible GraphRAG's Nuxeo change sync, with
  `NUXEO_KAFKA_BOOTSTRAP_SERVERS=localhost:9092` (neo4j stack) or `localhost:9093` (arcadedb stack). Kafka UI shows the
  topics and messages.

## Build

Needs JDK 21+ and Maven 3.9 (the Docker build needs none of this: it builds everything inside the image). Behind TLS
inspection on Windows, a `.mvn/jvm.config` with `-Djavax.net.ssl.trustStoreType=Windows-ROOT` in each of the two
projects below makes Maven trust the Windows certificate store (git-ignored in this project).

### Building Nuxeo

This project builds against the Nuxeo `2025.22-SNAPSHOT` artifacts that a local build of Nuxeo LTS 2025 installs in
`~/.m2`: the public [nuxeo/nuxeo](https://github.com/nuxeo/nuxeo) repository, branch `2025`, at the commit pinned in
`docker/.env` (`NUXEO_GIT_REF`). In the directory that will hold the Nuxeo clone:

```
git clone https://github.com/nuxeo/nuxeo.git
cd nuxeo
git checkout 00b5bed6f966941679aa9762c0f5286375902e44   # NUXEO_GIT_REF in docker/.env
mvn install -DskipTests                                # with MAVEN_OPTS="-Xmx4g -Xms2g"
```

Nuxeo's default build (its `boms` and `modules`) is all this project needs.

### Building nuxeo-graph-storage

Then, in this project's directory:

```
mvn install                 # build, and run the backends' tests (Docker needed, see Tests)
mvn install -DskipTests
```

Nuxeo's core repository tests run separately, with the `core-tests` profile ([Nuxeo core tests](#nuxeo-core-tests)).
The parent POM disables the inherited `packages.nuxeo.com` repository: third-party artifacts come from Maven Central.

Graph database versions (change each one's two settings together):

- **Neo4j (2026.09.0):** `NEO4J_VERSION` in `docker/.env` sets the Docker Compose's Neo4j server; `neo4j.version` in
  the parent `pom.xml` sets the tests' Neo4j image (`-Dnuxeo.test.neo4j.image=<image>` for another one). The Neo4j
  Java driver (6.3.0) is `neo4j-driver.version`, also in the parent `pom.xml`.
- **ArcadeDB (26.10.1):** `ARCADEDB_VERSION` in `docker/.env` sets the Docker Compose's ArcadeDB server;
  `arcadedb.version` in the parent `pom.xml` sets the ArcadeDB Java client and the tests' ArcadeDB image
  (`-Dnuxeo.test.arcadedb.image=<image>` for another one).

## Tests

Each backend has an integration test (`TestNeo4jRepository`, `TestArcadeDBRepository`) running the standard Nuxeo
`CoreFeature` (`CoreSession` API) and checking what is stored. By default they start their own server (Docker needed):

- **Neo4j:** a Testcontainers container, image `neo4j:2026.09.0` (`neo4j.version` in the parent `pom.xml`), the
  version of the Docker Compose; `-Dnuxeo.test.neo4j.image=<image>` for another one.
- **ArcadeDB:** a Testcontainers container, image `arcadedata/arcadedb:26.10.1`, the version of the Docker Composes.
  Without Docker, `-Dnuxeo.test.arcadedb.mode=inprocess` starts the server in the test JVM. In every mode the backend
  uses the remote (HTTP) API, but only the container is a separate server with its own JVM and timing.

To use external servers (local or remote, Docker or standalone), uncomment the lines in the surefire configuration of
each module's POM, or pass system properties:

```
mvn test -pl nuxeo-core-storage-neo4j -Dnuxeo.test.neo4j.uri=bolt://localhost:7687 -Dnuxeo.test.neo4j.username=neo4j -Dnuxeo.test.neo4j.password=password
mvn test -pl nuxeo-core-storage-arcadedb -Dnuxeo.test.arcadedb.host=localhost -Dnuxeo.test.arcadedb.port=2480 -Dnuxeo.test.arcadedb.database=nuxeo-test -Dnuxeo.test.arcadedb.password=playwithdata
```

Test output: each test class's logs go to `target/surefire-reports/<class>-output.txt`, not to the console
(`redirectTestOutputToFile`), which shows Maven's test summaries and failures. The tests' `log4j2-test.xml` logs
warnings and errors (and this project's own INFO), with stack traces cut to 3 lines, as many tests provoke exceptions
on purpose; `logging.properties` keeps the Neo4j driver's `java.util.logging` output to warnings.

Test wiring: `CoreFeature` only knows the vcs/mem/mongodb backends, so surefire runs it with `nuxeo.test.core=mem`, and
the backend's repository feature re-registers the `test` repository with its own factory.

### Nuxeo core tests

`nuxeo-graph-storage-core-tests` runs Nuxeo's own repository tests from the `nuxeo-core-test` tests jar (such as
`TestSQLRepositoryAPI`, `...Query`, `...Properties`, `...Versioning`, `...Security`, `...Locking`), each as a thin
subclass adding `GraphBackendFeature`:

```
mvn install -Pcore-tests -Dnuxeo.test.graph=neo4j      # or arcadedb, or mem (baseline: Nuxeo's in-memory backend)
```

Results: see [Current status](#current-status).

## Licensing

This project is licensed under the [Apache License 2.0](LICENSE). Everything linked at runtime is Apache 2.0: Nuxeo,
the Neo4j Java driver, Cypher-DSL, the ArcadeDB engine, network client and server. Testcontainers (tests only) is MIT.

The **Neo4j server is GPLv3** (Community) or commercial (Enterprise, Aura). It's only ever reached over Bolt as a
separate process (the Docker container in tests): no Neo4j server code is embedded, even in tests.

**Docker Composes.** The image is built locally from public source; it isn't published. Besides Nuxeo and the Web UI
(Apache 2.0), it contains the programs Nuxeo runs as separate command-line processes (not linked), as Nuxeo's own image
does: **LibreOffice** (MPL 2.0), ImageMagick (ImageMagick License), Ghostscript (AGPL 3.0) and the Poppler utilities
(GPL), on Oracle Linux 9 with Azul Zulu 21 (GPLv2 with Classpath Exception). The Compose files run the PostgreSQL
(PostgreSQL License), OpenSearch and OpenSearch Dashboards (Apache 2.0), or Elasticsearch and Kibana (Elastic License
2.0, SSPL or AGPLv3), Kafka (Apache 2.0), Kafka UI (kafbat, Apache 2.0), Neo4j Community (GPLv3) and ArcadeDB
(Apache 2.0) images, also as separate processes.

## Current status

Nuxeo's core repository tests (15 classes from `nuxeo-core-test`, 449 tests), run unchanged with `-Pcore-tests`: Nuxeo's
whole core suite, not a selection, written for all of Nuxeo's storage backends. Some also fail on Nuxeo's in-memory
backend (the mem row), for reasons unrelated to the graph storage.

| Backend | Passed | Failed | Skipped |
|---|---|---|---|
| mem (Nuxeo's in-memory DBS backend, baseline) | 384 | 38 | 27 |
| **Neo4j** (2026.09 in Docker via Testcontainers, Java driver) | **412** (413 when the intermittent `testConcurrentSavesNoDeadlock` passes) | 10 | 27 |
| **ArcadeDB** (26.10.1 in Docker via Testcontainers, remote API; no repository transactions, MongoDB-style) | **414** | 8 | 27 |
| ArcadeDB, `nuxeo.arcadedb.transactions=true` (write lock per transaction) | 410 (*) | 12 | 27 |
| ArcadeDB, `nuxeo.arcadedb.transactions=true`, `writeLock=fallback` (optimistic transactions) | 408 (*) | 14 | 27 |

(*) Measured before the tests had their own logging configuration (`src/test/resources/log4j2-test.xml`), which made
Nuxeo's 2 log-capture tests pass on every backend (+2 on the rows above, run since).

All but 2 or 3 of the graph backends' failures also fail on the mem baseline (pre-existing DBS/test issues:
`TestDocument` complex values, `NOW()`, `testInvalidQuery`, date literals, ...). The others:

| Test | Backends | Why |
|---|---|---|
| `testBinaryGC` | both | Expected: like MongoDB (for which the test is skipped), blobs are deleted with their documents thanks to the blob keys capability. |
| `testScrollApiConcurrency` | Neo4j (ArcadeDB with `nuxeo.arcadedb.transactions=true`) | Expected: scrolls from other threads can't see documents that the test thread hasn't committed (transaction isolation; mem, standalone MongoDB and ArcadeDB by default have none). |
| `testConcurrentSavesNoDeadlock` | Neo4j, intermittent (random document order) | Two transactions save overlapping documents in different orders. DBS walks its dirty documents in hash order, so Neo4j's node write locks can deadlock. Neo4j detects it and one transaction fails with `ConcurrentUpdateException`. The fix belongs in Nuxeo: sort `DBSTransactionState.save()` updates by id, like VCS. (ArcadeDB passes thanks to its write lock.) |

Some Nuxeo tests leave transactions open on failure paths (no `finally`). On transactional backends such a transaction
keeps its locks, so a server-side timeout terminates it, as MongoDB's 60 s transaction lifetime does: Neo4j's
`nuxeo.neo4j.transactionTimeout` (default 300 s, 20 s in tests), ArcadeDB's HTTP session expiry (20 s in tests). Each
Neo4j transaction carries metadata (thread and calling Nuxeo frames), visible in `SHOW TRANSACTIONS`: that is how the
leak was found.

## Limitations and next steps

- **ArcadeDB transactions:** by default (`nuxeo.arcadedb.transactions=false`), as in Nuxeo's MongoDB repository, a
  Nuxeo transaction isn't a database transaction: each document is written with its edges in one ArcadeDB
  transaction, but a Nuxeo transaction isn't atomic in the database. With `transactions=true`, ArcadeDB having no
  record locks, writing transactions hold a type lock (`writeLock=always`: write throughput bounded, other data in the
  database locked too) or are optimistic (`fallback`, `never`: 2 more core tests fail). Details:
  [docs/arcadedb-transactions.md](docs/arcadedb-transactions.md).
- **ArcadeDB connections:** each connection uses a pooled `RemoteDatabase`, without health checking.
- **Neo4j concurrency:** concurrent saves of overlapping documents in different orders can deadlock (see
  [Current status](#current-status)). Deletions lock proxy and version targets in a consistent order to avoid the
  common case.
- **Fulltext:** the graph backends have no fulltext index: at scale, fulltext search is the search engine's job
  (included in the Docker Composes), as for Nuxeo on MongoDB. Without one, `ecm:fulltext` conditions are evaluated in Java
  over the text Nuxeo extracts (`ecm:fulltextSimple`/`ecm:fulltextBinary`), after the other conditions have narrowed
  the candidates: fine for small repositories, slow for large ones (fulltext on one field: see [Queries](#queries)).
- **Commit errors don't reach the caller:** Nuxeo commits the repository's database transaction after its own
  transaction completed (`afterCompletion`, as for MongoDB), where an exception goes no further: the REST call has
  already succeeded. The backends log such a failure at `ERROR` ("transaction commit failed, its changes are lost").
  ArcadeDB reports some conflicts only at commit.
- **`NOW()`:** not supported (Nuxeo's evaluator doesn't support functions either).
- **Graph extras:** more relationships (document links, relations), and graph queries exposed to Nuxeo users.
