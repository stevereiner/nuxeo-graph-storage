package org.integratedsemantics.nuxeo.graph;

import static org.nuxeo.ecm.core.query.sql.NXQL.ECM_UUID;
import static org.nuxeo.ecm.core.storage.dbs.DBSDocument.KEY_ID;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.PartialList;
import org.nuxeo.ecm.core.api.ScrollResult;
import org.nuxeo.ecm.core.api.ScrollResultImpl;
import org.nuxeo.ecm.core.query.sql.model.OrderByClause;
import org.nuxeo.ecm.core.query.sql.model.Reference;
import org.nuxeo.ecm.core.storage.State;
import org.nuxeo.ecm.core.storage.dbs.DBSConnectionBase;
import org.nuxeo.ecm.core.storage.dbs.DBSExpressionEvaluator;
import org.nuxeo.ecm.core.storage.dbs.DBSRepositoryBase;
import org.nuxeo.ecm.core.storage.dbs.DBSSession.OrderByComparator;
import org.integratedsemantics.nuxeo.graph.GraphQueryTranslator.SortKey;
import org.integratedsemantics.nuxeo.graph.GraphQueryTranslator.Translation;

/**
 * Base class for graph database {@link org.nuxeo.ecm.core.storage.dbs.DBSConnection}s, implementing NXQL queries and
 * scrolls on top of a few backend primitives ({@link #queryIds}, {@link #countIds}).
 * <p>
 * The query is translated by a {@link GraphQueryTranslator}:
 * <ul>
 * <li>if the translation is exact and the ORDER BY translatable, ORDER BY, OFFSET, LIMIT and count are done by the
 * database, and only the returned page of documents is loaded,</li>
 * <li>otherwise the translation is used as a prefilter: candidates are fetched in batches (in ORDER BY order when
 * possible, so that it can stop early) and the exact NXQL semantics are applied in Java by the
 * {@link DBSExpressionEvaluator}.</li>
 * </ul>
 *
 * @param <C> the backend condition type
 * @since 0.1.0
 */
public abstract class GraphConnectionBase<C> extends DBSConnectionBase {

    private static final Logger log = LogManager.getLogger(GraphConnectionBase.class);

    /** Number of candidate documents fetched at a time when evaluating in Java. */
    protected static final int QUERY_BATCH_SIZE = 500;

    protected GraphConnectionBase(DBSRepositoryBase repository) {
        super(repository);
    }

    /** Creates the query translator for this backend. */
    protected abstract GraphQueryTranslator<C> newTranslator(DBSExpressionEvaluator evaluator);

    /**
     * Gets the ids of the documents matching a condition.
     *
     * @param where the condition, {@code null} for all documents
     * @param sort the sort keys; if empty, the ids are sorted by id
     * @param afterId if not {@code null} (only with an empty sort), only return ids greater than this one
     * @param offset the number of ids to skip
     * @param limit the maximum number of ids to return, {@code 0} for no limit
     */
    protected abstract List<String> queryIds(C where, List<SortKey> sort, String afterId, long offset, long limit);

    /**
     * Counts the documents matching a condition.
     *
     * @param where the condition, {@code null} for all documents
     * @param upTo stop counting after this number, {@code 0} for no limit
     */
    protected abstract long countIds(C where, long upTo);

    /** The open scrolls of the repository. */
    protected abstract Map<String, GraphScroll<C>> getScrolls();

