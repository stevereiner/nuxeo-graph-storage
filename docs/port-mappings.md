# Port mappings

Host ports of the Docker stacks (`docker/compose.neo4j.yaml`, `docker/compose.arcadedb.yaml`). Each port is a variable
in `docker/.env`: change it there.

| Service | Neo4j stack | ArcadeDB stack | `docker/.env` variable | Container port |
|---|---|---|---|---|
| Nuxeo (Web UI: `/nuxeo/ui`) | 8083 | 8084 | `NUXEO_NEO4J_PORT`, `NUXEO_ARCADEDB_PORT` | 8080 |
| Neo4j Browser (HTTP) | 7476 | | `NEO4J_HTTP_PORT` | 7474 |
| Neo4j Bolt | 7691 | | `NEO4J_BOLT_PORT` | 7687 |
| ArcadeDB Studio and HTTP API | | 2491 | `ARCADEDB_HTTP_PORT` | 2480 |
| Search engine (OpenSearch or Elasticsearch) | 9204 | 9205 | `NUXEO_NEO4J_SEARCH_PORT`, `NUXEO_ARCADEDB_SEARCH_PORT` | 9200 |
| Search dashboard (OpenSearch Dashboards or Kibana) | 5605 | 5606 | `NUXEO_NEO4J_SEARCH_DASHBOARDS_PORT`, `NUXEO_ARCADEDB_SEARCH_DASHBOARDS_PORT` | 5601 |
| Kafka, host listener (`localhost:<port>`) | 9092 | 9093 | `NUXEO_NEO4J_KAFKA_PORT`, `NUXEO_ARCADEDB_KAFKA_PORT` | 9092 |
| Kafka UI | 8092 | 8093 | `NUXEO_NEO4J_KAFKA_UI_PORT`, `NUXEO_ARCADEDB_KAFKA_UI_PORT` | 8080 |

Not published on the host (reached only inside each stack's network): Nuxeo's PostgreSQL (`nuxeo-db:5432`), Kafka's
internal listener (`nuxeo-kafka:29092`) and controller (29093), the search engine's transport port (9300).

## Ports avoided

The host ports are chosen not to clash with other stacks that may run on the same machine:

| Ports | Used by |
|---|---|
| 8080, 8081 | Alfresco (proxy), Nuxeo of aborroy/nuxeo-deployment |
| 2480, 2424, 7689 | A default ArcadeDB (HTTP, binary, Bolt) |
| 7474, 7687 | A default Neo4j |
| 7688 | Memgraph |
| 5601-5604, 9200-9203 | Other Elasticsearch / OpenSearch servers and their dashboards |
| 8180, 8181, 2490, 7475, 7690, 9206, 9207, 5607, 5608 | The Oak graph store's stacks (oak-graph-store) |

On Windows, avoid 9248-9347: Hyper-V / WinNAT usually reserves that range, and binding a port in it fails ("An attempt
was made to access a socket in a way forbidden by its access permissions"). Check the reserved ranges with
`netsh interface ipv4 show excludedportrange protocol=tcp`.
