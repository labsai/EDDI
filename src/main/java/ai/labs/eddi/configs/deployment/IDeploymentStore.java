/*
 * Copyright EDDI contributors
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

    /**
     * Deletes every deployment record belonging to an Agent, across all
     * environments and versions. For when the whole Agent is gone.
     *
     * @return the number of deployment records removed
     */
    int deleteDeploymentInfos(String agentId) throws IResourceStore.ResourceStoreException;

    /**
     * Deletes the single deployment record identified by environment, Agent and
     * version — for callers that have established only that this version is gone.
     *
     * @return the number of deployment records removed (0 or 1)
     */
    int deleteDeploymentInfo(String environment, String agentId, Integer agentVersion) throws IResourceStore.ResourceStoreException;
}
