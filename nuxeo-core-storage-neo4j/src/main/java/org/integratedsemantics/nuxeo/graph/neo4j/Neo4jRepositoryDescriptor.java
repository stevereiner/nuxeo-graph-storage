package org.integratedsemantics.nuxeo.graph.neo4j;

import org.nuxeo.common.xmap.annotation.XObject;
import org.nuxeo.ecm.core.storage.dbs.DBSRepositoryDescriptor;

/**
 * Neo4j repository descriptor. The Neo4j connection itself is configured through nuxeo.conf properties, see
 * {@link Neo4jRepository#URI_PROPERTY}.
 *
 * @since 0.1.0
 */
@XObject(value = "repository")
public class Neo4jRepositoryDescriptor extends DBSRepositoryDescriptor {

    @Override
    public Neo4jRepositoryDescriptor clone() {
        return (Neo4jRepositoryDescriptor) super.clone();
    }

}
