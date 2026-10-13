package org.integratedsemantics.nuxeo.graph.neo4j;

import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_BLOB_KEYS;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_ID;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_PARENT_ID;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_PRIMARY_TYPE;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_PROXY_TARGET_ID;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_VERSION_SERIES_ID;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.neo4j.cypherdsl.core.Condition;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.BookmarkManagerConfig;
import org.neo4j.driver.BookmarkManagers;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.nuxeo.ecm.core.model.Repository;
import org.nuxeo.ecm.core.storage.dbs.DBSRepositoryBase;
import org.nuxeo.ecm.core.storage.dbs.DBSRepositoryDescriptor;
import org.integratedsemantics.nuxeo.graph.GraphScroll;
import org.nuxeo.runtime.api.Framework;

/**
 * Neo4j implementation of a {@link org.nuxeo.ecm.core.storage.dbs.DBSRepository}.
 * <p>
 * Documents are stored as {@code :NxDocument:Nx_<repository>} nodes and their complex properties as
 * {@code :NxComplex:Nx_<repository>} nodes, see {@link Neo4jGraphMapper}. The per-repository label isolates the
 * repository from other data living in the same Neo4j database.
 * <p>
 * Database access goes through the Neo4j Java driver: {@code neo4j://} and {@code neo4j+s://} URIs (clusters, AuraDB)
 * are supported, and all the sessions share a bookmark manager for causal consistency.
 *
 * @since 0.1.0
 */
public class Neo4jRepository extends DBSRepositoryBase {

    private static final Logger log = LogManager.getLogger(Neo4jRepository.class);

    /** nuxeo.conf property for the Bolt URI of the Neo4j server. */
    public static final String URI_PROPERTY = "nuxeo.neo4j.uri";

    /** nuxeo.conf property for the Neo4j user name. */
    public static final String USERNAME_PROPERTY = "nuxeo.neo4j.username";

    /** nuxeo.conf property for the Neo4j password. If blank, no authentication is used. */
    public static final String PASSWORD_PROPERTY = "nuxeo.neo4j.password";

    /** nuxeo.conf property for the Neo4j database name. If blank, the server default database is used. */
    public static final String DATABASE_PROPERTY = "nuxeo.neo4j.database";

    /**
     * nuxeo.conf property for the timeout of Neo4j transactions, in seconds (default 300, like Nuxeo transactions).
     * A transaction left open by its caller is then terminated by the server, releasing its locks.
     */
    public static final String TRANSACTION_TIMEOUT_PROPERTY = "nuxeo.neo4j.transactionTimeout";

    public static final String DEFAULT_URI = "bolt://localhost:7687";

    public static final String DEFAULT_USERNAME = "neo4j";

    /** Label of all document nodes. */
    public static final String DOCUMENT_LABEL = "NxDocument";

    /** Label of all complex property nodes. */
    public static final String COMPLEX_LABEL = "NxComplex";

    /** Label of all lock nodes. */
    public static final String LOCK_LABEL = "NxLock";

    /** Property of lock nodes holding {@code repository/docId}. */
    public static final String LOCK_KEY = "lockKey";

    /** Relationship from a node to its complex property nodes. */
    public static final String REL_PROP = "PROP";

    /** Relationship from a document to its parent, derived from {@code ecm:parentId}. */
    public static final String REL_CHILD_OF = "CHILD_OF";

    /** Relationship from a proxy to its target, derived from {@code ecm:proxyTargetId}. */
    public static final String REL_PROXY_OF = "PROXY_OF";

    /** Relationship from a version to its live document, derived from {@code ecm:versionSeriesId}. */
    public static final String REL_VERSION_OF = "VERSION_OF";

    /** The document keys that are also materialized as relationships, and the relationship type to use. */
    public static final Map<String, String> REFERENCE_KEYS = Map.of( //
            KEY_PARENT_ID, REL_CHILD_OF, //
            KEY_PROXY_TARGET_ID, REL_PROXY_OF, //
            KEY_VERSION_SERIES_ID, REL_VERSION_OF);

    protected static final List<String> INDEXED_KEYS = List.of(KEY_PARENT_ID, KEY_PRIMARY_TYPE, KEY_PROXY_TARGET_ID,
            KEY_VERSION_SERIES_ID);

    // for debug
    private final AtomicLong temporaryIdCounter = new AtomicLong(0);

    protected final Driver driver;

    /** The configuration of all the driver sessions (database, shared bookmark manager). */
    protected final SessionConfig sessionConfig;

    /** Runs the statements done outside of Nuxeo transactions. */
    protected final Neo4jStatementExecutor executor;

    /** The repository-specific label, {@code Nx_<repository>}. */
    protected final String repositoryLabel;

    /** Cypher label expression for document nodes, {@code :NxDocument:`Nx_<repository>`}. */
    protected final String docLabels;

    /** Cypher label expression for complex nodes, {@code :NxComplex:`Nx_<repository>`}. */
    protected final String complexLabels;

    /** Cypher label expression for lock nodes, {@code :NxLock:`Nx_<repository>`}. */
    protected final String lockLabels;

    /** The database name, or {@code null} for the default database. */
    protected final String database;

    protected final Duration transactionTimeout;

    /** Open scrolls, by scroll id. */
    protected final Map<String, GraphScroll<Condition>> scrolls = new ConcurrentHashMap<>();

