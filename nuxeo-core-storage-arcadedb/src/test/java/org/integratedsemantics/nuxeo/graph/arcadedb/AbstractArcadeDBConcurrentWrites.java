package org.integratedsemantics.nuxeo.graph.arcadedb;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.junit.Test;
import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.runtime.test.runner.TransactionalFeature;
import org.nuxeo.runtime.transaction.TransactionHelper;

/**
 * Concurrent writes to one document from several transactions: no change may be lost.
 *
 * @since 0.1.0
 */
public abstract class AbstractArcadeDBConcurrentWrites {

    protected static final int ITERATIONS = 40;

    @Inject
    protected CoreSession session;

    @Inject
    protected TransactionalFeature txFeature;

    /**
     * Two threads append values to the same list of the same document, each value in its own transaction: the commits
     * conflict, and every value must survive (a lost commit loses its value).
     */
    @Test
    public void testConcurrentUpdatesOfOneDocument() throws Exception {
        var doc = session.createDocumentModel("/", "doc", "File");
        doc.setPropertyValue("dc:title", "initial");
        doc = session.createDocument(doc);
        txFeature.nextTransaction();
        var ref = doc.getRef();
        var replaysBefore = ArcadeDBConnection.REPLAYS.get();

        var executor = Executors.newFixedThreadPool(2);
        try {
            var tasks = new ArrayList<Callable<Void>>();
            tasks.add(() -> appendRepeatedly(ref, "a"));
            tasks.add(() -> appendRepeatedly(ref, "b"));
            for (var future : executor.invokeAll(tasks)) {
                future.get();
            }
        } finally {
            executor.shutdown();
            executor.awaitTermination(1, TimeUnit.MINUTES);
        }
        txFeature.nextTransaction();

        var expected = new TreeSet<String>();
        for (int i = 0; i < ITERATIONS; i++) {
            expected.add("a" + i);
            expected.add("b" + i);
        }
        var subjects = (Object[]) session.getDocument(ref).getPropertyValue("dc:subjects");
        var actual = new TreeSet<String>();
        for (var subject : subjects) {
            actual.add((String) subject);
        }
        var missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        System.out.println("ArcadeDB commit conflicts replayed: " + (ArcadeDBConnection.REPLAYS.get() - replaysBefore)
                + ", values missing: " + missing.size() + " " + missing);
        assertEquals(expected, actual);
    }

    protected Void appendRepeatedly(DocumentRef ref, String prefix) {
        for (int i = 0; i < ITERATIONS; i++) {
            var value = prefix + i;
            TransactionHelper.runInTransaction(() -> {
                var s = CoreInstance.getCoreSession(session.getRepositoryName());
                var d = s.getDocument(ref);
                var current = (Object[]) d.getPropertyValue("dc:subjects");
                var subjects = new ArrayList<Object>(current == null ? List.of() : List.of(current));
                subjects.add(value);
                d.setPropertyValue("dc:subjects", subjects.toArray(new String[0]));
                s.saveDocument(d);
            });
        }
        return null;
    }

}
