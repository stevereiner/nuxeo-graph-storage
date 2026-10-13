package org.integratedsemantics.nuxeo.graph.coretests;

import org.nuxeo.runtime.test.runner.Features;

/** Nuxeo's {@link org.nuxeo.ecm.core.TestSQLRepositoryAPI} run against the graph backend. */
@Features(GraphBackendFeature.class)
public class TestGraphSQLRepositoryAPI extends org.nuxeo.ecm.core.TestSQLRepositoryAPI {
}
