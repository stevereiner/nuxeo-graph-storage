package org.integratedsemantics.nuxeo.graph.arcadedb;

import static org.integratedsemantics.nuxeo.graph.arcadedb.ArcadeDBQueryTranslator.name;
import static org.integratedsemantics.nuxeo.graph.arcadedb.ArcadeDBRepository.DOC_TYPE;
import static org.integratedsemantics.nuxeo.graph.arcadedb.ArcadeDBRepository.LOCK_KEY;
import static org.integratedsemantics.nuxeo.graph.arcadedb.ArcadeDBRepository.LOCK_TYPE;
import static org.integratedsemantics.nuxeo.graph.arcadedb.ArcadeDBRepository.REFERENCE_KEYS;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_ANCESTOR_IDS;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_ID;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_LOCK_CREATED;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_LOCK_OWNER;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_NAME;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_PARENT_ID;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.apache.commons.collections4.ListUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.core.api.ConcurrentUpdateException;
import org.nuxeo.ecm.core.api.DocumentNotFoundException;
import org.nuxeo.ecm.core.api.Lock;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.lock.LockManager;
import org.nuxeo.ecm.core.schema.types.primitives.DateType;
import org.nuxeo.ecm.core.storage.State;
import org.nuxeo.ecm.core.storage.StateHelper;
import org.nuxeo.ecm.core.storage.State.ListDiff;
import org.nuxeo.ecm.core.storage.State.StateDiff;
import org.nuxeo.ecm.core.storage.dbs.DBSConnection;
import org.nuxeo.ecm.core.storage.dbs.DBSExpressionEvaluator;
import org.nuxeo.ecm.core.storage.dbs.DBSTransactionState.ConditionalUpdates;
import org.integratedsemantics.nuxeo.graph.GraphConnectionBase;
import org.integratedsemantics.nuxeo.graph.GraphQueryTranslator;
import org.integratedsemantics.nuxeo.graph.GraphQueryTranslator.SortKey;
import org.integratedsemantics.nuxeo.graph.GraphScroll;
import org.integratedsemantics.nuxeo.graph.StateUpdate;

import com.arcadedb.exception.DuplicatedKeyException;
import com.arcadedb.database.Database.TRANSACTION_ISOLATION_LEVEL;
import com.arcadedb.exception.NeedRetryException;
import com.arcadedb.exception.RecordNotFoundException;
import com.arcadedb.exception.SchemaException;
import com.arcadedb.exception.TransactionException;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.remote.RemoteDatabase;

/**
 * ArcadeDB implementation of a {@link DBSConnection}, using a {@link RemoteDatabase} client.
 * <p>
 * ArcadeDB specifics handled here:
 * <ul>
 * <li>results are capped at 20,000 rows by the HTTP API, so reads are paged,</li>
 * <li>any error inside a remote transaction rolls it back on the server, so the transaction is then considered dead
 * and its commit fails,</li>
 * <li>duplicate keys and MVCC conflicts are only detected at commit time, they become
 * {@link ConcurrentUpdateException}s,</li>
 * <li>locks are not transactional: they are written with another client, outside of the transaction.</li>
 * </ul>
 *
 * @since 0.1.0
 */
public class ArcadeDBConnection extends GraphConnectionBase<SqlCondition> {

    private static final Logger log = LogManager.getLogger(ArcadeDBConnection.class);

    /** Page size for reads, well below the 20,000 rows cap of the HTTP API. */
    protected static final int PAGE_SIZE = 1000;

    protected final ArcadeDBRepository arcadeRepository;

    protected final ArcadeDBConverter converter;

    protected RemoteDatabase db;

    protected boolean transactionActive;

    /** Set when an error happened in the transaction: the server already rolled it back. */
    protected boolean transactionDead;

    /** Set when the write lock was taken in the current transaction. */
    protected boolean writeLocked;

    /** Set when an optimistic transaction is replayed holding the write lock ({@link WriteLockMode#FALLBACK}). */
    protected boolean lockedReplay;

    /**
     * The writes of the current transaction, as Nuxeo issued them, to run them again in a new transaction if the commit
     * conflicts with another transaction: ArcadeDB detects conflicts at commit, which Nuxeo calls after its own
     * transaction completed, too late to fail or retry the request.
     */
    protected List<Runnable> redoLog = new ArrayList<>();

    /**
     * The isolation of the transactions. Without the write lock, repeatable reads: a transaction's commit fails if a
     * record it read was changed meanwhile (and is run again). With read committed (ArcadeDB's default), each statement
     * sees the latest committed data, so an update computed from an earlier read would silently overwrite a concurrent
     * change. With the write lock, writers are serialized, and the lock can't be taken after repeatable reads (ArcadeDB:
     * "Explicit lock must be acquired before any modification"): read committed.
     */
    protected TRANSACTION_ISOLATION_LEVEL isolation() {
        return locking() ? TRANSACTION_ISOLATION_LEVEL.READ_COMMITTED : TRANSACTION_ISOLATION_LEVEL.REPEATABLE_READ;
    }

    /** Whether the current transaction takes the write lock (at its first write). */
    protected boolean locking() {
        return arcadeRepository.writeLock == WriteLockMode.ALWAYS || lockedReplay;
    }

    /** How many times a transaction whose commit conflicts is run again. */
    protected static final int COMMIT_RETRIES = 20;

