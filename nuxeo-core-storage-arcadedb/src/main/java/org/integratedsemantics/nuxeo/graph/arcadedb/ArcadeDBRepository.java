package org.integratedsemantics.nuxeo.graph.arcadedb;

import static org.integratedsemantics.nuxeo.graph.arcadedb.ArcadeDBQueryTranslator.name;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_ANCESTOR_IDS;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_BLOB_KEYS;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_ID;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_PARENT_ID;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_PRIMARY_TYPE;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_PROXY_TARGET_ID;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_VERSION_SERIES_ID;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.core.model.Repository;
import org.nuxeo.ecm.core.storage.dbs.DBSRepositoryBase;
import org.nuxeo.ecm.core.storage.dbs.DBSRepositoryDescriptor;
import org.integratedsemantics.nuxeo.graph.GraphScroll;
import org.nuxeo.runtime.api.Framework;

import com.arcadedb.remote.RemoteDatabase;

/**
 * ArcadeDB implementation of a {@link org.nuxeo.ecm.core.storage.dbs.DBSRepository}, using the ArcadeDB Java remote
 * API.
 * <p>
 * Each document is one {@code NxDocument} vertex holding the whole document state, complex values being embedded maps
 * and lists (like in MongoDB). The hierarchy, proxies and versions are also materialized as {@code CHILD_OF},
 * {@code PROXY_OF} and {@code VERSION_OF} edges. Locks are {@code NxLock} documents, written outside of transactions.
 * The ArcadeDB database is expected to be dedicated to the repository.
 *
 * @since 0.1.0
 */
public class ArcadeDBRepository extends DBSRepositoryBase {

    private static final Logger log = LogManager.getLogger(ArcadeDBRepository.class);

    /** nuxeo.conf property for the ArcadeDB server host. */
    public static final String HOST_PROPERTY = "nuxeo.arcadedb.host";

    /** nuxeo.conf property for the ArcadeDB server HTTP port. */
    public static final String PORT_PROPERTY = "nuxeo.arcadedb.port";

    /** nuxeo.conf property for the ArcadeDB database (dedicated to the repository). */
    public static final String DATABASE_PROPERTY = "nuxeo.arcadedb.database";

    /** nuxeo.conf property for the ArcadeDB user name. */
    public static final String USERNAME_PROPERTY = "nuxeo.arcadedb.username";

    /** nuxeo.conf property for the ArcadeDB password. */
    public static final String PASSWORD_PROPERTY = "nuxeo.arcadedb.password";

    /**
     * nuxeo.conf property: the {@link WriteLockMode}, {@code always} (default: writers serialized with an ArcadeDB type
     * lock, taken at the first write of a transaction), {@code fallback} or {@code never} (optimistic transactions).
     */
    public static final String WRITE_LOCK_PROPERTY = "nuxeo.arcadedb.writeLock";

    /**
     * nuxeo.conf property: with {@code nuxeo.arcadedb.writeLock=fallback}, how many times a conflicting transaction is
     * replayed without the lock before it is replayed holding it (default 3).
     */
    public static final String OPTIMISTIC_REPLAYS_PROPERTY = "nuxeo.arcadedb.optimisticReplays";

    /**
     * nuxeo.conf property: whether a Nuxeo transaction is an ArcadeDB transaction (default {@code false}). Without,
     * like Nuxeo's MongoDB repository, each write commits on its own (DBS rolls back with an undo log), and updates are
     * applied by the database where possible, see {@link ArcadeDBConnection#updateStateAutoCommit}.
     */
    public static final String TRANSACTIONS_PROPERTY = "nuxeo.arcadedb.transactions";

    /** nuxeo.conf property for the number of buckets of the document type (spreads concurrent inserts). */
    public static final String BUCKETS_PROPERTY = "nuxeo.arcadedb.buckets";

    public static final String DOC_TYPE = "NxDocument";

    public static final String LOCK_TYPE = "NxLock";

    public static final String LOCK_KEY = "lockKey";

    public static final String EDGE_CHILD_OF = "CHILD_OF";

    public static final String EDGE_PROXY_OF = "PROXY_OF";

    public static final String EDGE_VERSION_OF = "VERSION_OF";

