package org.integratedsemantics.nuxeo.graph.neo4j;

import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_ID;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_LOCK_CREATED;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_LOCK_OWNER;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_NAME;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_PARENT_ID;
import static org.integratedsemantics.nuxeo.graph.neo4j.Neo4jGraphMapper.fromNeo4j;
import static org.integratedsemantics.nuxeo.graph.neo4j.Neo4jGraphMapper.isComplex;
import static org.integratedsemantics.nuxeo.graph.neo4j.Neo4jGraphMapper.putSimple;
import static org.integratedsemantics.nuxeo.graph.neo4j.Neo4jGraphMapper.toNeo4j;
import static org.integratedsemantics.nuxeo.graph.neo4j.Neo4jRepository.LOCK_KEY;
import static org.integratedsemantics.nuxeo.graph.neo4j.Neo4jRepository.REFERENCE_KEYS;
import static org.integratedsemantics.nuxeo.graph.neo4j.Neo4jRepository.REL_CHILD_OF;
import static org.integratedsemantics.nuxeo.graph.neo4j.Neo4jRepository.REL_PROP;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.apache.commons.collections4.ListUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.neo4j.cypherdsl.core.Condition;
import org.neo4j.cypherdsl.core.Cypher;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Transaction;
import org.neo4j.driver.TransactionConfig;
import org.neo4j.driver.exceptions.Neo4jException;
import org.neo4j.driver.exceptions.TransientException;
import org.nuxeo.ecm.core.api.ConcurrentUpdateException;
import org.nuxeo.ecm.core.api.DocumentNotFoundException;
import org.nuxeo.ecm.core.api.Lock;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.lock.LockManager;
import org.nuxeo.ecm.core.storage.State;
import org.nuxeo.ecm.core.storage.State.StateDiff;
import org.nuxeo.ecm.core.storage.dbs.DBSConnection;
import org.nuxeo.ecm.core.storage.dbs.DBSExpressionEvaluator;
import org.nuxeo.ecm.core.storage.dbs.DBSTransactionState.ConditionalUpdates;
import org.integratedsemantics.nuxeo.graph.GraphConnectionBase;
import org.integratedsemantics.nuxeo.graph.GraphQueryTranslator;
import org.integratedsemantics.nuxeo.graph.GraphQueryTranslator.SortKey;
import org.integratedsemantics.nuxeo.graph.GraphScroll;
import org.integratedsemantics.nuxeo.graph.StateUpdate;
import org.integratedsemantics.nuxeo.graph.neo4j.Neo4jGraphMapper.ComplexRow;
import org.integratedsemantics.nuxeo.graph.neo4j.Neo4jGraphMapper.GraphChild;

/**
 * Neo4j implementation of a {@link DBSConnection}.
 * <p>
 * Documents and their complex properties are read and written in the connection's own Neo4j transaction started by
 * {@link #begin}, or through the repository's {@link Neo4jStatementExecutor} outside of Nuxeo transactions. Locks are not transactional: they are stored in separate lock nodes
 * written through an independent driver session (like VCS uses a separate lock table).
 *
 * @since 0.1.0
 */
public class Neo4jConnection extends GraphConnectionBase<Condition> {

    private static final Logger log = LogManager.getLogger(Neo4jConnection.class);

    /** Maximum number of ids passed to a single Cypher query. */
    protected static final int ID_BATCH_SIZE = 1000;

    protected final Neo4jRepository neo4jRepository;

    /** Label expression for document nodes. */
    protected final String docLabels;

    /** Label expression for complex nodes. */
    protected final String complexLabels;

    /** Label expression for lock nodes. */
    protected final String lockLabels;

    /**
     * Suffix reading the documents matched by a MATCH clause binding {@code d}, with their complex properties and
     * lock. Complex properties are restricted to the requested top-level keys unless {@code $allKeys} is true.
     */
    protected final String readStatesSuffix;

    /**
     * The connection's transaction, if {@link #begin} was called. It is an explicit driver transaction owned by the
     * connection (thread-bound transactions don't match Nuxeo's connection-scoped transactions).
     */
    protected Transaction tx;

