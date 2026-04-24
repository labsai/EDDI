/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.deployment;

import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo.DeploymentStatus;
import ai.labs.eddi.datastore.IResourceStore;

import java.util.List;

/**
 * @author ginccc
 */
public interface IDeploymentStore {

    DeploymentInfo getDeploymentInfo(String environment, String agentId, Integer agentVersion) throws IResourceStore.ResourceStoreException;

    void setDeploymentInfo(String environment, String agentId, Integer agentVersion, DeploymentStatus deploymentStatus);

    List<DeploymentInfo> readDeploymentInfos() throws IResourceStore.ResourceStoreException;

    List<DeploymentInfo> readDeploymentInfos(DeploymentStatus deploymentStatus) throws IResourceStore.ResourceStoreException;
}
