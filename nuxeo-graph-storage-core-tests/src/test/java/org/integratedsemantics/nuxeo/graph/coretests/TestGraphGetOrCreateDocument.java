package org.integratedsemantics.nuxeo.graph.coretests;

import org.nuxeo.runtime.test.runner.Features;

/** Nuxeo's {@link org.nuxeo.ecm.core.TestGetOrCreateDocument} run against the graph backend. */
@Features(GraphBackendFeature.class)
public class TestGraphGetOrCreateDocument extends org.nuxeo.ecm.core.TestGetOrCreateDocument {
}
