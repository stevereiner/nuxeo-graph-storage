package org.integratedsemantics.nuxeo.graph.arcadedb;

import org.nuxeo.ecm.core.storage.dbs.DBSRepositoryFactory;

/**
 * ArcadeDB repository factory.
 * <p>
 * Accepts any {@link org.nuxeo.ecm.core.storage.dbs.DBSRepositoryDescriptor}, so that an ArcadeDB contribution can take
 * over a repository first registered by another DBS backend (the descriptors are merged into the first one's class).
 *
 * @since 0.1.0
 */
public class ArcadeDBRepositoryFactory extends DBSRepositoryFactory {

    public ArcadeDBRepositoryFactory(String repositoryName) {
        super(repositoryName);
    }

    @Override
    public Object call() {
        return new ArcadeDBRepository(getRepositoryDescriptor());
    }

}
