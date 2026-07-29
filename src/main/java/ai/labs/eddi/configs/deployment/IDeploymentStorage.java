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
     * environments and versions. For when the Agent itself is gone — a deployment
     * record whose Agent no longer exists makes the runtime attempt (and fail) a
     * redeploy on every startup.
     *
     * @return the number of deployment records removed
     */
    int deleteDeploymentInfos(String agentId) throws IResourceStore.ResourceStoreException;

    /**
     * Deletes the single deployment record identified by environment, Agent and
     * version. For callers that have established only that <em>this</em> version is
     * gone: deleting agent-wide there would take out sibling records they never
     * checked.
     *
     * @return the number of deployment records removed (0 or 1)
     */
    int deleteDeploymentInfo(String environment, String agentId, Integer agentVersion) throws IResourceStore.ResourceStoreException;
}