    /** Count of the transactions replayed holding the write lock after optimistic replays (for tests). */
    protected static final AtomicLong LOCKED_REPLAYS = new AtomicLong();

    /** Count of the transactions run again after a commit conflict (for tests and monitoring). */
    protected static final AtomicLong REPLAYS = new AtomicLong();


    public ArcadeDBConnection(ArcadeDBRepository repository) {
        super(repository);
        arcadeRepository = repository;
        converter = repository.converter;
    }

    protected RemoteDatabase db() {
        if (db == null) {
            db = arcadeRepository.borrow();
        }
        return db;
    }

    @Override
    public void begin() {
        if (transactionActive) {
            throw new NuxeoException("Transaction already started");
        }
        if (!arcadeRepository.transactions) {
            // each write commits on its own, see autoCommit
            return;
        }
        lockedReplay = false;
        db().begin(isolation());
        transactionActive = true;
        transactionDead = false;
        writeLocked = false;
        redoLog = new ArrayList<>();
    }

    @Override
    public void commit() {
        if (!transactionActive) {
            return;
        }
        try {
            if (transactionDead) {
                rollbackQuietly();
                throw new ConcurrentUpdateException("Transaction was aborted by an earlier error");
            }
            commitOrReplay();
        } catch (RuntimeException e) {
            // Nuxeo commits after its transaction completed (afterCompletion): the exception goes no further
            log.error("ArcadeDB: transaction commit failed, its changes are lost: {}", e.getMessage(), e);
            throw translate(e);
        } finally {
            transactionActive = false;
            transactionDead = false;
            lockedReplay = false;
            redoLog = new ArrayList<>();
        }
    }

    /**
     * Commits; if the commit conflicts with another transaction, runs the transaction's writes again in a new
     * transaction, and commits that. Nuxeo's writes are property changes (like MongoDB's {@code $set}), re-applied to
     * the current values: as with Nuxeo on MongoDB, the last writer of a property wins. A conditional write whose
     * condition no longer holds (a change token: a concurrent change of the same document) fails the replay. With
     * {@link WriteLockMode#FALLBACK}, a transaction still conflicting after a few replays (see
     * {@link ArcadeDBRepository#OPTIMISTIC_REPLAYS_PROPERTY}) is replayed holding the write lock (read committed, the
     * lock taken before the replay's first read).
     */
    protected void commitOrReplay() {
        for (int attempt = 1;; attempt++) {
            try {
                db.commit();
                return;
            } catch (NeedRetryException e) {
                if (attempt > COMMIT_RETRIES || redoLog.isEmpty()) {
                    throw e;
                }
                log.debug("ArcadeDB: commit conflict, running the transaction's {} writes again (attempt {}): {}",
                        redoLog.size(), attempt, e.getMessage());
                rollbackQuietly();
                pause(attempt);
                REPLAYS.incrementAndGet();
                if (!lockedReplay && arcadeRepository.writeLock == WriteLockMode.FALLBACK
                        && attempt > arcadeRepository.optimisticReplays) {
                    log.debug("ArcadeDB: replaying with the write lock");
                    lockedReplay = true;
                    LOCKED_REPLAYS.incrementAndGet();
                }
                var writes = redoLog;
                redoLog = new ArrayList<>();
                db.begin(isolation());
                writeLocked = false;
                try {
                    writes.forEach(Runnable::run);
                } catch (RuntimeException replayFailure) {
                    rollbackQuietly();
                    throw replayFailure;
                }
            }
        }
    }

