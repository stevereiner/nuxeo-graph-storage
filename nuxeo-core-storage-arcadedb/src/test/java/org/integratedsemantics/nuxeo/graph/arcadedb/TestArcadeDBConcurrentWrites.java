package org.integratedsemantics.nuxeo.graph.arcadedb;

import org.junit.runner.RunWith;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.WithFrameworkProperty;

/**
 * Concurrent writes with repository transactions and the write lock: the writing transactions are serialized.
 *
 * @since 0.1.0
 */
@RunWith(FeaturesRunner.class)
@Features(ArcadeDBRepositoryFeature.class)
@WithFrameworkProperty(name = ArcadeDBRepository.TRANSACTIONS_PROPERTY, value = "true")
public class TestArcadeDBConcurrentWrites extends AbstractArcadeDBConcurrentWrites {
}