    public Neo4jRepository(DBSRepositoryDescriptor descriptor) {
        super(descriptor.name, descriptor);
        repositoryLabel = "Nx_" + descriptor.name.replaceAll("[^A-Za-z0-9_]", "_");
        docLabels = ":" + DOCUMENT_LABEL + ":`" + repositoryLabel + "`";
        complexLabels = ":" + COMPLEX_LABEL + ":`" + repositoryLabel + "`";
        lockLabels = ":" + LOCK_LABEL + ":`" + repositoryLabel + "`";
        driver = newDriver();
        var db = Framework.getProperty(DATABASE_PROPERTY);
        database = StringUtils.isBlank(db) ? null : db;
        transactionTimeout = Duration.ofSeconds(
                Long.parseLong(Framework.getProperty(TRANSACTION_TIMEOUT_PROPERTY, "300")));
        // all the sessions share the bookmarks, for causal consistency on clusters (AuraDB...)
        var sessionConfigBuilder = SessionConfig.builder()
                                                .withBookmarkManager(BookmarkManagers.defaultManager(
                                                        BookmarkManagerConfig.builder().build()));
        if (database != null) {
            sessionConfigBuilder.withDatabase(database);
        }
        sessionConfig = sessionConfigBuilder.build();
        executor = new Neo4jStatementExecutor(driver, sessionConfig);
        // all the blob keys are stored in ecm:blobKeys
        capabilities.put(Repository.CAPABILITY_QUERY_BLOB_KEYS, Boolean.TRUE);
        initRepository();
    }

    protected Driver newDriver() {
        var uri = Framework.getProperty(URI_PROPERTY, DEFAULT_URI);
        var username = Framework.getProperty(USERNAME_PROPERTY, DEFAULT_USERNAME);
        var password = Framework.getProperty(PASSWORD_PROPERTY);
        var authToken = StringUtils.isBlank(password) ? AuthTokens.none() : AuthTokens.basic(username, password);
        log.info("Repository: {} connecting to Neo4j at: {}", repositoryName, uri);
        var driver = GraphDatabase.driver(uri, authToken);
        driver.verifyConnectivity();
        return driver;
    }

    protected void initRepository() {
        initSchema();
        try (var connection = getConnection()) {
            if (connection.readState(connection.getRootId()) == null) {
                connection.initRoot();
            }
        }
    }

    /**
     * Creates the uniqueness constraint on document ids and the indexes on the most queried keys.
     */
    protected void initSchema() {
        var prefix = repositoryLabel.toLowerCase();
        executor.rows("CREATE CONSTRAINT %s_id IF NOT EXISTS FOR (n:`%s`) REQUIRE n.`%s` IS UNIQUE".formatted(prefix,
                repositoryLabel, KEY_ID), Map.of());
        for (var key : INDEXED_KEYS) {
            var indexName = prefix + "_" + key.replaceAll("[^A-Za-z0-9_]", "_");
            executor.rows("CREATE INDEX %s IF NOT EXISTS FOR (n:`%s`) ON (n.`%s`)".formatted(indexName,
                    repositoryLabel, key), Map.of());
        }
        executor.rows("CREATE CONSTRAINT nx_lock_key IF NOT EXISTS FOR (n:%s) REQUIRE n.%s IS UNIQUE".formatted(
                LOCK_LABEL, LOCK_KEY), Map.of());
        executor.rows("CALL db.awaitIndexes(300)", Map.of());
    }

    @Override
    public List<IdType> getAllowedIdTypes() {
        return List.of(IdType.varchar);
    }

    @Override
    public void shutdown() {
        super.shutdown();
        scrolls.clear();
        driver.close();
    }

    protected String generateNewId() {
        if (DEBUG_UUIDS) {
            return "UUID_" + temporaryIdCounter.incrementAndGet();
        } else {
            return UUID.randomUUID().toString();
        }
    }

    @Override
    public boolean supportsTransactions() {
        return true;
    }

    @Override
    public Neo4jConnection getConnection() {
        return new Neo4jConnection(this);
    }

    /**
     * Executes some code atomically, outside of a Nuxeo transaction.
     */
    protected <T> T inTransaction(Supplier<T> supplier) {
        return executor.inTransaction(supplier);
    }

    /**
     * Opens a driver session, used for the connections' transactions and for locks.
     */
    protected Session openSession() {
        return driver.session(sessionConfig);
    }

    /** The key of the lock node of a document. */
    protected String lockKey(String id) {
        return repositoryName + "/" + id;
    }

    @Override
    public void markReferencedBlobs(BiConsumer<String, String> markerCallback) {
        executor.rows("MATCH (d%s) WHERE d.`%s` IS NOT NULL RETURN d.`%s` AS keys".formatted(docLabels,
                KEY_BLOB_KEYS, KEY_BLOB_KEYS), Map.of())
                .forEach(row -> ((List<?>) row.get("keys")).forEach(
                        key -> markerCallback.accept((String) key, repositoryName)));
    }

    @Override
    public void updateCapabilities() {
        throw new UnsupportedOperationException();
    }

    /**
     * Gets the repository-specific label, {@code Nx_<repository>}.
     */
    public String getRepositoryLabel() {
        return repositoryLabel;
    }

}
