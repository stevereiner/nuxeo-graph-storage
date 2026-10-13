package org.integratedsemantics.nuxeo.graph.neo4j;

import org.nuxeo.ecm.core.storage.dbs.DBSRepositoryFactory;

/**
 * Neo4j repository factory.
 * <p>
 * Accepts any {@link org.nuxeo.ecm.core.storage.dbs.DBSRepositoryDescriptor}, so that a Neo4j contribution can take
 * over a repository first registered by another DBS backend (the descriptors are merged into the first one's class).
 *
 * @since 0.1.0
 */
public class Neo4jRepositoryFactory extends DBSRepositoryFactory {

    public Neo4jRepositoryFactory(String repositoryName) {
        super(repositoryName);
    }

    @Override
    public Object call() {
        return new Neo4jRepository(getRepositoryDescriptor());
    }

}