    /** A short random pause before running a conflicting transaction again, longer at each attempt. */
    protected static void pause(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(5, 20) * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NuxeoException(e);
        }
    }

    @Override
    public void rollback() {
        if (!transactionActive) {
            return;
        }
        try {
            rollbackQuietly();
        } finally {
            transactionActive = false;
            transactionDead = false;
            redoLog = new ArrayList<>();
        }
    }

    protected void rollbackQuietly() {
        try {
            db.rollback();
        } catch (RuntimeException e) {
            log.debug("Rollback failed (transaction already gone on the server)", e);
        }
    }

    @Override
    public void close() {
        rollback();
        if (db != null) {
            arcadeRepository.release(db);
            db = null;
        }
    }

    @Override
    public String generateNewId() {
        return arcadeRepository.generateNewId();
    }

    /**
     * Translates database errors meaning a concurrent update into {@link ConcurrentUpdateException}.
     */
    protected static RuntimeException translate(RuntimeException e) {
        if (e instanceof NuxeoException) {
            return e;
        }
        if (e instanceof DuplicatedKeyException || e instanceof NeedRetryException
                || e instanceof RecordNotFoundException || e instanceof TransactionException) {
            return new ConcurrentUpdateException(e.getMessage(), e);
        }
        return e;
    }

    /** Runs a statement, tracking errors that abort the transaction. */
    protected <T> T run(Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException e) {
            if (transactionActive) {
                transactionDead = true;
            }
            throw translate(e);
        }
    }

    protected List<Result> query(String sql, Map<String, Object> params) {
        log.trace("ArcadeDB: QUERY {} {}", sql, params);
        return run(() -> db().query("sql", sql, params).stream().toList());
    }

    /**
     * Serializes the writing transactions, see {@link ArcadeDBRepository#WRITE_LOCK_PROPERTY}.
     */
    protected void lockForWrite() {
        if (transactionActive && !writeLocked && locking()) {
            var lock = writeLockStatement();
            log.trace("ArcadeDB: {}", lock);
            run(() -> db().command("sql", lock));
            writeLocked = true;
        }
    }

    /**
     * The write lock: every vertex and edge type of the database, read when the lock is taken. Once a transaction holds
     * a lock, ArcadeDB refuses to commit changes to types it doesn't cover, and deleting a document vertex also changes
     * the types of its edges and of the vertices at their other end, which can be other data's (a knowledge graph
     * linked to the documents, for example). A transaction can hold one lock statement, and a failure at commit can't
     * be retried, so the types are read each time. Document types (the separate {@code NxLock} records) are not
     * locked.
     */
    protected String writeLockStatement() {
        var types = new TreeSet<String>();
        types.add(DOC_TYPE);
        types.addAll(REFERENCE_KEYS.values());
        for (var result : query("SELECT name FROM schema:types WHERE type IN ['vertex', 'edge']", Map.of())) {
            types.add(result.getProperty("name"));
        }
        return "LOCK TYPE " + String.join(", ", types);
    }

    protected List<Result> command(String sql, Map<String, Object> params) {
        lockForWrite();
        log.trace("ArcadeDB: COMMAND {} {}", sql, params);
        return run(() -> db().command("sql", sql, params).stream().toList());
    }

    /** A write: in the current transaction, or committed on its own without repository transactions. */
    protected List<Result> write(String statement, Map<String, Object> params, boolean repeatableRead) {
        if (transactionActive) {
            return command(statement, params);
        }
        return autoCommit(List.of(statement), params, repeatableRead);
    }

    /** Server-side retries of a write committed on its own that conflicts with another ({@code COMMIT RETRY}). */
    protected static final int AUTO_COMMIT_RETRIES = 10;

    /** Count of the writes committed on their own that were run again holding the write lock (for tests). */
    protected static final AtomicLong LOCKED_AUTO_COMMITS = new AtomicLong();

    /** The write lock statement of the writes committed on their own, read again when the database has new types. */
    protected String autoCommitLock;

    /**
     * Runs statements as one server-side transaction, the result of the last one returned: optimistic first (unless
     * the write lock mode is {@link WriteLockMode#ALWAYS}), ArcadeDB running it again if it conflicts
     * ({@code COMMIT RETRY}), and holding the write lock if it still conflicts ({@link WriteLockMode#FALLBACK}).
     * Repeatable reads for updates computed from the current values, read committed for inserts (with repeatable
     * reads, ArcadeDB 26.10.1 may not check a unique index at commit).
     */
    protected List<Result> autoCommit(List<String> statements, Map<String, Object> params, boolean repeatableRead) {
        var body = new StringBuilder();
        for (int i = 0; i < statements.size(); i++) {
            body.append(i == statements.size() - 1 ? "LET r = " : "").append(statements.get(i)).append("; ");
        }
        return autoCommitScript(body.toString(), params, repeatableRead);
    }

    /** Like {@link #autoCommit}, for a script body that sets {@code $r}, the result returned. */
    protected List<Result> autoCommitScript(String body, Map<String, Object> params, boolean repeatableRead) {
        var mode = arcadeRepository.writeLock;
        if (mode != WriteLockMode.ALWAYS) {
            var script = "BEGIN%s; %sCOMMIT RETRY %d; RETURN $r;".formatted(
                    repeatableRead ? " ISOLATION REPEATABLE_READ" : "", body, AUTO_COMMIT_RETRIES);
            try {
                return script(script, params);
            } catch (NeedRetryException e) {
                if (mode == WriteLockMode.NEVER) {
                    throw translate(e);
                }
                log.debug("ArcadeDB: write still conflicting, running it again with the write lock: {}",
                        e.getMessage());
                LOCKED_AUTO_COMMITS.incrementAndGet();
            } catch (RuntimeException e) {
                throw translate(e);
            }
        }
        if (autoCommitLock == null) {
            autoCommitLock = writeLockStatement();
        }
        try {
            return script("BEGIN; %s; %sCOMMIT; RETURN $r;".formatted(autoCommitLock, body), params);
        } catch (RuntimeException e) {
            if (!isOutdatedLock(e)) {
                throw translate(e);
            }
            // types created or dropped since the lock statement was read
            autoCommitLock = writeLockStatement();
            try {
                return script("BEGIN; %s; %sCOMMIT; RETURN $r;".formatted(autoCommitLock, body), params);
            } catch (RuntimeException e2) {
                throw translate(e2);
            }
        }
    }

    /** Whether a locked write failed because the database's types changed since its lock statement was read. */
    protected static boolean isOutdatedLock(RuntimeException e) {
        var message = e.getMessage();
        return message != null && (message.contains("not all the modified resources were locked")
                || e instanceof SchemaException && message.contains("was not found"));
    }

    protected List<Result> script(String script, Map<String, Object> params) {
        log.trace("ArcadeDB: SCRIPT {} {}", script, params);
        return db().command("sqlscript", script, params).stream().toList();
    }

    protected static long count(List<Result> results) {
        if (results.isEmpty()) {
            return 0;
        }
        Number n = results.get(0).getProperty("count");
        if (n == null) {
            n = results.get(0).getProperty("cnt");
        }
        return n == null ? 0 : n.longValue();
    }

    // ----- reads -----

    /**
     * Reads one page of documents matching a condition.
     */
    protected List<State> readStatesWhere(String where, Map<String, Object> params, Collection<String> keys) {
        var states = new ArrayList<State>();
        for (var result : query("SELECT FROM %s WHERE %s".formatted(DOC_TYPE, where), params)) {
            states.add(converter.mapToState(result.toMap()));
        }
        return finishStates(states, keys);
    }

    /**
     * Reads all the documents matching a condition, by pages (ordered by id).
     */
    protected List<State> readStatesPaged(String where, Map<String, Object> params, Collection<String> keys,
            int limit) {
        var states = new ArrayList<State>();
        String afterId = "";
        for (;;) {
            var pageParams = new HashMap<>(params);
            pageParams.put("afterId", afterId);
            int pageSize = limit == 0 ? PAGE_SIZE : Math.min(PAGE_SIZE, limit - states.size());
            var results = query("SELECT FROM %s WHERE %s AND %s > :afterId ORDER BY %s LIMIT %d".formatted(DOC_TYPE,
                    where, name(KEY_ID), name(KEY_ID), pageSize), pageParams);
            for (var result : results) {
                states.add(converter.mapToState(result.toMap()));
            }
            if (results.size() < pageSize || (limit != 0 && states.size() >= limit)) {
                break;
            }
            afterId = (String) states.get(states.size() - 1).get(KEY_ID);
        }
        return finishStates(states, keys);
    }

    /**
     * Adds the locks to the states (locks are stored separately), and restricts them to the requested keys.
     */
    protected List<State> finishStates(List<State> states, Collection<String> keys) {
        if (!states.isEmpty()) {
            var byLockKey = new HashMap<String, State>();
            for (var state : states) {
                byLockKey.put(arcadeRepository.lockKey((String) state.get(KEY_ID)), state);
            }
            var params = new HashMap<String, Object>();
            params.put("keys", List.copyOf(byLockKey.keySet()));
            var locks = query("SELECT %s AS k, owner AS o, created AS c FROM %s WHERE %s IN :keys".formatted(LOCK_KEY,
                    LOCK_TYPE, LOCK_KEY), params);
            for (var lock : locks) {
                var state = byLockKey.get((String) lock.getProperty("k"));
                if (state != null) {
                    state.put(KEY_LOCK_OWNER, (String) lock.getProperty("o"));
                    state.put(KEY_LOCK_CREATED,
                            converter.fromArcade(KEY_LOCK_CREATED, lock.getProperty("c"), DateType.INSTANCE));
                }
            }
        }
        if (keys == null) {
            return states;
        }
        var partials = new ArrayList<State>(states.size());
        for (var state : states) {
            var partial = new State();
            partial.put(KEY_ID, state.get(KEY_ID));
            for (var key : keys) {
                partial.put(key, state.get(key));
            }
            partials.add(partial);
        }
        return partials;
    }

    @Override
    public State readState(String id) {
        return readPartialState(id, null);
    }

    @Override
    public State readPartialState(String id, Collection<String> keys) {
        if (id == null) {
            return null;
        }
        var states = readStatesWhere(name(KEY_ID) + " = :id", Map.of("id", id), keys);
        return states.isEmpty() ? null : states.get(0);
    }

    @Override
    public List<State> readStates(List<String> ids) {
        var states = new ArrayList<State>(ids.size());
        for (var batch : ListUtils.partition(ids, PAGE_SIZE)) {
            states.addAll(readStatesWhere(name(KEY_ID) + " IN :ids", Map.of("ids", batch), null));
        }
        return states;
    }

    protected String childWhere() {
        return "%s = :parentId AND %s = :name AND NOT (%s IN :ignored)".formatted(name(KEY_PARENT_ID),
                name(KEY_NAME), name(KEY_ID));
    }

    @Override
    public State readChildState(String parentId, String name, Set<String> ignored) {
        var states = readStatesWhere(childWhere(),
                Map.of("parentId", parentId, "name", name, "ignored", List.copyOf(ignored)), null);
        return states.isEmpty() ? null : states.get(0);
    }

    @Override
    public boolean hasChild(String parentId, String name, Set<String> ignored) {
        return exists(childWhere(), Map.of("parentId", parentId, "name", name, "ignored", List.copyOf(ignored)));
    }

    protected boolean exists(String where, Map<String, Object> params) {
        return !query("SELECT %s AS id FROM %s WHERE %s LIMIT 1".formatted(name(KEY_ID), DOC_TYPE, where),
                params).isEmpty();
    }

    @Override
    public List<State> queryKeyValue(String key, Object value, Set<String> ignored) {
        return readStatesPaged("%s = :value AND NOT (%s IN :ignored)".formatted(name(key), name(KEY_ID)),
                Map.of("value", converter.toArcade(value), "ignored", List.copyOf(ignored)), null, 0);
    }

    @Override
    public List<State> queryKeyValue(String key1, Object value1, String key2, Object value2, Set<String> ignored) {
        return readStatesPaged("%s = :value1 AND %s = :value2 AND NOT (%s IN :ignored)".formatted(name(key1),
                name(key2), name(KEY_ID)), Map.of("value1", converter.toArcade(value1), "value2",
                        converter.toArcade(value2), "ignored", List.copyOf(ignored)),
                null, 0);
    }

    @Override
    public List<State> queryKeyValueWithOperator(String key1, Object value1, String key2, DBSQueryOperator operator,
            Object value2, Set<String> ignored) {
        var values = ((Collection<?>) value2).stream().map(converter::toArcade).toList();
        var in = switch (operator) {
            case IN -> "%s IN :values".formatted(name(key2));
            case NOT_IN -> "NOT (%s IN :values)".formatted(name(key2));
        };
        return readStatesPaged("%s = :value1 AND %s AND NOT (%s IN :ignored)".formatted(name(key1), in,
                name(KEY_ID)),
                Map.of("value1", converter.toArcade(value1), "values", values, "ignored", List.copyOf(ignored)),
                null, 0);
    }

    @Override
    public boolean queryKeyValuePresence(String key, String value, Set<String> ignored) {
        return exists("%s = :value AND NOT (%s IN :ignored)".formatted(name(key), name(KEY_ID)),
                Map.of("value", value, "ignored", List.copyOf(ignored)));
    }

    @Override
    public Stream<State> getDescendants(String rootId, Set<String> keys) {
        return getDescendants(rootId, keys, 0);
    }

    /**
     * Uses the indexed {@code ecm:ancestorIds} list (a traversal of the CHILD_OF edges would be capped at 20,000
     * results by the HTTP API).
     */
    @Override
    public Stream<State> getDescendants(String rootId, Set<String> keys, int limit) {
        return readStatesPaged(name(KEY_ANCESTOR_IDS) + " CONTAINS :id", Map.of("id", rootId), keys, limit).stream();
    }

    // ----- writes -----

    @Override
    public void createState(State state) {
        createStates(List.of(state));
    }

    @Override
    public void createStates(List<State> states) {
        log.trace("ArcadeDB: CREATE {}", states);
        if (transactionActive) {
            // a copy: the states passed are Nuxeo's live document states, changed later in the transaction
            var copies = states.stream().map(StateHelper::deepCopy).toList();
            redoLog.add(() -> createStates(copies));
        }
        if (!transactionActive) {
            createStatesAutoCommit(states);
            return;
        }
        lockForWrite();
        for (var batch : ListUtils.partition(states, PAGE_SIZE)) {
            var maps = batch.stream().map(converter::stateToMap).toList();
            command("INSERT INTO %s FROM (SELECT expand(:list))".formatted(DOC_TYPE), Map.of("list", maps));
        }
        // edges, once all the documents of the batch exist
        var statements = new ArrayList<String>();
        var params = new HashMap<String, Object>();
        for (var state : states) {
            for (var en : REFERENCE_KEYS.entrySet()) {
                var target = state.get(en.getKey());
                if (target != null && !target.equals(state.get(KEY_ID))) {
                    int i = statements.size();
                    statements.add(createEdgeStatement(en.getValue(), ":f" + i, ":t" + i));
                    params.put("f" + i, state.get(KEY_ID));
                    params.put("t" + i, target);
                }
            }
        }
        for (int start = 0; start < statements.size(); start += 100) {
            var end = Math.min(start + 100, statements.size());
            var script = String.join(";\n", statements.subList(start, end)) + ";";
            var scriptParams = new HashMap<String, Object>();
            for (int i = start; i < end; i++) {
                scriptParams.put("f" + i, params.get("f" + i));
                scriptParams.put("t" + i, params.get("t" + i));
            }
            lockForWrite();
            log.trace("ArcadeDB: SCRIPT {} {}", script, scriptParams);
            run(() -> db().command("sqlscript", script, scriptParams));
        }
    }

    /** An edge of a reference key, from a document to the document it references. */
    protected record Edge(String type, Serializable from, Serializable to) {
    }

    /**
     * Inserts documents without a transaction: each batch of documents with their edges in one script, one ArcadeDB
     * transaction, so that a document never exists without its edges. An edge to a document of a later batch goes with
     * that batch. Read committed: with repeatable reads, ArcadeDB 26.10.1 may not check the unique index of the ids at
     * commit.
     */
    protected void createStatesAutoCommit(List<State> states) {
        var batches = ListUtils.partition(states, PAGE_SIZE);
        var batchOf = new HashMap<Serializable, Integer>();
        var edges = new ArrayList<List<Edge>>();
        for (int b = 0; b < batches.size(); b++) {
            for (var state : batches.get(b)) {
                batchOf.put(state.get(KEY_ID), b);
            }
            edges.add(new ArrayList<>());
        }
        for (var state : states) {
            var id = state.get(KEY_ID);
            for (var en : REFERENCE_KEYS.entrySet()) {
                var target = state.get(en.getKey());
                if (target != null && !target.equals(id)) {
                    int b = Math.max(batchOf.get(id), batchOf.getOrDefault(target, 0));
                    edges.get(b).add(new Edge(en.getValue(), id, target));
                }
            }
        }
        for (int b = 0; b < batches.size(); b++) {
            var statements = new ArrayList<String>();
            var params = new HashMap<String, Object>();
            statements.add("INSERT INTO %s FROM (SELECT expand(:list))".formatted(DOC_TYPE));
            params.put("list", batches.get(b).stream().map(converter::stateToMap).toList());
            int i = 0;
            for (var edge : edges.get(b)) {
                statements.add(createEdgeStatement(edge.type(), ":f" + i, ":t" + i));
                params.put("f" + i, edge.from());
                params.put("t" + i, edge.to());
                i++;
            }
            autoCommit(statements, params, false);
        }
    }

    protected static String createEdgeStatement(String edgeType, String fromParam, String toParam) {
        return "CREATE EDGE %s FROM (SELECT FROM %s WHERE %s = %s) TO (SELECT FROM %s WHERE %s = %s)".formatted(
                edgeType, DOC_TYPE, name(KEY_ID), fromParam, DOC_TYPE, name(KEY_ID), toParam);
    }

    @Override
    public void updateState(String id, StateDiff diff, ConditionalUpdates conditionalUpdates) {
        log.trace("ArcadeDB: UPDATE {}: {}", id, diff);
        if (!arcadeRepository.transactions) {
            updateStateAutoCommit(id, diff, conditionalUpdates);
            return;
        }
        if (transactionActive) {
            // the conditional update as it is now: its callback (run once, at the first attempt) advances it
            ConditionalUpdates replayConditions;
            if (conditionalUpdates == null) {
                replayConditions = null;
            } else {
                replayConditions = new ConditionalUpdates();
                replayConditions.put(new HashMap<>(conditionalUpdates.getConditions()),
                        new HashMap<>(conditionalUpdates.getUpdates()));
            }
            redoLog.add(() -> updateState(id, diff, replayConditions));
        }
        // lock before reading the current values the diffs apply to
        lockForWrite();
        var update = new StateUpdate(id, diff, conditionalUpdates);
        if (!update.diffs.isEmpty()) {
            var current = readPartialState(id, update.diffs.keySet());
            if (current == null) {
                throw new ConcurrentUpdateException("Missing: " + id);
            }
            update.applyDiffs(current);
        }
        var params = new HashMap<String, Object>();
        params.put("id", id);
        var sets = new ArrayList<String>();
        int i = 0;
        for (var en : update.values.entrySet()) {
            sets.add("%s = :s%d".formatted(name(en.getKey()), i));
            params.put("s" + i, converter.toArcade(en.getValue()));
            i++;
        }
        for (var en : update.deltas.entrySet()) {
            var n = name(en.getKey());
            sets.add("%s = ifnull(%s, :b%d) + :s%d".formatted(n, n, i, i));
            params.put("b" + i, en.getValue().getBase());
            params.put("s" + i, en.getValue().getDeltaValue());
            i++;
        }
        var where = new StringBuilder(name(KEY_ID) + " = :id");
        if (conditionalUpdates != null) {
            for (var en : conditionalUpdates.getConditions().entrySet()) {
                if (en.getValue() == null) {
                    where.append(" AND %s IS NULL".formatted(name(en.getKey())));
                } else {
                    where.append(" AND %s = :c%d".formatted(name(en.getKey()), i));
                    params.put("c" + i, converter.toArcade(en.getValue()));
                    i++;
                }
            }
        }
        if (!sets.isEmpty()) {
            var updated = count(command("UPDATE %s SET %s WHERE %s".formatted(DOC_TYPE, String.join(", ", sets),
                    where), params));
            if (updated != 1) {
                throw new ConcurrentUpdateException(id);
            }
        }
        updateEdges(id, update.values);
        if (conditionalUpdates != null) {
            conditionalUpdates.finish();
        }
    }

    /** Replaces the edges of the reference keys that were updated, in the current transaction. */
    protected void updateEdges(String id, Map<String, Serializable> values) {
        var params = new HashMap<String, Object>();
        for (var statement : edgeStatements(id, values, params)) {
            command(statement, params);
        }
    }

    /** The statements replacing the edges of the reference keys that were updated; their parameters are added. */
    protected List<String> edgeStatements(String id, Map<String, Serializable> values, Map<String, Object> params) {
        var statements = new ArrayList<String>();
        int i = 0;
        for (var en : REFERENCE_KEYS.entrySet()) {
            if (values.containsKey(en.getKey())) {
                statements.add("DELETE FROM %s WHERE @out IN (SELECT FROM %s WHERE %s = :eSrc)".formatted(
                        en.getValue(), DOC_TYPE, name(KEY_ID)));
                var target = values.get(en.getKey());
                if (target != null && !target.equals(id)) {
                    statements.add(createEdgeStatement(en.getValue(), ":eSrc", ":eDst" + i));
                    params.put("eDst" + i, target);
                }
                i++;
            }
        }
        if (!statements.isEmpty()) {
            params.put("eSrc", id);
        }
        return statements;
    }

    /** How many times an update whose compare-and-set failed is read and applied again. */
    protected static final int CAS_ATTEMPTS = 10;

    /**
     * An update without repository transactions, applied by the database where possible, like Nuxeo's MongoDB
     * repository: new values and increments of top-level keys ({@code $set}, {@code $inc}), and lists only appended to
     * or pulled from ({@code $push}, {@code $pullAll}: {@code p = ifnull(p, []) || :items},
     * {@code p = ifnull(p, []).removeAll(:items)}). ArcadeDB SQL can't set a deeper path ({@code a.b.c},
     * {@code a[0].b}): the other diffs are applied in Java onto the current value of their top-level key, written if
     * that value didn't change meanwhile ({@code WHERE p = :old}, compare-and-set), else read and applied again. The
     * change token conditions are in the same statement, so a concurrent change fails the save at once.
     */
    protected void updateStateAutoCommit(String id, StateDiff diff, ConditionalUpdates conditionalUpdates) {
        for (int attempt = 1; attempt <= CAS_ATTEMPTS; attempt++) {
            var update = new StateUpdate(id, diff, conditionalUpdates);
            var params = new HashMap<String, Object>();
            params.put("id", id);
            var sets = new ArrayList<String>();
            var where = new StringBuilder(name(KEY_ID) + " = :id");
            int i = 0;
            for (var key : List.copyOf(update.diffs.keySet())) {
                if (update.diffs.get(key) instanceof ListDiff listDiff && listDiff.diff == null
                        && (listDiff.rpush == null || listDiff.pull == null)) {
                    var n = name(key);
                    if (listDiff.rpush != null) {
                        sets.add("%s = ifnull(%s, []) || :s%d".formatted(n, n, i));
                        params.put("s" + i, converter.toArcade(listDiff.rpush));
                    } else {
                        sets.add("%s = ifnull(%s, []).removeAll(:s%d)".formatted(n, n, i));
                        params.put("s" + i, converter.toArcade(listDiff.pull));
                    }
                    i++;
                    update.diffs.remove(key);
                }
            }
            boolean compareAndSet = !update.diffs.isEmpty();
            if (compareAndSet) {
                var keys = List.copyOf(update.diffs.keySet());
                var columns = String.join(", ", keys.stream().map(ArcadeDBQueryTranslator::name).toList());
                var rows = query("SELECT %s FROM %s WHERE %s = :id".formatted(columns, DOC_TYPE, name(KEY_ID)),
                        Map.of("id", id));
                if (rows.isEmpty()) {
                    throw new ConcurrentUpdateException("Missing: " + id);
                }
                var raw = rows.get(0).toMap();
                update.applyDiffs(converter.mapToState(raw));
                for (var key : keys) {
                    var old = raw.get(key);
                    if (old == null) {
                        where.append(" AND %s IS NULL".formatted(name(key)));
                    } else {
                        where.append(" AND %s = :o%d".formatted(name(key), i));
                        params.put("o" + i, old);
                        i++;
                    }
                }
            }
            for (var en : update.values.entrySet()) {
                sets.add("%s = :s%d".formatted(name(en.getKey()), i));
                params.put("s" + i, converter.toArcade(en.getValue()));
                i++;
            }
            for (var en : update.deltas.entrySet()) {
                var n = name(en.getKey());
                sets.add("%s = ifnull(%s, :b%d) + :s%d".formatted(n, n, i, i));
                params.put("b" + i, en.getValue().getBase());
                params.put("s" + i, en.getValue().getDeltaValue());
                i++;
            }
            if (conditionalUpdates != null) {
                for (var en : conditionalUpdates.getConditions().entrySet()) {
                    if (en.getValue() == null) {
                        where.append(" AND %s IS NULL".formatted(name(en.getKey())));
                    } else {
                        where.append(" AND %s = :c%d".formatted(name(en.getKey()), i));
                        params.put("c" + i, converter.toArcade(en.getValue()));
                        i++;
                    }
                }
            }
            if (sets.isEmpty()) {
                return;
            }
            var statement = "UPDATE %s SET %s WHERE %s".formatted(DOC_TYPE, String.join(", ", sets), where);
            // the edges in the same script, replaced only if the document was updated
            var edges = edgeStatements(id, update.values, params);
            var results = edges.isEmpty() ? autoCommit(List.of(statement), params, true)
                    : autoCommitScript("LET u = %s; IF ($u[0].count = 1) { %s; } LET r = $u; ".formatted(statement,
                            String.join("; ", edges)), params, true);
            if (count(results) == 1) {
                if (conditionalUpdates != null) {
                    conditionalUpdates.finish();
                }
                return;
            }
            if (!compareAndSet) {
                // missing, or a concurrent change of its change token
                throw new ConcurrentUpdateException(id);
            }
            log.debug("ArcadeDB: concurrent change of {}, applying its diffs again (attempt {})", id, attempt);
        }
        throw new ConcurrentUpdateException(id);
    }

    @Override
    public void deleteStates(Set<String> ids) {
        log.trace("ArcadeDB: REMOVE {}", ids);
        if (transactionActive) {
            var copy = Set.copyOf(ids);
            redoLog.add(() -> deleteStates(copy));
        }
        lockForWrite();
        for (var batch : ListUtils.partition(List.copyOf(ids), PAGE_SIZE)) {
            // the edges of the deleted vertices are deleted too
            write("DELETE FROM %s WHERE %s IN :ids".formatted(DOC_TYPE, name(KEY_ID)), Map.of("ids", batch), false);
        }
        // locks are not transactional
        var lockKeys = ids.stream().map(arcadeRepository::lockKey).toList();
        arcadeRepository.withAutoCommit(adb -> adb.command("sql",
                "DELETE FROM %s WHERE %s IN :keys".formatted(LOCK_TYPE, LOCK_KEY), Map.of("keys", lockKeys)));
    }

    // ----- NXQL queries -----

    @Override
    protected GraphQueryTranslator<SqlCondition> newTranslator(DBSExpressionEvaluator evaluator) {
        return new ArcadeDBQueryTranslator(evaluator);
    }

    @Override
    protected Map<String, GraphScroll<SqlCondition>> getScrolls() {
        return arcadeRepository.scrolls;
    }

    protected String whereClause(SqlCondition where, String afterId, Map<String, Object> params) {
        var conditions = new ArrayList<String>();
        if (where != null) {
            conditions.add(where.sql());
            params.putAll(where.params());
        }
        if (afterId != null) {
            conditions.add(name(KEY_ID) + " > :afterId");
            params.put("afterId", afterId);
        }
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }

    @Override
    protected List<String> queryIds(SqlCondition where, List<SortKey> sort, String afterId, long offset,
            long limit) {
        var params = new LinkedHashMap<String, Object>();
        var sql = new StringBuilder("SELECT %s AS id FROM %s".formatted(name(KEY_ID), DOC_TYPE));
        sql.append(whereClause(where, afterId, params));
        sql.append(" ORDER BY ");
        for (var key : sort) {
            // like NXQL, ArcadeDB sorts missing values first in ascending order
            sql.append(name(key.key())).append(key.descending() ? " DESC, " : " ASC, ");
        }
        sql.append(name(KEY_ID));
        // page to stay below the 20,000 rows cap
        var ids = new ArrayList<String>();
        long position = offset;
        for (;;) {
            long pageSize = limit == 0 ? 10_000 : Math.min(10_000, limit - ids.size());
            var results = query(sql + " SKIP " + position + " LIMIT " + pageSize, params);
            results.forEach(r -> ids.add(r.getProperty("id")));
            position += results.size();
            if (results.size() < pageSize || (limit != 0 && ids.size() >= limit)) {
                return ids;
            }
        }
    }

    @Override
    protected long countIds(SqlCondition where, long upTo) {
        var params = new HashMap<String, Object>();
        var whereSql = whereClause(where, null, params);
        String sql;
        if (upTo > 0) {
            sql = "SELECT count(*) AS cnt FROM (SELECT FROM %s%s LIMIT %d)".formatted(DOC_TYPE, whereSql, upTo);
        } else {
            sql = "SELECT count(*) AS cnt FROM %s%s".formatted(DOC_TYPE, whereSql);
        }
        return count(query(sql, params));
    }

    // ----- locks (not transactional) -----

    /** Reads the lock information of a document: its lock document, or a lock set on the document itself. */
    protected Lock readLock(RemoteDatabase adb, String id, boolean[] onDocument) {
        var docs = adb.query("sql", "SELECT %s AS o, %s AS c FROM %s WHERE %s = :id".formatted(name(KEY_LOCK_OWNER),
                name(KEY_LOCK_CREATED), DOC_TYPE, name(KEY_ID)), Map.of("id", id)).stream().toList();
        if (docs.isEmpty()) {
            throw new DocumentNotFoundException(id);
        }
        var locks = adb.query("sql", "SELECT owner AS o, created AS c FROM %s WHERE %s = :key".formatted(LOCK_TYPE,
                LOCK_KEY), Map.of("key", arcadeRepository.lockKey(id))).stream().toList();
        Result row = null;
        if (!locks.isEmpty()) {
            row = locks.get(0);
        } else if (docs.get(0).getProperty("o") != null) {
            row = docs.get(0);
            onDocument[0] = true;
        }
        if (row == null) {
            return null;
        }
        var created = (Calendar) converter.fromArcade(KEY_LOCK_CREATED, row.getProperty("c"), DateType.INSTANCE);
        return new Lock(row.getProperty("o"), created);
    }

    @Override
    public Lock getLock(String id) {
        return arcadeRepository.withAutoCommit(adb -> readLock(adb, id, new boolean[1]));
    }

    @Override
    public Lock setLock(String id, Lock lock) {
        return arcadeRepository.withAutoCommit(adb -> {
            for (int attempt = 0;; attempt++) {
                var old = readLock(adb, id, new boolean[1]);
                if (old != null) {
                    return old;
                }
                var content = new HashMap<String, Object>();
                content.put(LOCK_KEY, arcadeRepository.lockKey(id));
                content.put("owner", lock.getOwner());
                content.put("created", converter.toArcade(lock.getCreated()));
                try {
                    adb.command("sql", "INSERT INTO %s CONTENT :c".formatted(LOCK_TYPE), Map.of("c", content));
                    return null;
                } catch (DuplicatedKeyException | NeedRetryException e) {
                    // locked concurrently: read again
                    if (attempt >= 5) {
                        throw new ConcurrentUpdateException(e.getMessage(), e);
                    }
                }
            }
        });
    }

    @Override
    public Lock removeLock(String id, String owner) {
        return arcadeRepository.withAutoCommit(adb -> {
            var onDocument = new boolean[1];
            var old = readLock(adb, id, onDocument);
            if (old == null) {
                return null;
            }
            if (!LockManager.canLockBeRemoved(old.getOwner(), owner)) {
                // existing mismatched lock, flag failure
                return new Lock(old.getOwner(), old.getCreated(), true);
            }
            if (onDocument[0]) {
                adb.command("sql", "UPDATE %s SET %s = null, %s = null WHERE %s = :id".formatted(DOC_TYPE,
                        name(KEY_LOCK_OWNER), name(KEY_LOCK_CREATED), name(KEY_ID)), Map.of("id", id));
            } else {
                adb.command("sql", "DELETE FROM %s WHERE %s = :key".formatted(LOCK_TYPE, LOCK_KEY),
                        Map.of("key", arcadeRepository.lockKey(id)));
            }
            return old;
        });
    }

}
