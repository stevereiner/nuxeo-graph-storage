# Queries

Details for the README's [Queries](../README.md#queries): NXQL through Nuxeo, and the graph read directly with Cypher
or SQL.

## NXQL

`GraphQueryTranslator` translates NXQL (with Cypher-DSL for Neo4j, SQL strings for ArcadeDB). Every translated
condition is either **exact** (same results as Nuxeo's evaluator) or a **superset**:

- **Exact query:** `ORDER BY`, `OFFSET`, `LIMIT` and the total count run in the database; only the requested page of
  documents is loaded.
- **Superset query:** candidates are fetched in batches and Nuxeo's `DBSExpressionEvaluator` applies the exact
  semantics in Java. Batches come in `ORDER BY` order when possible, so the query stops once the page is full.

Exactness relies on Nuxeo's evaluator using SQL-like ternary logic (`null = x` is null). The translator also handles
`ecm:` booleans (false when null); `NOT`, only over a single predicate (the evaluator's AND/OR aren't commutative with
null); `ecm:mixinType`, computed from facets like the evaluator does; nested paths (`file:content/name`,
`files:files/*/file/name`, `files:files/1/file/name`), as Neo4j `EXISTS {}` patterns or ArcadeDB `content.name`,
`files[1]...` and `files CONTAINS (...)`; the security check on `ecm:racl`; `NXQL ORDER BY`, which sorts nulls first
(emulated in Cypher, native in ArcadeDB). Not translated (evaluated in Java): fulltext, `ecm:acl/*`, correlated
wildcards `*1`, casts, `NOW()`.

**Fulltext on one field** (`ecm:fulltext.dc:title LIKE 'hello wor*'`, which the Web UI's suggestion box at the top
right sends when there is no OpenSearch) is rewritten by `FieldFulltextRewriter` into case-insensitive `ILIKE`
conditions: each term matches the start of a word (a word starts the field or follows a space or punctuation, so
`ontology` finds `company-ontology-test.txt`), with or without `*`, like the OpenSearch `match_phrase_prefix` the box
is meant for; `-term` excludes; all terms must match. Nuxeo's own DBS stores (MongoDB) find nothing there without
OpenSearch: they match the whole input, `*` included, as one `LIKE`. The suggestion box only searches titles; the
search page's Full Text field (`ecm:fulltext`) searches the content too.

Scrolls use keyset pagination on `ecm:id`: only the last id is kept between batches.

### NXQL examples

Run through Nuxeo, NXQL queries are permission-checked: each user only gets the documents they may read. For example
with the REST API, on the Neo4j stack (port 8084 for the ArcadeDB stack):

```
curl -u Administrator:Administrator -G http://localhost:8083/nuxeo/api/v1/search/lang/NXQL/execute \
     --data-urlencode "query=SELECT * FROM Note WHERE dc:title ILIKE 'cmis%' AND ecm:isVersion = 0"
```

| NXQL | Finds |
|---|---|
| `SELECT * FROM Document WHERE ecm:parentId = '<folder id>'` | A folder's children |
| `SELECT * FROM Document WHERE ecm:path STARTSWITH '/default-domain/workspaces' AND ecm:isVersion = 0 ORDER BY dc:title` | Everything under a folder, at any depth, without versions |
| `SELECT * FROM Note WHERE dc:title ILIKE 'cmis%' AND ecm:isVersion = 0` | Notes by title, case-insensitive |
| `SELECT * FROM File WHERE file:content/mime-type = 'application/pdf' AND ecm:isVersion = 0` | Files by a blob property (a nested path) |
| `SELECT * FROM Document WHERE ecm:isVersion = 1 ORDER BY dc:modified DESC` | Versions, latest first |
| `SELECT * FROM Document WHERE ecm:mixinType = 'Folderish' AND ecm:mixinType != 'HiddenInNavigation' AND ecm:isTrashed = 0` | Visible folders (by facet) |
| `SELECT * FROM Document WHERE dc:modified >= DATE '2026-01-01' AND ecm:primaryType IN ('File', 'Note') AND ecm:isVersion = 0` | Files and notes modified since a date |
| `SELECT * FROM Document WHERE ecm:fulltext = 'quokka'` | Fulltext: answered in Java over the candidates (see above), or by the search engine for the Web UI's searches |

## Querying the graph directly

The graph can also be read directly, with Cypher (Neo4j Browser, or ArcadeDB Studio's Cypher) or SQL (ArcadeDB).
Database access is privileged: a direct query isn't permission-checked (filter on `ecm:racl`, below), and writes go
through Nuxeo (see the README's [Uses](../README.md#uses)).

What to query:

- **Documents:** `NxDocument` nodes / vertices (in Neo4j also labeled `Nx_<repository>`, `Nx_default` in the Docker
  Composes), with Nuxeo's property names, which need backticks: `ecm:id`, `ecm:name`, `ecm:primaryType`,
  `ecm:parentId`, `ecm:ancestorIds`, `ecm:racl`, `dc:title`, `dc:modified`, ...
- **Hierarchy:** `CHILD_OF` edges from child to parent; the root's `ecm:name` is `""`. Paths aren't stored: they are
  the `ecm:name`s of the ancestors. `ecm:ancestorIds` lists the ancestors' ids (indexed in ArcadeDB).
- **Versions:** `VERSION_OF` edges from a version to its live document; versions have `ecm:isVersion` = true (unset on
  live documents) and `ecm:versionLabel`.
- **Blobs:** the blob's descriptor (`name`, `mime-type`, `length`, `digest`), not its content: in Neo4j an `NxComplex`
  node linked by `PROP {key: 'content'}` (for `file:content`), in ArcadeDB the embedded map `content`.

### Cypher (Neo4j)

```cypher
// the children of a workspace
MATCH (:NxDocument {`ecm:primaryType`: 'Workspace', `ecm:name`: 'graph-storage-tests'})<-[:CHILD_OF]-(c)
RETURN c.`ecm:name` AS name, c.`ecm:primaryType` AS type ORDER BY name

// a document's path: its ancestors up to the root
MATCH p = (:NxDocument {`ecm:name`: 'quokka-note'})-[:CHILD_OF*]->(:NxDocument {`ecm:primaryType`: 'Root'})
RETURN '/' + reduce(s = '', n IN reverse(nodes(p))[1..] | s + CASE s WHEN '' THEN '' ELSE '/' END + n.`ecm:name`) AS path

// what is under a folder, at any depth, by type
MATCH (d:NxDocument)-[:CHILD_OF*]->(:NxDocument {`ecm:name`: 'workspaces'})
RETURN d.`ecm:primaryType` AS type, count(*) AS n ORDER BY n DESC

// the versions of each document
MATCH (v:NxDocument)-[:VERSION_OF]->(d:NxDocument)
RETURN d.`dc:title` AS document, v.`ecm:versionLabel` AS version ORDER BY document, version

// files and their blobs (live documents only)
MATCH (d:NxDocument)-[:PROP {key: 'content'}]->(b:NxComplex)
WHERE b.name IS NOT NULL AND coalesce(d.`ecm:isVersion`, false) = false
RETURN d.`dc:title` AS title, b.name AS file, b.`mime-type` AS mimeType, b.length AS bytes

// what a user may read: the user, their groups and Everyone, in the document's read ACL
MATCH (d:NxDocument)
WHERE d.`ecm:primaryType` IN ['File', 'Note'] AND coalesce(d.`ecm:isVersion`, false) = false
  AND any(p IN d.`ecm:racl` WHERE p IN ['jdoe', 'members', 'Everyone'])
RETURN d.`dc:title` AS title
```

ArcadeDB Studio runs the queries on `CHILD_OF` and `VERSION_OF` unchanged; ArcadeDB has no `PROP` edges or
`NxComplex` nodes (complex values are embedded), so the blob query is SQL there (below).

### SQL (ArcadeDB)

```sql
-- the children of a workspace
SELECT `ecm:name` AS name, `ecm:primaryType` AS type FROM (SELECT expand(in('CHILD_OF')) FROM NxDocument
  WHERE `ecm:primaryType` = 'Workspace' AND `ecm:name` = 'graph-storage-tests') ORDER BY name

-- a document and its ancestors, up to the root
SELECT `ecm:name` AS name FROM (TRAVERSE out('CHILD_OF') FROM (SELECT FROM NxDocument WHERE `ecm:name` = 'quokka-note'))

-- what is under a folder, at any depth, by type (with the indexed ecm:ancestorIds)
SELECT `ecm:primaryType` AS type, count(*) AS n FROM NxDocument
  WHERE `ecm:ancestorIds` CONTAINS (SELECT `ecm:id` FROM NxDocument WHERE `ecm:name` = 'workspaces')[0].`ecm:id`
  GROUP BY `ecm:primaryType`

-- the versions of a document
SELECT `dc:title` AS title, `ecm:versionLabel` AS version FROM (SELECT expand(in('VERSION_OF')) FROM NxDocument
  WHERE `ecm:name` = 'cmispress.txt' AND `ecm:isVersion` IS NULL)

-- files and their blobs (live documents only)
SELECT `dc:title` AS title, content.name AS file, content.`mime-type` AS mimeType, content.length AS bytes
  FROM NxDocument WHERE content.name IS NOT NULL AND `ecm:isVersion` IS NULL

-- what a user may read: the user, their groups and Everyone, in the document's read ACL
SELECT `dc:title` AS title FROM NxDocument WHERE `ecm:primaryType` IN ['File', 'Note']
  AND `ecm:isVersion` IS NULL AND `ecm:racl` CONTAINSANY ['jdoe', 'members', 'Everyone']
```

The examples were run on the Docker Composes, whose test workspace `graph-storage-tests` holds the file `quokka-note`.
