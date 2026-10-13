package org.integratedsemantics.nuxeo.graph.arcadedb;

import org.nuxeo.ecm.core.storage.dbs.DBSRepositoryService;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.model.ComponentInstance;
import org.nuxeo.runtime.model.DefaultComponent;

/**
 * Service holding the registered ArcadeDB repositories, which are passed on to the {@link DBSRepositoryService}.
 *
 * @since 0.1.0
 */
public class ArcadeDBRepositoryService extends DefaultComponent {

    private static final String XP_REPOSITORY = "repository";

    @Override
    public void registerContribution(Object contrib, String xpoint, ComponentInstance contributor) {
        if (XP_REPOSITORY.equals(xpoint)) {
            Framework.getService(DBSRepositoryService.class)
                     .addContribution((ArcadeDBRepositoryDescriptor) contrib, ArcadeDBRepositoryFactory.class);
        } else {
            throw new IllegalArgumentException("Unknown extension point: " + xpoint);
        }
    }

    @Override
    public void unregisterContribution(Object contrib, String xpoint, ComponentInstance contributor) {
        if (XP_REPOSITORY.equals(xpoint)) {
            Framework.getService(DBSRepositoryService.class)
                     .removeContribution((ArcadeDBRepositoryDescriptor) contrib, ArcadeDBRepositoryFactory.class);
        } else {
            throw new IllegalArgumentException("Unknown extension point: " + xpoint);
        }
    }

}
