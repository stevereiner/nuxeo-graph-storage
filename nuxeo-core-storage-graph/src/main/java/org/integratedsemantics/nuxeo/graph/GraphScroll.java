package org.integratedsemantics.nuxeo.graph;

import org.nuxeo.ecm.core.storage.dbs.DBSExpressionEvaluator;

/**
 * An open scroll. Batches are fetched by keyset pagination on the document id, so nothing but the last id is kept
 * between batches.
 *
 * @param <C> the backend condition type
 * @since 0.1.0
 */
public class GraphScroll<C> {

    protected final C where;

    protected final boolean exact;

    protected final DBSExpressionEvaluator evaluator;

    protected final int batchSize;

    protected final long keepAliveMillis;

    protected String lastId;

    protected boolean exhausted;

    protected long lastAccess;

    public GraphScroll(C where, boolean exact, DBSExpressionEvaluator evaluator, int batchSize, int keepAliveSeconds) {
        this.where = where;
        this.exact = exact;
        this.evaluator = evaluator;
        this.batchSize = batchSize;
        this.keepAliveMillis = keepAliveSeconds * 1000L;
        touch();
    }

    public void touch() {
        lastAccess = System.currentTimeMillis();
    }

    public boolean isExpired() {
        return System.currentTimeMillis() - lastAccess > keepAliveMillis;
    }

}
