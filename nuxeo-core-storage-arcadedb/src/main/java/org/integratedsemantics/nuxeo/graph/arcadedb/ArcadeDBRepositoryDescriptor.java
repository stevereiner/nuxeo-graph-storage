package org.integratedsemantics.nuxeo.graph.arcadedb;

import org.nuxeo.common.xmap.annotation.XObject;
import org.nuxeo.ecm.core.storage.dbs.DBSRepositoryDescriptor;

/**
 * ArcadeDB repository descriptor. The ArcadeDB connection itself is configured through nuxeo.conf properties, see
 * {@link ArcadeDBRepository#HOST_PROPERTY}.
 *
 * @since 0.1.0
 */
@XObject(value = "repository")
public class ArcadeDBRepositoryDescriptor extends DBSRepositoryDescriptor {

    @Override
    public ArcadeDBRepositoryDescriptor clone() {
        return (ArcadeDBRepositoryDescriptor) super.clone();
    }

}
