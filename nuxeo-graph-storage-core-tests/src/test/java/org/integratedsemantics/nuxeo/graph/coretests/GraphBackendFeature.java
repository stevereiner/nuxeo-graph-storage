package org.integratedsemantics.nuxeo.graph.coretests;

import org.nuxeo.runtime.test.runner.DynamicFeaturesLoader;
import org.nuxeo.runtime.test.runner.RunnerFeature;

/**
 * Loads the repository feature of the graph backend under test, chosen by the {@code nuxeo.test.graph} system property
 * ({@code neo4j} by default, {@code arcadedb}, or {@code mem} for a baseline run on Nuxeo's in-memory backend).
 *
 * @since 0.1.0
 */
public class GraphBackendFeature implements RunnerFeature {

    public static final String BACKEND_PROPERTY = "nuxeo.test.graph";

    public GraphBackendFeature(DynamicFeaturesLoader loader) {
        var backend = System.getProperty(BACKEND_PROPERTY, "neo4j");
        var featureClass = switch (backend) {
            case "neo4j" -> "org.integratedsemantics.nuxeo.graph.neo4j.Neo4jRepositoryFeature";
            case "arcadedb" -> "org.integratedsemantics.nuxeo.graph.arcadedb.ArcadeDBRepositoryFeature";
            // baseline: Nuxeo's own in-memory DBS backend, to compare failures
            case "mem" -> null;
            default -> throw new IllegalArgumentException("Unknown graph backend: " + backend);
        };
        if (featureClass != null) {
            loader.loadFeature(featureClass);
        }
    }

}
