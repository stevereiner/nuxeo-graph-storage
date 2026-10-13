# Search: OpenSearch, or Elasticsearch

Details for the README's [Docker Composes](../README.md#docker-composes).

Each stack chooses its search engine in `docker/.env` (`NUXEO_NEO4J_SEARCH_ENGINE`, `NUXEO_ARCADEDB_SEARCH_ENGINE`):
`opensearch` (the default: OpenSearch 1.3 with OpenSearch Dashboards) or `elasticsearch` (Elasticsearch 8.19 with
Kibana, the version of the Oak graph store's stacks). The service is `nuxeo-search` either way, on the same host
ports, so Nuxeo's settings don't change. Each engine keeps its index in its own volume: after switching, recreate the
stack's search services, restart Nuxeo and reindex (below), for example
`docker compose -f docker/compose.arcadedb.yaml up -d` then `docker compose -f docker/compose.arcadedb.yaml restart nuxeo`.

The image installs Nuxeo's OpenSearch search client (`nuxeo-search-client-opensearch1`, built from the same source)
and enables its `opensearch1-search-client` template, as Nuxeo deployments on MongoDB do. Nuxeo indexes documents as
they change; the search engine answers the Web UI's folder listings, searches and suggestion box, with real fulltext
(stemming, relevance) and the search filters' counts. Document reads and writes, and NXQL queries through the REST
API, Automation (`Repository.Query`) or `CoreSession.query`, still run in the graph database.

Engines the client works with (Nuxeo's [compatibility matrix](https://doc.nuxeo.com/nxdoc/compatibility-matrix/) and
[search setup](https://doc.nuxeo.com/nxdoc/search-setup/); tested here with this setup):

| Engine | With this client | |
|---|---|---|
| OpenSearch 1.3 (1.3.20 in docker/.env) | Yes | Nuxeo's fully tested target |
| Elasticsearch 7, 8 (8.19.23 in docker/.env) | Yes | Tested: 8.17.10 (indexing, fulltext, suggestion box, listings); 8.19.23 in the stacks (indexing, fulltext and title search) |
| OpenSearch 2.x, 3.x | No | Tested: 2.19.6 fails (Nuxeo can't parse its bulk responses). The `nuxeo-search-client-opensearch2` package the search setup page mentions isn't in the public source |
| Elasticsearch 9 | No | Needs Nuxeo's `nuxeo-search-client-elasticsearch9` package (Elasticsearch 9 only), not in the public source either |
| Embedded OpenSearch | Yes | In Nuxeo's JVM (`nuxeo-opensearch1-embed` package, in the public source): for development and tests, not used here |

So the stacks run their own OpenSearch 1.3 or Elasticsearch 8, not a shared OpenSearch 2.x such as Flexible
GraphRAG's or Content Lake's (2.19.6). To use another server, point `OPENSEARCH_URL` at it in `docker/nuxeo-base.yaml`
(property `nuxeo.opensearch1.client.server`; the older `elasticsearch.addressList` still works as a fallback), and set
the user and password in a `config/conf.d` file if needed (`nuxeo.opensearch1.client.username`, `...password`).

To index existing content (for example after adding a search engine to a running stack), run Nuxeo's indexing as
Administrator:
`curl -u Administrator:Administrator -H 'Content-Type: application/json' -d '{"params":{}}' http://localhost:8083/nuxeo/api/v1/automation/Search.Index`.
Without a search engine (remove `opensearch1-search-client` from `NUXEO_TEMPLATES` in the stack's Compose file), every
search runs in the graph database.
