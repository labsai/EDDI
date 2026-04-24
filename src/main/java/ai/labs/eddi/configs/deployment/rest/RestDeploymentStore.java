/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.deployment.rest;

import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.IRestDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.datastore.IResourceStore;
import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * @author ginccc
 */

@ApplicationScoped
public class RestDeploymentStore implements IRestDeploymentStore {
    private final IDeploymentStore deploymentStore;

    @Inject
    public RestDeploymentStore(IDeploymentStore deploymentStore) {
        this.deploymentStore = deploymentStore;
    }

    @Override
    public List<DeploymentInfo> readDeploymentInfos() {
        try {
            return deploymentStore.readDeploymentInfos();
        } catch (IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }
}
