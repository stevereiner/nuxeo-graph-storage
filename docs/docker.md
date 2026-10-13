# Docker Composes: services and volumes

Details for the README's [Docker Composes](../README.md#docker-composes).

Both stacks, `docker/compose.neo4j.yaml` and `docker/compose.arcadedb.yaml`, have the same services and volumes, except
the graph database. Docker prefixes the volumes with the Compose project name, for example
`nuxeo-graph-neo4j_nuxeo-db-data`.

| Service | Volume | Holds |
|---|---|---|
| `neo4j` / `arcadedb` | `neo4j-data` / `arcadedb-databases` | The graph database: documents and folders (metadata, hierarchy, versions, ACLs) |
| `nuxeo-db` (PostgreSQL) | `nuxeo-db-data` | Nuxeo's other data: users and groups, directories, audit, key/value store |
| `nuxeo-search` (OpenSearch or Elasticsearch), `nuxeo-search-dashboards` | `nuxeo-opensearch-data` or `nuxeo-elasticsearch-data` | Nuxeo's search index (can be rebuilt from the repository) |
| `nuxeo-kafka` (Kafka, KRaft), `nuxeo-kafka-ui` | `nuxeo-kafka-data` | Nuxeo Stream: bulk commands, works, the audit topic |
| `nuxeo` (Nuxeo) | `nuxeo-data` | The files' content (`binaries/`), transient files |
| | `nuxeo-logs` | Nuxeo's logs |

Each stack declares seven volumes and uses six: only the volume of its current search engine is in use.

`docker compose ... down` removes the containers and the network and keeps the volumes, so the next `up` finds the
content again; `down -v` also deletes the volumes. Keep or delete a stack's volumes together: a document's metadata is
in the graph database, its file content in `nuxeo-data`, its history (audit) in PostgreSQL, its search index in the
search engine, and pending work in Kafka. Deleting only some of them leaves documents without their content, history
or index.
