/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.deployment.mongo;

import java.util.Map;
import java.util.HashMap;
import ai.labs.eddi.engine.cluster.events.IClusterEventBus;
import ai.labs.eddi.engine.cluster.events.ClusterEvent;
import ai.labs.eddi.configs.deployment.IDeploymentStorage;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.datastore.IResourceStore;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * DeploymentStore delegates to {@link IDeploymentStorage} for all persistence.
 * The underlying storage is injected via CDI (MongoDB by default, PostgreSQL
 * when configured).
 *
 * @author ginccc
 */
@ApplicationScoped
public class DeploymentStore implements IDeploymentStore {

    private final IDeploymentStorage storage;

    /**
     * Cluster mode: every change of a deployment record is announced, so the other
     * nodes deploy or undeploy within about a second instead of at their next
     * reconciliation sweep. Field-injected (null in tests); a no-op on one node.
     */
    @Inject
    IClusterEventBus clusterEvents;

    @Inject
    public DeploymentStore(IDeploymentStorage storage) {
        this.storage = storage;
    }

    private void announce(String environment, String agentId, Integer agentVersion, String status) {
        if (clusterEvents != null) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("env", environment);
            payload.put("agentId", agentId);
            payload.put("version", agentVersion);
            payload.put("status", status);
            clusterEvents.publish(ClusterEvent.DEPLOYMENT_CHANGED, payload);
        }
    }

    @Override
    public DeploymentInfo getDeploymentInfo(String environment, String agentId, Integer agentVersion) throws IResourceStore.ResourceStoreException {
        return storage.readDeploymentInfo(environment, agentId, agentVersion);
    }

    @Override
    public void setDeploymentInfo(String environment, String agentId, Integer agentVersion, DeploymentInfo.DeploymentStatus deploymentStatus) {
        storage.setDeploymentInfo(environment, agentId, agentVersion, deploymentStatus);
        announce(environment, agentId, agentVersion, deploymentStatus == null ? null : deploymentStatus.toString());
    }

    /**
     * Status {@value #TRANSIENT} — the receiving nodes deploy it without a record.
     */
    @Override
    public void announceTransientDeployment(String environment, String agentId, Integer agentVersion) {
        announce(environment, agentId, agentVersion, TRANSIENT);
    }

    /**
     * The {@code status} of a {@code deployment.changed} event for an unrecorded
     * deploy.
     */
    public static final String TRANSIENT = "deployed-transient";

    @Override
    public List<DeploymentInfo> readDeploymentInfos() throws IResourceStore.ResourceStoreException {
        return storage.readDeploymentInfos();
    }

    @Override
    public List<DeploymentInfo> readDeploymentInfos(DeploymentInfo.DeploymentStatus deploymentStatus) throws IResourceStore.ResourceStoreException {
        return storage.readDeploymentInfos(deploymentStatus.toString());
    }

    @Override
    public int deleteDeploymentInfos(String agentId) throws IResourceStore.ResourceStoreException {
        int deleted = storage.deleteDeploymentInfos(agentId);
        announce(null, agentId, null, "deleted");
        return deleted;
    }

    @Override
    public int deleteDeploymentInfo(String environment, String agentId, Integer agentVersion) throws IResourceStore.ResourceStoreException {
        int deleted = storage.deleteDeploymentInfo(environment, agentId, agentVersion);
        announce(environment, agentId, agentVersion, "deleted");
        return deleted;
    }
}
