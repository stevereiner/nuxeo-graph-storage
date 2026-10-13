package org.integratedsemantics.nuxeo.graph.arcadedb;

import org.nuxeo.ecm.core.test.CoreFeature;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.RunnerFeature;

/**
 * Runs the standard {@link CoreFeature} with its "test" repository stored in ArcadeDB.
 * <p>
 * {@link CoreFeature} only knows the vcs/mem/mongodb backends, so it is run in "mem" mode (see the surefire
 * configuration) and the ArcadeDB test contribution then takes over the "test" repository.
 *
 * @since 0.1.0
 */
@Deploy("org.integratedsemantics.nuxeo.graph.arcadedb")
@Deploy("org.integratedsemantics.nuxeo.graph.arcadedb.test:OSGI-INF/test-arcadedb-repository-contrib.xml")
@Features(CoreFeature.class)
public class ArcadeDBRepositoryFeature implements RunnerFeature {

    @Override
    public void initialize(FeaturesRunner runner) {
        ArcadeDBTestServer.start();
        ArcadeDBTestServer.clear();
    }

}