    protected Session txSession;

    public Neo4jConnection(Neo4jRepository repository) {
        super(repository);
        neo4jRepository = repository;
        docLabels = repository.docLabels;
        complexLabels = repository.complexLabels;
        lockLabels = repository.lockLabels;
        readStatesSuffix = """
                 WITH DISTINCT d
                 OPTIONAL MATCH (d)-[r0:PROP]->(c0) WHERE $allKeys OR r0.key IN $keys
                 OPTIONAL MATCH (c0)-[:PROP*0..]->(p)-[r:PROP]->(c)
                 WITH d,
                   collect(DISTINCT CASE WHEN c0 IS NULL THEN NULL ELSE
                     {parent: elementId(d), key: r0.key, pos: r0.pos, eid: elementId(c0), props: properties(c0)} END)
                   + collect(CASE WHEN c IS NULL THEN NULL ELSE
                     {parent: elementId(p), key: r.key, pos: r.pos, eid: elementId(c), props: properties(c)} END) AS cx
                 OPTIONAL MATCH (l%s {%s: $lockPrefix + d.`%s`})
                 RETURN elementId(d) AS eid, properties(d) AS props, cx, l.owner AS lockOwner,
                   l.created AS lockCreated
                """.formatted(lockLabels, LOCK_KEY, KEY_ID);
    }

    /** Escapes a property key for Cypher: {@code d.`ecm:id`}. */
    protected static String prop(String var, String key) {
        return var + ".`" + key.replace("`", "``") + "`";
    }

    @Override
    public void begin() {
        if (tx != null) {
            throw new NuxeoException("Transaction already started");
        }
        txSession = neo4jRepository.openSession();
        // metadata visible in SHOW TRANSACTIONS, to diagnose long-running or leaked transactions
        var origin = StackWalker.getInstance()
                                .walk(frames -> frames.map(f -> f.getClassName() + "." + f.getMethodName())
                                                      .filter(f -> f.startsWith("org.nuxeo")
                                                              && !f.contains(".storage.neo4j."))
                                                      .limit(12)
                                                      .toList());
        tx = txSession.beginTransaction(TransactionConfig.builder()
                                                         .withTimeout(neo4jRepository.transactionTimeout)
                                                         .withMetadata(Map.of("thread",
                                                                 Thread.currentThread().getName(), "origin",
                                                                 String.join(" < ", origin)))
                                                         .build());
    }

    @Override
    public void commit() {
        if (tx == null) {
            return;
        }
        try {
            tx.commit();
        } catch (RuntimeException e) {
            // Nuxeo commits after its transaction completed (afterCompletion): the exception goes no further
            log.error("Neo4j: transaction commit failed, its changes are lost: {}", e.getMessage(), e);
            throw translate(e);
        } finally {
            closeTransaction();
        }
    }

    @Override
    public void rollback() {
        if (tx == null) {
            return;
        }
        try {
            tx.rollback();
        } catch (RuntimeException e) {
            log.debug("Rollback failed", e);
        } finally {
            closeTransaction();
        }
    }

    protected void closeTransaction() {
        try {
            tx.close();
            txSession.close();
        } catch (RuntimeException e) {
            log.debug("Cannot close transaction", e);
        } finally {
            tx = null;
            txSession = null;
        }
    }

    @Override
    public void close() {
        if (tx != null) {
            log.warn("Closing connection with an active transaction, rolling back");
            rollback();
        }
    }

    @Override
    public String generateNewId() {
        return neo4jRepository.generateNewId();
    }

