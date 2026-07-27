/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.deployment;

import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.datastore.IResourceStore;

import java.util.List;

/**
 * Database-agnostic storage interface for deployment information.
 * <p>
 * Implementations: MongoDeploymentStorage (@DefaultBean),
 * PostgresDeploymentStorage (@LookupIfProperty).
 */
public interface IDeploymentStorage {

    void setDeploymentInfo(String environment, String agentId, Integer agentVersion, DeploymentInfo.DeploymentStatus deploymentStatus);

    DeploymentInfo readDeploymentInfo(String environment, String agentId, Integer agentVersion) throws IResourceStore.ResourceStoreException;

    List<DeploymentInfo> readDeploymentInfos() throws IResourceStore.ResourceStoreException;

    List<DeploymentInfo> readDeploymentInfos(String deploymentStatus) throws IResourceStore.ResourceStoreException;

    /**
     * Deletes every deployment record belonging to an Agent, across all
     * environments and versions. Called when the Agent itself is deleted — a
     * deployment record whose Agent no longer exists makes the runtime attempt (and
     * fail) a redeploy on every startup.
     *
     * @return the number of deployment records removed
     */
    int deleteDeploymentInfos(String agentId) throws IResourceStore.ResourceStoreException;
}
