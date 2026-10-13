package org.integratedsemantics.nuxeo.graph.neo4j;

import org.nuxeo.ecm.core.storage.dbs.DBSRepositoryService;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.model.ComponentInstance;
import org.nuxeo.runtime.model.DefaultComponent;

/**
 * Service holding the registered Neo4j repositories, which are passed on to the {@link DBSRepositoryService}.
 *
 * @since 0.1.0
 */
public class Neo4jRepositoryService extends DefaultComponent {

    private static final String XP_REPOSITORY = "repository";

    @Override
    public void registerContribution(Object contrib, String xpoint, ComponentInstance contributor) {
        if (XP_REPOSITORY.equals(xpoint)) {
            Framework.getService(DBSRepositoryService.class)
                     .addContribution((Neo4jRepositoryDescriptor) contrib, Neo4jRepositoryFactory.class);
        } else {
            throw new IllegalArgumentException("Unknown extension point: " + xpoint);
        }
    }

    @Override
    public void unregisterContribution(Object contrib, String xpoint, ComponentInstance contributor) {
        if (XP_REPOSITORY.equals(xpoint)) {
            Framework.getService(DBSRepositoryService.class)
                     .removeContribution((Neo4jRepositoryDescriptor) contrib, Neo4jRepositoryFactory.class);
        } else {
            throw new IllegalArgumentException("Unknown extension point: " + xpoint);
        }
    }

}