    /** The document keys that are also materialized as edges, and the edge type to use. */
    public static final Map<String, String> REFERENCE_KEYS = Map.of( //
            KEY_PARENT_ID, EDGE_CHILD_OF, //
            KEY_PROXY_TARGET_ID, EDGE_PROXY_OF, //
            KEY_VERSION_SERIES_ID, EDGE_VERSION_OF);

    protected static final List<String> INDEXED_KEYS = List.of(KEY_PARENT_ID, KEY_PRIMARY_TYPE, KEY_PROXY_TARGET_ID,
            KEY_VERSION_SERIES_ID);

    // for debug
    private final AtomicLong temporaryIdCounter = new AtomicLong(0);

    protected final String host;

    protected final int port;

    protected final String database;

    protected final String username;

    protected final String password;

    protected final ArcadeDBConverter converter = new ArcadeDBConverter();

    protected final WriteLockMode writeLock;

    protected final int optimisticReplays;

    protected final boolean transactions;

    /** Idle remote database clients (they are not thread-safe, a client is used by one connection at a time). */
    protected final ConcurrentLinkedDeque<RemoteDatabase> pool = new ConcurrentLinkedDeque<>();

    /** Open scrolls, by scroll id. */
    protected final Map<String, GraphScroll<SqlCondition>> scrolls = new ConcurrentHashMap<>();

    public ArcadeDBRepository(DBSRepositoryDescriptor descriptor) {
        super(descriptor.name, descriptor);
        host = Framework.getProperty(HOST_PROPERTY, "localhost");
        port = Integer.parseInt(Framework.getProperty(PORT_PROPERTY, "2480"));
        database = Framework.getProperty(DATABASE_PROPERTY, "nuxeo");
        username = Framework.getProperty(USERNAME_PROPERTY, "root");
        password = Framework.getProperty(PASSWORD_PROPERTY, "");
        writeLock = WriteLockMode.of(Framework.getProperty(WRITE_LOCK_PROPERTY));
        optimisticReplays = Integer.parseInt(Framework.getProperty(OPTIMISTIC_REPLAYS_PROPERTY, "3"));
        transactions = Framework.isBooleanPropertyTrue(TRANSACTIONS_PROPERTY);
        log.info("Repository: {} connecting to ArcadeDB at: {}:{}/{}", repositoryName, host, port, database);
        // all the blob keys are stored in ecm:blobKeys
        capabilities.put(Repository.CAPABILITY_QUERY_BLOB_KEYS, Boolean.TRUE);
        initSchema();
        try (var connection = getConnection()) {
            if (connection.readState(connection.getRootId()) == null) {
                connection.initRoot();
            }
        }
    }

    protected RemoteDatabase newRemoteDatabase() {
        return new RemoteDatabase(host, port, database, username, password);
    }

    /** Borrows a remote database client from the pool. */
    protected RemoteDatabase borrow() {
        var db = pool.pollFirst();
        return db != null ? db : newRemoteDatabase();
    }

    /** Returns a remote database client to the pool. */
    protected void release(RemoteDatabase db) {
        if (db.isTransactionActive()) {
            try {
                db.rollback();
            } catch (RuntimeException e) {
                log.debug("Cannot rollback", e);
            }
        }
        pool.offerFirst(db);
    }

    /** Executes some code with a client that is not in a transaction (each command is auto-committed). */
    protected <T> T withAutoCommit(Function<RemoteDatabase, T> function) {
        var db = borrow();
        try {
            return function.apply(db);
        } finally {
            release(db);
        }
    }

