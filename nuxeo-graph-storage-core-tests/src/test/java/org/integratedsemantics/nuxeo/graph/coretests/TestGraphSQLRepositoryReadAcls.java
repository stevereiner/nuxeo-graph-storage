package org.integratedsemantics.nuxeo.graph.coretests;

import org.nuxeo.runtime.test.runner.Features;

/** Nuxeo's {@link org.nuxeo.ecm.core.TestSQLRepositoryReadAcls} run against the graph backend. */
@Features(GraphBackendFeature.class)
public class TestGraphSQLRepositoryReadAcls extends org.nuxeo.ecm.core.TestSQLRepositoryReadAcls {
}
