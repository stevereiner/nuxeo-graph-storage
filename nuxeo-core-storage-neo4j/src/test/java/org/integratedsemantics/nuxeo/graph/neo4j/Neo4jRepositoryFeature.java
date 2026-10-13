package org.integratedsemantics.nuxeo.graph.neo4j;

import org.nuxeo.ecm.core.test.CoreFeature;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.RunnerFeature;

/**
 * Runs the standard {@link CoreFeature} with its "test" repository stored in Neo4j.
 * <p>
 * {@link CoreFeature} only knows the vcs/mem/mongodb backends, so it is run in "mem" mode (see the surefire
 * configuration) and the Neo4j test contribution then takes over the "test" repository.
 *
 * @since 0.1.0
 */
@Deploy("org.integratedsemantics.nuxeo.graph.neo4j")
@Deploy("org.integratedsemantics.nuxeo.graph.neo4j.test:OSGI-INF/test-neo4j-repository-contrib.xml")
@Features(CoreFeature.class)
public class Neo4jRepositoryFeature implements RunnerFeature {

    public static final String REPOSITORY_NAME = "test";

    @Override
    public void initialize(FeaturesRunner runner) {
        Neo4jTestServer.start();
        Neo4jTestServer.clear(REPOSITORY_NAME);
    }

}