    /**
     * Translates database errors meaning a concurrent update into {@link ConcurrentUpdateException}. The driver
     * exceptions are looked for in the causes, as other {@link Neo4jStatementExecutor}s may wrap them.
     */
    protected static RuntimeException translate(RuntimeException e) {
        if (e instanceof NuxeoException) {
            return e;
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof TransientException || String.valueOf(t.getMessage()).contains("EntityNotFound")
                    || (t instanceof Neo4jException ne && ne.code() != null
                            && ne.code().contains("ConstraintValidationFailed"))) {
                return new ConcurrentUpdateException(e.getMessage(), e);
            }
        }
        return e;
    }

    /**
     * Runs a query: in the connection's transaction if any, otherwise through the repository's
     * {@link Neo4jStatementExecutor} (auto-commit, or the unit of work of {@link Neo4jRepository#inTransaction}).
     */
    protected List<Map<String, Object>> rows(String cypher, Map<String, Object> params) {
        if (tx != null) {
            return tx.run(cypher, params).list(Record::asMap);
        }
        return neo4jRepository.executor.rows(cypher, params);
    }

    /** Runs a query without result, see {@link #rows}. */
    protected void exec(String cypher, Map<String, Object> params) {
        if (tx != null) {
            tx.run(cypher, params).consume();
        } else {
            neo4jRepository.executor.rows(cypher, params);
        }
    }

    /** Runs a write atomically (in the connection's transaction, or a new one), translating concurrency errors. */
    protected <T> T write(Supplier<T> supplier) {
        try {
            return tx != null ? supplier.get() : neo4jRepository.inTransaction(supplier);
        } catch (RuntimeException e) {
            throw translate(e);
        }
    }

    // ----- reads -----

    /**
     * Reads the documents matched by a Cypher MATCH clause binding {@code d}.
     *
     * @param match the MATCH clause, including its WHERE
     * @param params the parameters of the MATCH clause
     * @param keys the keys to read in addition to the id, or {@code null} for all keys
     */
    protected List<State> readStates(String match, Map<String, Object> params, Collection<String> keys) {
        var allParams = new HashMap<>(params);
        allParams.put("allKeys", keys == null);
        allParams.put("keys", keys == null ? List.of() : List.copyOf(keys));
        allParams.put("lockPrefix", neo4jRepository.lockKey(""));
        var cypher = match + "\n" + readStatesSuffix;
        log.trace("Neo4j: READ {} {}", cypher, params);
        var states = new ArrayList<State>();
        for (var row : rows(cypher, allParams)) {
            var state = rowToState(row);
            if (keys != null) {
                var partial = new State();
                partial.put(KEY_ID, state.get(KEY_ID));
                for (var key : keys) {
                    partial.put(key, state.get(key));
                }
                state = partial;
            }
            states.add(state);
        }
        return states;
    }

    @SuppressWarnings("unchecked")
    protected static State rowToState(Map<String, Object> row) {
        var cx = (List<Map<String, Object>>) row.get("cx");
        var rows = new ArrayList<ComplexRow>(cx.size());
        for (var c : cx) {
            var pos = (Number) c.get("pos");
            rows.add(new ComplexRow((String) c.get("parent"), (String) c.get("key"),
                    pos == null ? null : pos.longValue(), (String) c.get("eid"), (Map<String, Object>) c.get("props")));
        }
        var state = Neo4jGraphMapper.fromGraph((String) row.get("eid"), (Map<String, Object>) row.get("props"), rows);
        var lockOwner = row.get("lockOwner");
        if (lockOwner != null) {
            state.put(KEY_LOCK_OWNER, (String) lockOwner);
            state.put(KEY_LOCK_CREATED, fromNeo4j(row.get("lockCreated")));
        }
        return state;
    }

    protected String matchDocs(String where) {
        return "MATCH (d%s) WHERE %s".formatted(docLabels, where);
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
        var states = readStates(matchDocs(prop("d", KEY_ID) + " = $id"), Map.of("id", id), keys);
        return states.isEmpty() ? null : states.get(0);
    }

    @Override
    public List<State> readStates(List<String> ids) {
        var states = new ArrayList<State>(ids.size());
        for (var batch : ListUtils.partition(ids, ID_BATCH_SIZE)) {
            states.addAll(readStates(matchDocs(prop("d", KEY_ID) + " IN $ids"), Map.of("ids", batch), null));
        }
        return states;
    }

    @Override
    public State readChildState(String parentId, String name, Set<String> ignored) {
        var states = readStates(
                matchDocs("%s = $parentId AND %s = $name AND NOT %s IN $ignored".formatted(prop("d", KEY_PARENT_ID),
                        prop("d", KEY_NAME), prop("d", KEY_ID))),
                Map.of("parentId", parentId, "name", name, "ignored", List.copyOf(ignored)), null);
        return states.isEmpty() ? null : states.get(0);
    }

    @Override
    public boolean hasChild(String parentId, String name, Set<String> ignored) {
        return exists("%s = $parentId AND %s = $name AND NOT %s IN $ignored".formatted(prop("d", KEY_PARENT_ID),
                prop("d", KEY_NAME), prop("d", KEY_ID)),
                Map.of("parentId", parentId, "name", name, "ignored", List.copyOf(ignored)));
    }

    protected boolean exists(String where, Map<String, Object> params) {
        return !rows(matchDocs(where) + " RETURN 1 LIMIT 1", params).isEmpty();
    }

    @Override
    public List<State> queryKeyValue(String key, Object value, Set<String> ignored) {
        log.trace("Neo4j: QUERY {} = {}", key, value);
        return readStates(matchDocs("%s = $value AND NOT %s IN $ignored".formatted(prop("d", key), prop("d", KEY_ID))),
                Map.of("value", toNeo4j(value), "ignored", List.copyOf(ignored)), null);
    }

    @Override
    public List<State> queryKeyValue(String key1, Object value1, String key2, Object value2, Set<String> ignored) {
        log.trace("Neo4j: QUERY {} = {} AND {} = {}", key1, value1, key2, value2);
        return readStates(matchDocs("%s = $value1 AND %s = $value2 AND NOT %s IN $ignored".formatted(prop("d", key1),
                prop("d", key2), prop("d", KEY_ID))),
                Map.of("value1", toNeo4j(value1), "value2", toNeo4j(value2), "ignored", List.copyOf(ignored)), null);
    }

    @Override
    public List<State> queryKeyValueWithOperator(String key1, Object value1, String key2, DBSQueryOperator operator,
            Object value2, Set<String> ignored) {
        var values = ((Collection<?>) value2).stream().map(Neo4jGraphMapper::toNeo4j).toList();
        var not = switch (operator) {
            case IN -> "";
            case NOT_IN -> "NOT ";
        };
        return readStates(matchDocs("%s = $value1 AND %s%s IN $values AND NOT %s IN $ignored".formatted(
                prop("d", key1), not, prop("d", key2), prop("d", KEY_ID))),
                Map.of("value1", toNeo4j(value1), "values", values, "ignored", List.copyOf(ignored)), null);
    }

    @Override
    public boolean queryKeyValuePresence(String key, String value, Set<String> ignored) {
        return exists("%s = $value AND NOT %s IN $ignored".formatted(prop("d", key), prop("d", KEY_ID)),
                Map.of("value", value, "ignored", List.copyOf(ignored)));
    }

    @Override
    public Stream<State> getDescendants(String rootId, Set<String> keys) {
        return getDescendants(rootId, keys, 0);
    }

    /**
     * Uses a graph traversal along {@code CHILD_OF} relationships.
     */
    @Override
    public Stream<State> getDescendants(String rootId, Set<String> keys, int limit) {
        var match = "MATCH (root%s {`%s`: $id})<-[:%s*1..]-(d%s)".formatted(docLabels, KEY_ID, REL_CHILD_OF,
                docLabels);
        if (limit != 0) {
            match += " WITH d LIMIT " + limit;
        }
        return readStates(match, Map.of("id", rootId), keys).stream();
    }

    // ----- writes -----

    /** A complex child to create under an existing node. */
    protected record PendingChild(String parentEid, GraphChild child) {
    }

    @Override
    public void createState(State state) {
        createStates(List.of(state));
    }

    @Override
    public void createStates(List<State> states) {
        log.trace("Neo4j: CREATE {}", states);
        write(() -> {
            doCreateStates(states);
            return null;
        });
    }

    protected void doCreateStates(List<State> states) {
        var graphs = states.stream().map(Neo4jGraphMapper::toGraph).toList();
        var docs = new ArrayList<Map<String, Object>>(graphs.size());
        for (int i = 0; i < graphs.size(); i++) {
            docs.add(Map.of("tmp", (long) i, "props", graphs.get(i).props()));
        }
        var eids = createNodes("""
                UNWIND $nodes AS n
                CREATE (d%s)
                SET d = n.props
                RETURN n.tmp AS tmp, elementId(d) AS eid
                """.formatted(docLabels), docs);
        var pending = new ArrayList<PendingChild>();
        for (int i = 0; i < graphs.size(); i++) {
            for (var child : graphs.get(i).children()) {
                pending.add(new PendingChild(eids[i], child));
            }
        }
        createComplexChildren(pending);
        // reference relationships are created once all the documents of the batch exist
        for (var en : REFERENCE_KEYS.entrySet()) {
            var refs = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < states.size(); i++) {
                var state = states.get(i);
                var target = state.get(en.getKey());
                if (target != null && !target.equals(state.get(KEY_ID))) {
                    refs.add(Map.of("from", eids[i], "to", target));
                }
            }
            if (!refs.isEmpty()) {
                exec("""
                        UNWIND $refs AS r
                        MATCH (a) WHERE elementId(a) = r.from
                        MATCH (b%s {`%s`: r.to})
                        CREATE (a)-[:%s]->(b)
                        """.formatted(docLabels, KEY_ID, en.getValue()), Map.of("refs", refs));
            }
        }
    }

    /**
     * Runs a node creation query over {@code $nodes} returning {@code tmp} and {@code eid}, and returns the created
     * element ids indexed by {@code tmp}.
     */
    protected String[] createNodes(String cypher, List<Map<String, Object>> nodes) {
        var eids = new String[nodes.size()];
        for (var row : rows(cypher, Map.of("nodes", nodes))) {
            eids[((Number) row.get("tmp")).intValue()] = (String) row.get("eid");
        }
        return eids;
    }

    /**
     * Creates complex children, level by level (one query per depth level, whatever the number of documents).
     */
    protected void createComplexChildren(List<PendingChild> level) {
        var cypher = """
                UNWIND $nodes AS n
                MATCH (p) WHERE elementId(p) = n.parent
                CREATE (p)-[r:%s]->(c%s)
                SET r.key = n.key, r.pos = n.pos, c = n.props
                RETURN n.tmp AS tmp, elementId(c) AS eid
                """.formatted(REL_PROP, complexLabels);
        while (!level.isEmpty()) {
            var nodes = new ArrayList<Map<String, Object>>(level.size());
            for (int i = 0; i < level.size(); i++) {
                var pending = level.get(i);
                var node = new HashMap<String, Object>();
                node.put("tmp", (long) i);
                node.put("parent", pending.parentEid());
                node.put("key", pending.child().key());
                node.put("pos", pending.child().pos());
                node.put("props", pending.child().node().props());
                nodes.add(node);
            }
            var eids = createNodes(cypher, nodes);
            var next = new ArrayList<PendingChild>();
            for (int i = 0; i < level.size(); i++) {
                for (var child : level.get(i).child().node().children()) {
                    next.add(new PendingChild(eids[i], child));
                }
            }
            level = next;
        }
    }

    @Override
    public void updateState(String id, StateDiff diff, ConditionalUpdates conditionalUpdates) {
        log.trace("Neo4j: UPDATE {}: {}", id, diff);
        write(() -> {
            doUpdateState(id, diff, conditionalUpdates);
            return null;
        });
    }

    @SuppressWarnings("unchecked")
    protected void doUpdateState(String id, StateDiff diff, ConditionalUpdates conditionalUpdates) {
        // lock the node for the rest of the transaction and read its simple properties
        var lockRows = rows("""
                MATCH (d%s {`%s`: $id})
                SET d.__nxlock = true REMOVE d.__nxlock
                RETURN elementId(d) AS eid, properties(d) AS props
                """.formatted(docLabels, KEY_ID), Map.of("id", id));
        if (lockRows.isEmpty()) {
            throw new ConcurrentUpdateException("Missing: " + id);
        }
        var eid = (String) lockRows.get(0).get("eid");
        var props = (Map<String, Object>) lockRows.get(0).get("props");
        if (conditionalUpdates != null) {
            for (var en : conditionalUpdates.getConditions().entrySet()) {
                if (!Objects.equals(fromNeo4j(props.get(en.getKey())), en.getValue())) {
                    throw new ConcurrentUpdateException(id);
                }
            }
        }
        var update = new StateUpdate(id, diff, conditionalUpdates);
        if (!update.diffs.isEmpty()) {
            var current = readPartialState(id, update.diffs.keySet());
            if (current == null) {
                throw new ConcurrentUpdateException("Missing: " + id);
            }
            update.applyDiffs(current);
        }
        // split the new values into node properties and complex children
        var set = new HashMap<String, Object>();
        var complex = new HashMap<String, Serializable>();
        update.values.forEach((key, value) -> {
            if (value == null) {
                // we don't know if it was a simple or complex value, remove both
                putSimple(set, key, null);
                complex.put(key, null);
            } else if (isComplex(value)) {
                complex.put(key, value);
            } else {
                putSimple(set, key, value);
            }
        });
        var params = new HashMap<String, Object>();
        params.put("eid", eid);
        if (!set.isEmpty()) {
            params.put("set", set);
            exec("MATCH (d) WHERE elementId(d) = $eid SET d += $set", params);
        }
        if (!update.deltas.isEmpty()) {
            var sets = new ArrayList<String>();
            int i = 0;
            for (var en : update.deltas.entrySet()) {
                var p = prop("d", en.getKey());
                sets.add("%s = CASE WHEN %s IS NULL THEN $full%d ELSE %s + $delta%d END".formatted(p, p, i, p, i));
                params.put("full" + i, en.getValue().getFullValue());
                params.put("delta" + i, en.getValue().getDeltaValue());
                i++;
            }
            exec("MATCH (d) WHERE elementId(d) = $eid SET " + String.join(", ", sets), params);
        }
        if (!complex.isEmpty()) {
            // delete the old children, create the new ones
            params.put("keys", List.copyOf(complex.keySet()));
            exec("""
                    MATCH (d)-[r:%s]->(c) WHERE elementId(d) = $eid AND r.key IN $keys
                    MATCH (c)-[:%s*0..]->(x)
                    WITH collect(DISTINCT x) AS xs
                    FOREACH (n IN xs | DETACH DELETE n)
                    """.formatted(REL_PROP, REL_PROP), params);
            var pending = new ArrayList<PendingChild>();
            complex.forEach((key, value) -> {
                if (value != null) {
                    Neo4jGraphMapper.toGraphChildren(key, value)
                                    .forEach(child -> pending.add(new PendingChild(eid, child)));
                }
            });
            createComplexChildren(pending);
        }
        // reference relationships
        for (var en : REFERENCE_KEYS.entrySet()) {
            if (update.values.containsKey(en.getKey())) {
                var refParams = new HashMap<String, Object>();
                refParams.put("eid", eid);
                refParams.put("to", update.values.get(en.getKey()));
                exec("""
                        MATCH (a) WHERE elementId(a) = $eid
                        OPTIONAL MATCH (a)-[r:%s]->()
                        DELETE r
                        WITH DISTINCT a
                        OPTIONAL MATCH (b%s {`%s`: $to})
                        FOREACH (_ IN CASE WHEN b IS NULL OR b = a THEN [] ELSE [1] END | CREATE (a)-[:%s]->(b))
                        """.formatted(en.getValue(), docLabels, KEY_ID, en.getValue()), refParams);
            }
        }
        if (conditionalUpdates != null) {
            conditionalUpdates.finish();
        }
    }

    @Override
    public void deleteStates(Set<String> ids) {
        log.trace("Neo4j: REMOVE {}", ids);
        write(() -> {
            for (var batch : ListUtils.partition(List.copyOf(ids), ID_BATCH_SIZE)) {
                // deleting a proxy or version also updates its target (ecm:proxyIds...): lock the targets first, in
                // a consistent order, otherwise concurrent deletions deadlock on the node and its relationships
                exec("""
                        MATCH (d%s)-[:%s|%s]->(t) WHERE d.`%s` IN $ids
                        WITH DISTINCT t ORDER BY elementId(t)
                        SET t.__nxlock = true REMOVE t.__nxlock
                        """.formatted(docLabels, Neo4jRepository.REL_PROXY_OF, Neo4jRepository.REL_VERSION_OF,
                        KEY_ID), Map.of("ids", batch));
                exec("""
                        MATCH (d%s) WHERE d.`%s` IN $ids
                        OPTIONAL MATCH (d)-[:%s*]->(c)
                        WITH collect(DISTINCT d) + collect(DISTINCT c) AS ns
                        FOREACH (n IN ns | DETACH DELETE n)
                        """.formatted(docLabels, KEY_ID, REL_PROP), Map.of("ids", batch));
            }
            return null;
        });
        // locks are not transactional
        try (var session = neo4jRepository.openSession()) {
            var lockKeys = ids.stream().map(neo4jRepository::lockKey).toList();
            session.executeWriteWithoutResult(tx -> tx.run("MATCH (l%s) WHERE l.%s IN $keys DELETE l".formatted(
                    lockLabels, LOCK_KEY), Map.of("keys", lockKeys)));
        }
    }

    // ----- NXQL queries -----

    @Override
    protected GraphQueryTranslator<Condition> newTranslator(DBSExpressionEvaluator evaluator) {
        return new Neo4jQueryTranslator(neo4jRepository, evaluator);
    }

    @Override
    protected Map<String, GraphScroll<Condition>> getScrolls() {
        return neo4jRepository.scrolls;
    }

    /**
     * Renders {@code MATCH (d) WHERE ...} for a condition, collecting its parameters.
     */
    protected String renderMatch(Condition where, String afterId, Map<String, Object> params) {
        var d = Neo4jQueryTranslator.documentNode(neo4jRepository);
        var condition = where == null ? Cypher.noCondition() : where;
        if (afterId != null) {
            condition = condition.and(d.property(KEY_ID).gt(Cypher.parameter("afterId", afterId)));
        }
        var statement = Cypher.match(d).where(condition).returning(d).build();
        params.putAll(statement.getCatalog().getParameters());
        var cypher = statement.getCypher();
        return cypher.substring(0, cypher.lastIndexOf(" RETURN "));
    }

    @Override
    protected List<String> queryIds(Condition where, List<SortKey> sort, String afterId, long offset, long limit) {
        var params = new HashMap<String, Object>();
        var cypher = new StringBuilder(renderMatch(where, afterId, params));
        cypher.append(" RETURN ").append(prop("d", KEY_ID)).append(" AS id ORDER BY ");
        for (var key : sort) {
            var p = prop("d", key.key());
            // NXQL sorts nulls first, Cypher sorts them last
            cypher.append(p).append(key.descending() ? " IS NULL ASC, " : " IS NULL DESC, ");
            cypher.append(p).append(key.descending() ? " DESC, " : " ASC, ");
        }
        cypher.append(prop("d", KEY_ID));
        if (offset > 0) {
            cypher.append(" SKIP $offset");
            params.put("offset", offset);
        }
        if (limit > 0) {
            cypher.append(" LIMIT $limit");
            params.put("limit", limit);
        }
        log.debug("Neo4j: QUERY {} {}", cypher, params);
        return rows(cypher.toString(), params).stream().map(row -> (String) row.get("id")).toList();
    }

    @Override
    protected long countIds(Condition where, long upTo) {
        var params = new HashMap<String, Object>();
        var cypher = new StringBuilder(renderMatch(where, null, params));
        if (upTo > 0) {
            cypher.append(" WITH d LIMIT $upTo");
            params.put("upTo", upTo);
        }
        cypher.append(" RETURN count(d) AS c");
        log.debug("Neo4j: COUNT {} {}", cypher, params);
        return ((Number) rows(cypher.toString(), params).get(0).get("c")).longValue();
    }

    // ----- locks (not transactional) -----

    protected Map<String, Object> lockParams(String id) {
        var params = new HashMap<String, Object>();
        params.put("id", id);
        params.put("key", neo4jRepository.lockKey(id));
        return params;
    }

    /** Reads the lock of a document; a lock may also be on the document itself if it was set before its creation. */
    protected String readLockCypher() {
        return """
                MATCH (d%s {`%s`: $id})
                OPTIONAL MATCH (l%s {%s: $key})
                RETURN l.owner AS owner, l.created AS created, d.`%s` AS docOwner, d.`%s` AS docCreated
                """.formatted(docLabels, KEY_ID, lockLabels, LOCK_KEY, KEY_LOCK_OWNER, KEY_LOCK_CREATED);
    }

    protected static Lock currentLock(Map<String, Object> row) {
        if (row.get("owner") != null) {
            return new Lock((String) row.get("owner"), (Calendar) fromNeo4j(row.get("created")));
        } else if (row.get("docOwner") != null) {
            return new Lock((String) row.get("docOwner"), (Calendar) fromNeo4j(row.get("docCreated")));
        }
        return null;
    }

    /**
     * Reads the lock information of a document in a read transaction: write transactions use locking unique index
     * seeks, which would wait for the uncommitted creation of the document by the caller's own transaction.
     */
    protected Map<String, Object> readLockRow(org.neo4j.driver.Session session, String id) {
        var row = session.executeRead(tx -> {
            var result = tx.run(readLockCypher(), lockParams(id));
            return result.hasNext() ? result.next().asMap() : null;
        });
        if (row == null) {
            throw new DocumentNotFoundException(id);
        }
        return row;
    }

    @Override
    public Lock getLock(String id) {
        try (var session = neo4jRepository.openSession()) {
            return currentLock(readLockRow(session, id));
        }
    }

    @Override
    public Lock setLock(String id, Lock lock) {
        var params = lockParams(id);
        params.put("owner", lock.getOwner());
        params.put("created", toNeo4j(lock.getCreated()));
        try (var session = neo4jRepository.openSession()) {
            var old = currentLock(readLockRow(session, id));
            if (old != null) {
                return old;
            }
            return session.executeWrite(tx -> {
                // the unique constraint on the lock key makes MERGE atomic
                var row = tx.run("""
                        MERGE (l%s {%s: $key})
                          ON CREATE SET l.owner = $owner, l.created = $created, l.__new = true
                        WITH l, l.__new AS isNew
                        REMOVE l.__new
                        RETURN isNew, l.owner AS owner, l.created AS created
                        """.formatted(lockLabels, LOCK_KEY), params).single().asMap();
                return Boolean.TRUE.equals(row.get("isNew")) ? null : currentLock(row);
            });
        }
    }

    @Override
    public Lock removeLock(String id, String owner) {
        try (var session = neo4jRepository.openSession()) {
            var row = readLockRow(session, id);
            var old = currentLock(row);
            if (old == null) {
                return null;
            }
            if (!LockManager.canLockBeRemoved(old.getOwner(), owner)) {
                // existing mismatched lock, flag failure
                return new Lock(old.getOwner(), old.getCreated(), true);
            }
            var params = lockParams(id);
            session.executeWriteWithoutResult(tx -> {
                if (row.get("owner") != null) {
                    tx.run("MATCH (l%s {%s: $key}) DELETE l".formatted(lockLabels, LOCK_KEY), params);
                } else {
                    // lock set on the document before its creation (the document is committed by now)
                    tx.run("MATCH (d%s {`%s`: $id}) REMOVE d.`%s`, d.`%s`".formatted(docLabels, KEY_ID,
                            KEY_LOCK_OWNER, KEY_LOCK_CREATED), params);
                }
            });
            return old;
        }
    }

}
