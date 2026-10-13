# Data model

Details for the README's [Data model](../README.md#data-model).

## Neo4j: hybrid graph

```
(:NxDocument:Nx_<repo> {`ecm:id`, `ecm:primaryType`, `dc:title`, `dc:subjects`: [...], ...})
   -[:CHILD_OF]->    (:NxDocument)        parent, derived from ecm:parentId
   -[:PROXY_OF]->    (:NxDocument)        proxy target, from ecm:proxyTargetId
   -[:VERSION_OF]->  (:NxDocument)        live doc of a version, from ecm:versionSeriesId
   -[:PROP {key:'content'}]->            (:NxComplex {name, digest, length, mime-type, ...})
   -[:PROP {key:'files', pos:0}]->       (:NxComplex)-[:PROP {key:'file'}]->(:NxComplex)
   -[:PROP {key:'ecm:acp', pos:0}]->     (:NxComplex {name:'local'})-[:PROP {key:'acl', pos:i}]->(:NxComplex {user, perm, grant})
(:NxLock:Nx_<repo> {lockKey, owner, created})
```

- Scalars and arrays of scalars are node properties; complex values and lists of them are child `:NxComplex` nodes,
  linked by `PROP` relationships carrying the key and, for list elements, the position.
- **Node counts differ between the backends**, as ArcadeDB embeds complex values in the document's vertex. A fresh
  Docker Compose stack with one test file holds 31 documents (mostly Nuxeo's own: workflow route nodes, roots, a workspace):
  31 vertices in ArcadeDB; in Neo4j 31 `NxDocument` plus 94 `NxComplex` nodes (workflow transitions and task buttons
  with their nested values, the ACLs of `ecm:acp`, blob descriptors), 125 in all.
- **Nulls:** Neo4j lists can't contain nulls: null array elements are recorded as positions in a `__nulls__<key>`
  property, null list elements are `__null__` nodes.
- **Dates:** stored as UTC `DateTime`, because Cypher datetime equality depends on the time zone.
- **Isolation:** every node also has an `Nx_<repository>` label, so a repository can share a database with other data
  (tests only ever delete `Nx_test` nodes).
- **Hierarchy reads** traverse `CHILD_OF*`; **writes** are batched by depth, one `UNWIND` query per nesting level.

## ArcadeDB: documents with embedded values, plus edges

ArcadeDB vertices are documents, so each Nuxeo document is **one `NxDocument` vertex holding the whole state**, with
complex values as embedded maps and lists, as in MongoDB. `CHILD_OF`/`PROXY_OF`/`VERSION_OF` edges materialize the
hierarchy, proxies and versions; locks are `NxLock` documents. A database holds one repository; other data can share
it. Handled ArcadeDB specifics (ArcadeDB 26.10.1):

- **Lossy types over HTTP/JSON:** small longs come back as integers, and dates can't be sent. Dates are stored as epoch
  millis, and values are converted back using the Nuxeo schema types (`ArcadeDBConverter`).
- **Null semantics:** `<>`, `NOT` and `NOT IN` match missing properties: translated negations get `IS NOT NULL` guards.
- **Result cap:** results are capped at 20,000 rows, so all reads are paged.
- **Transaction errors:** any server error inside a remote transaction rolls it back on the server; the transaction is
  then marked dead and its commit fails.
- **Late conflicts:** duplicate keys and MVCC conflicts surface at commit as `ConcurrentUpdateException`.
- **Locking:** no record locks: `NxLock` uses a unique index (insert or duplicate), conditional updates check change
  tokens.
- **Descendants:** the indexed `ecm:ancestorIds` list (`BY ITEM` index), as edge traversals hit the 20,000-row cap.
