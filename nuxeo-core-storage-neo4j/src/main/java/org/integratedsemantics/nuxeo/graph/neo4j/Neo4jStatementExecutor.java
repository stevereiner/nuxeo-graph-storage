package org.integratedsemantics.nuxeo.graph.neo4j;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.TransactionContext;

/**
 * Runs Cypher statements outside of Nuxeo transactions: repository initialization, auto-committed statements, and
 * atomic units of work done without a Nuxeo transaction. Inside Nuxeo transactions, {@link Neo4jConnection}s use
 * their own driver transaction instead.
 * <p>
 * Units of work run in driver-managed transactions ({@code executeWrite}), which are retried on transient errors
 * such as deadlocks or cluster leader changes.
 *
 * @since 0.1.0
 */
public class Neo4jStatementExecutor {

    protected final Driver driver;

    protected final SessionConfig sessionConfig;

    /** The transaction of the unit of work running in the current thread, see {@link #inTransaction}. */
    protected final ThreadLocal<TransactionContext> current = new ThreadLocal<>();

    public Neo4jStatementExecutor(Driver driver, SessionConfig sessionConfig) {
        this.driver = driver;
        this.sessionConfig = sessionConfig;
    }

    /**
     * Runs a statement and returns its rows: in the current unit of work of {@link #inTransaction} if any, otherwise
     * auto-committed.
     */
    public List<Map<String, Object>> rows(String cypher, Map<String, Object> params) {
        var tx = current.get();
        if (tx != null) {
            return tx.run(cypher, params).list(Record::asMap);
        }
        try (var session = driver.session(sessionConfig)) {
            return session.run(cypher, params).list(Record::asMap);
        }
    }

    /**
     * Runs a unit of work atomically: the statements it runs through {@link #rows} are in the same transaction.
     */
    public <T> T inTransaction(Supplier<T> work) {
        if (current.get() != null) {
            return work.get();
        }
        try (var session = driver.session(sessionConfig)) {
            return session.executeWrite(tx -> {
                current.set(tx);
                try {
                    return work.get();
                } finally {
                    current.remove();
                }
            });
        }
    }

}