    /**
     * Creates the types and indexes if missing, and disables super-node promotion: since 26.8.1, ArcadeDB moves the
     * edges of a vertex with more than {@code arcadedb.graph.supernodeThreshold} (4096) edges (a folder with that many
     * children) to stripe buckets that the {@code LOCK TYPE} write lock doesn't cover, and a transaction can hold one
     * lock statement only: every later write touching it would fail.
     */
    protected void initSchema() {
        var buckets = Integer.parseInt(Framework.getProperty(BUCKETS_PROPERTY, "8"));
        var db = newRemoteDatabase();
        try {
            try {
                db.command("sql", "ALTER DATABASE `arcadedb.graph.supernodeThreshold` 0");
            } catch (RuntimeException e) {
                log.warn("Cannot disable super-node promotion (set arcadedb.graph.supernodeThreshold=0 on the "
                        + "server): {}", e.getMessage());
            }
            db.command("sql", "CREATE VERTEX TYPE %s IF NOT EXISTS BUCKETS %d".formatted(DOC_TYPE, buckets));
            for (var edgeType : REFERENCE_KEYS.values()) {
                db.command("sql", "CREATE EDGE TYPE %s IF NOT EXISTS".formatted(edgeType));
            }
            db.command("sql", "CREATE DOCUMENT TYPE %s IF NOT EXISTS".formatted(LOCK_TYPE));
            db.command("sql", "CREATE PROPERTY %s.%s IF NOT EXISTS STRING".formatted(DOC_TYPE, name(KEY_ID)));
            db.command("sql", "CREATE INDEX IF NOT EXISTS ON %s (%s) UNIQUE".formatted(DOC_TYPE, name(KEY_ID)));
            for (var key : INDEXED_KEYS) {
                db.command("sql", "CREATE PROPERTY %s.%s IF NOT EXISTS STRING".formatted(DOC_TYPE, name(key)));
                db.command("sql", "CREATE INDEX IF NOT EXISTS ON %s (%s) NOTUNIQUE".formatted(DOC_TYPE, name(key)));
            }
            db.command("sql", "CREATE PROPERTY %s.%s IF NOT EXISTS LIST OF STRING".formatted(DOC_TYPE,
                    name(KEY_ANCESTOR_IDS)));
            db.command("sql", "CREATE INDEX IF NOT EXISTS ON %s (%s BY ITEM) NOTUNIQUE".formatted(DOC_TYPE,
                    name(KEY_ANCESTOR_IDS)));
            db.command("sql", "CREATE PROPERTY %s.%s IF NOT EXISTS STRING".formatted(LOCK_TYPE, LOCK_KEY));
            db.command("sql", "CREATE INDEX IF NOT EXISTS ON %s (%s) UNIQUE".formatted(LOCK_TYPE, LOCK_KEY));
        } finally {
            // don't pool it: its schema cache predates the DDL
            db.close();
        }
    }

    @Override
    public List<IdType> getAllowedIdTypes() {
        return List.of(IdType.varchar);
    }

    @Override
    public void shutdown() {
        super.shutdown();
        scrolls.clear();
        RemoteDatabase db;
        while ((db = pool.pollFirst()) != null) {
            db.close();
        }
    }

    protected String generateNewId() {
        if (DEBUG_UUIDS) {
            return "UUID_" + temporaryIdCounter.incrementAndGet();
        } else {
            return UUID.randomUUID().toString();
        }
    }

    /** The key of the lock document of a document. */
    protected String lockKey(String id) {
        return repositoryName + "/" + id;
    }

    @Override
    public boolean supportsTransactions() {
        return transactions;
    }

    @Override
    public ArcadeDBConnection getConnection() {
        return new ArcadeDBConnection(this);
    }

    @Override
    public void markReferencedBlobs(BiConsumer<String, String> markerCallback) {
        withAutoCommit(db -> {
            String afterId = "";
            for (;;) {
                var params = new HashMap<String, Object>();
                params.put("afterId", afterId);
                var results = db.query("sql", """
                        SELECT %s AS id, %s AS k FROM %s WHERE %s IS NOT NULL AND %s > :afterId
                        ORDER BY %s LIMIT 1000""".formatted(name(KEY_ID), name(KEY_BLOB_KEYS), DOC_TYPE,
                        name(KEY_BLOB_KEYS), name(KEY_ID), name(KEY_ID)), params).stream().toList();
                if (results.isEmpty()) {
                    return null;
                }
                for (var result : results) {
                    List<?> keys = result.getProperty("k");
                    keys.forEach(key -> markerCallback.accept((String) key, repositoryName));
                    afterId = result.getProperty("id");
                }
            }
        });
    }

    @Override
    public void updateCapabilities() {
        throw new UnsupportedOperationException();
    }

}
