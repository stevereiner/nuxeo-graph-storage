package org.integratedsemantics.nuxeo.graph.arcadedb;

import org.junit.runner.RunWith;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.WithFrameworkProperty;

/**
 * Concurrent writes without the write lock: ArcadeDB detects the conflicts at commit (repeatable reads), and the
 * connection runs the transaction's writes again (see {@link ArcadeDBConnection#commitOrReplay()}).
 *
 * @since 0.1.0
 */
@RunWith(FeaturesRunner.class)
@Features(ArcadeDBRepositoryFeature.class)
@WithFrameworkProperty(name = ArcadeDBRepository.TRANSACTIONS_PROPERTY, value = "true")
@WithFrameworkProperty(name = ArcadeDBRepository.WRITE_LOCK_PROPERTY, value = "false")
public class TestArcadeDBConcurrentWritesWithoutLock extends AbstractArcadeDBConcurrentWrites {
}
