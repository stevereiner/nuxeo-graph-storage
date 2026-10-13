package org.integratedsemantics.nuxeo.graph.arcadedb;

import org.junit.runner.RunWith;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.WithFrameworkProperty;

/**
 * Concurrent writes without repository transactions, each write optimistic (ArcadeDB runs it again if it conflicts),
 * holding the write lock only if it still conflicts.
 *
 * @since 0.1.0
 */
@RunWith(FeaturesRunner.class)
@Features(ArcadeDBRepositoryFeature.class)
@WithFrameworkProperty(name = ArcadeDBRepository.TRANSACTIONS_PROPERTY, value = "false")
@WithFrameworkProperty(name = ArcadeDBRepository.WRITE_LOCK_PROPERTY, value = "fallback")
public class TestArcadeDBConcurrentWritesWithoutTransactionsOptimistic extends AbstractArcadeDBConcurrentWrites {
}
