package org.integratedsemantics.nuxeo.graph.arcadedb;

import org.junit.runner.RunWith;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.WithFrameworkProperty;

/**
 * Concurrent writes without repository transactions, like Nuxeo's MongoDB repository: each write commits on its own,
 * holding the write lock for that write only (see {@link ArcadeDBConnection#updateStateAutoCommit}).
 *
 * @since 0.1.0
 */
@RunWith(FeaturesRunner.class)
@Features(ArcadeDBRepositoryFeature.class)
@WithFrameworkProperty(name = ArcadeDBRepository.TRANSACTIONS_PROPERTY, value = "false")
public class TestArcadeDBConcurrentWritesWithoutTransactions extends AbstractArcadeDBConcurrentWrites {
}
