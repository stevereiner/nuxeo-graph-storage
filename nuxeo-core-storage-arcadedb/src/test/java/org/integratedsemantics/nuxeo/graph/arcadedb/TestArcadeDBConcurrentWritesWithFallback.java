package org.integratedsemantics.nuxeo.graph.arcadedb;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.WithFrameworkProperty;

/**
 * Concurrent writes with the lock as a fallback, and no optimistic replay: every transaction whose commit conflicts
 * is replayed holding the write lock (see {@link ArcadeDBConnection#commitOrReplay()}).
 *
 * @since 0.1.0
 */
@RunWith(FeaturesRunner.class)
@Features(ArcadeDBRepositoryFeature.class)
@WithFrameworkProperty(name = ArcadeDBRepository.TRANSACTIONS_PROPERTY, value = "true")
@WithFrameworkProperty(name = ArcadeDBRepository.WRITE_LOCK_PROPERTY, value = "fallback")
@WithFrameworkProperty(name = ArcadeDBRepository.OPTIMISTIC_REPLAYS_PROPERTY, value = "0")
public class TestArcadeDBConcurrentWritesWithFallback extends AbstractArcadeDBConcurrentWrites {

    @Override
    @Test
    public void testConcurrentUpdatesOfOneDocument() throws Exception {
        var replaysBefore = ArcadeDBConnection.REPLAYS.get();
        var lockedBefore = ArcadeDBConnection.LOCKED_REPLAYS.get();
        super.testConcurrentUpdatesOfOneDocument();
        var replays = ArcadeDBConnection.REPLAYS.get() - replaysBefore;
        var locked = ArcadeDBConnection.LOCKED_REPLAYS.get() - lockedBefore;
        System.out.println("ArcadeDB transactions replayed with the write lock: " + locked);
        // the first replay of a transaction is locked, and a locked replay doesn't conflict
        assertEquals(replays, locked);
    }

}
