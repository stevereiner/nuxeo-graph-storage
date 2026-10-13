# ArcadeDB transactions and concurrency

Details for the README's [Limitations](../README.md#limitations-and-next-steps).

Two modes, set with `nuxeo.arcadedb.transactions` (see the README's
[Configuration](../README.md#configuration-nuxeoconf)):

- **ArcadeDB with repository transactions** (`nuxeo.arcadedb.transactions=true`): ArcadeDB has no record locks; it
  detects concurrent changes of a record at commit.
  - `writeLock=always` serializes writing transactions with a type lock (`LOCK TYPE`, at the first write): reads
    aren't affected, write throughput is bounded.
  - `fallback` and `never` are optimistic, with repeatable reads (under read committed, ArcadeDB's default, an update
    computed from an earlier read silently overwrites a concurrent change). A conflicting transaction's writes are
    replayed on the current data (the last writer of a property wins, as with Nuxeo on MongoDB); with `fallback`, after
    `optimisticReplays` replays, it is replayed holding the lock. Optimistic mode fails
    `testOptimisticLockingWithParallelChange` and `testConcurrentArrayUpdateAndRemove`: a stale change token is
    detected at commit, after Nuxeo's request completed, not during the save.
  - The lock covers every vertex and edge type of the database (read when it is taken): ArcadeDB refuses to commit
    changes to types a held lock doesn't cover, and deleting a vertex changes the types of its edges and of the
    vertices they link. Other data in the database (a knowledge graph linked to the documents) is locked too while a
    locked repository transaction writes.
- **ArcadeDB without repository transactions** (the default): as in Nuxeo's MongoDB repository, which runs without
  MongoDB transactions, a Nuxeo transaction isn't a database transaction. Each write commits on its own (holding the
  write lock for that write only, or optimistic with `writeLock=fallback`); DBS rolls back with its undo log.
  - A document and its edges are written in one script, one ArcadeDB transaction: an insert batch with its edges, an
    update with the replacement of its `CHILD_OF`/`PROXY_OF`/`VERSION_OF` edge
    (`LET u = UPDATE ...; IF ($u[0].count = 1) { DELETE ...; CREATE EDGE ... }`), a delete with its edges. A document
    never lacks its edges or has stale ones; operations on several documents (deleting, moving or copying a folder)
    aren't atomic as a whole, as with MongoDB.
  - As in the MongoDB repository, the database applies updates where ArcadeDB SQL can: new values and increments of
    top-level properties, lists only appended to or pulled from (`p = ifnull(p, []) || :items`, `removeAll`). It can't
    set a deeper path (`a.b.c`, `a[0].b`): other changes to complex properties are applied in Java to the current value
    and written only if it didn't change meanwhile (`WHERE p = :old`, deep equality of maps and lists), else applied
    again. Change token conditions are in the same statement: a stale token fails the save at once
    (`ConcurrentUpdateException`), as Nuxeo expects.
  - It passes 2 more core tests than with transactions, faster, and concurrent writes don't wait for each other's whole
    transaction. Trade-off, as with MongoDB: a Nuxeo transaction isn't atomic in the database (other sessions see its
    writes before it ends; a crash mid-transaction leaves them).