    /**
     * Reads states in the order of the given ids, skipping missing ones.
     */
    protected List<State> readStatesInOrder(List<String> ids) {
        var byId = new HashMap<String, State>();
        for (var state : readStates(ids)) {
            byId.put((String) state.get(KEY_ID), state);
        }
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    protected static void addMatches(List<Map<String, Serializable>> projections, DBSExpressionEvaluator evaluator,
            List<State> states, boolean distinctDocuments) {
        for (var state : states) {
            var matches = evaluator.matches(state);
            if (!matches.isEmpty()) {
                if (distinctDocuments) {
                    projections.add(matches.get(0));
                } else {
                    projections.addAll(matches);
                }
            }
        }
    }

    protected static boolean hasWildcardProjection(DBSExpressionEvaluator evaluator) {
        var selectClause = evaluator.getSelectClause();
        return selectClause != null && selectClause.elements.values()
                                                            .stream()
                                                            .anyMatch(o -> o instanceof Reference r
                                                                    && r.name.contains("*"));
    }

    @Override
    public PartialList<Map<String, Serializable>> queryAndFetch(DBSExpressionEvaluator evaluator,
            OrderByClause orderByClause, boolean distinctDocuments, int limit, int offset, int countUpTo) {
        FieldFulltextRewriter.rewrite(evaluator);
        evaluator.parse();
        var translation = newTranslator(evaluator).translate(orderByClause);
        // with wildcard projections a document can give several rows, so paging can't be done on documents
        boolean oneRowPerDoc = distinctDocuments || !hasWildcardProjection(evaluator);
        boolean dbSort = translation.sort() != null && oneRowPerDoc;
        log.debug("Query: {} exact={} dbSort={}", evaluator, translation.exact(), dbSort);
        if (translation.exact() && dbSort) {
            return queryExact(evaluator, translation, distinctDocuments, limit, offset, countUpTo);
        } else {
            return queryFiltered(evaluator, translation, orderByClause, dbSort, distinctDocuments, limit, offset,
                    countUpTo);
        }
    }

    /**
     * The query is fully done by the database, only the requested page is loaded.
     */
    protected PartialList<Map<String, Serializable>> queryExact(DBSExpressionEvaluator evaluator,
            Translation<C> translation, boolean distinctDocuments, int limit, int offset, int countUpTo) {
        var ids = queryIds(translation.where(), translation.sort(), null, offset, limit);
        var projections = new ArrayList<Map<String, Serializable>>(ids.size());
        // the evaluator computes the projections (and double-checks the match)
        addMatches(projections, evaluator, readStatesInOrder(ids), distinctDocuments);
        long totalSize;
        if (countUpTo == 0) {
            totalSize = -1;
        } else if (limit == 0 && offset == 0) {
            totalSize = projections.size();
        } else {
            totalSize = countIds(translation.where(), countUpTo > 0 ? countUpTo + 1L : 0);
        }
        if (countUpTo > 0 && totalSize > countUpTo) {
            totalSize = -2;
        }
        return new PartialList<>(projections, totalSize);
    }

    /**
     * The database returns candidates, the exact semantics are applied in Java.
     */
    protected PartialList<Map<String, Serializable>> queryFiltered(DBSExpressionEvaluator evaluator,
            Translation<C> translation, OrderByClause orderByClause, boolean dbSort, boolean distinctDocuments,
            int limit, int offset, int countUpTo) {
        boolean javaSort = orderByClause != null && !orderByClause.elements.isEmpty() && !dbSort;
        // when results arrive in their final order, we can stop as soon as we have enough
        boolean canStopEarly = limit != 0 && !javaSort && countUpTo != -1;
        long needed = (long) offset + limit;
        var projections = new ArrayList<Map<String, Serializable>>();
        boolean truncated = false;
        var sort = dbSort ? translation.sort() : List.<SortKey> of();
        String afterId = null;
        long position = 0;
        for (;;) {
            List<String> ids;
            if (sort.isEmpty()) {
                // keyset pagination on ids
                ids = queryIds(translation.where(), sort, afterId, 0, QUERY_BATCH_SIZE);
                if (!ids.isEmpty()) {
                    afterId = ids.get(ids.size() - 1);
                }
            } else {
                ids = queryIds(translation.where(), sort, null, position, QUERY_BATCH_SIZE);
                position += ids.size();
            }
            addMatches(projections, evaluator, readStatesInOrder(ids), distinctDocuments);
            if (canStopEarly && projections.size() >= needed
                    && (countUpTo == 0 || projections.size() > countUpTo)) {
                truncated = true;
                break;
            }
            if (ids.size() < QUERY_BATCH_SIZE) {
                break;
            }
        }
        if (javaSort) {
            projections.sort(new OrderByComparator(orderByClause));
        }
        long totalSize = projections.size();
        if (countUpTo == 0) {
            totalSize = -1;
        } else if (truncated || (countUpTo > 0 && totalSize > countUpTo)) {
            totalSize = -2;
        }
        if (limit != 0) {
            int size = projections.size();
            projections.subList(0, Math.min(offset, size)).clear();
            size = projections.size();
            if (limit < size) {
                projections.subList(limit, size).clear();
            }
        }
        return new PartialList<>(projections, totalSize);
    }

    @Override
    public ScrollResult<String> scroll(DBSExpressionEvaluator evaluator, int batchSize, int keepAliveSeconds) {
        FieldFulltextRewriter.rewrite(evaluator);
        evaluator.parse();
        var translation = newTranslator(evaluator).translate(null);
        var scrolls = getScrolls();
        scrolls.values().removeIf(GraphScroll::isExpired);
        var scrollId = UUID.randomUUID().toString();
        var scroll = new GraphScroll<>(translation.where(), translation.exact(), evaluator, batchSize,
                keepAliveSeconds);
        scrolls.put(scrollId, scroll);
        var batch = nextBatch(scroll);
        if (batch.isEmpty()) {
            scrolls.remove(scrollId);
        }
        return new ScrollResultImpl<>(scrollId, batch);
    }

    @Override
    public ScrollResult<String> scroll(String scrollId) {
        var scrolls = getScrolls();
        var scroll = scrolls.get(scrollId);
        if (scroll == null || scroll.isExpired()) {
            scrolls.remove(scrollId);
            throw new NuxeoException("Unknown or timed out scrollId: " + scrollId);
        }
        var batch = nextBatch(scroll);
        if (batch.isEmpty()) {
            // the scroll is now closed
            scrolls.remove(scrollId);
            return ScrollResultImpl.emptyResult();
        }
        return new ScrollResultImpl<>(scrollId, batch);
    }

    protected List<String> nextBatch(GraphScroll<C> scroll) {
        synchronized (scroll) {
            scroll.touch();
            for (;;) {
                if (scroll.exhausted) {
                    return List.of();
                }
                var ids = queryIds(scroll.where, List.of(), scroll.lastId, 0, scroll.batchSize);
                if (ids.size() < scroll.batchSize) {
                    scroll.exhausted = true;
                }
                if (ids.isEmpty()) {
                    return ids;
                }
                scroll.lastId = ids.get(ids.size() - 1);
                if (scroll.exact) {
                    return ids;
                }
                var matching = new ArrayList<String>();
                for (var state : readStatesInOrder(ids)) {
                    var matches = scroll.evaluator.matches(state);
                    if (!matches.isEmpty()) {
                        matching.add(matches.get(0).get(ECM_UUID).toString());
                    }
                }
                if (!matching.isEmpty()) {
                    return matching;
                }
            }
        }
    }

}
