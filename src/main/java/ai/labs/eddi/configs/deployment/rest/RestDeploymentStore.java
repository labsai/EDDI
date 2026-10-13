/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.deployment.rest;

import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.IRestDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */

@ApplicationScoped
public class RestDeploymentStore implements IRestDeploymentStore {
    private final IDeploymentStore deploymentStore;
    private final ResourceAccessGuard accessGuard;

    @Inject
    public RestDeploymentStore(IDeploymentStore deploymentStore, ResourceAccessGuard accessGuard) {
        this.deploymentStore = deploymentStore;
        this.accessGuard = accessGuard;
    }

    /**
     * The deployment records of the agents the caller may {@link AccessLevel#USE} —
     * the same rule as {@code GET /administration/{env}/deploymentstatus}. It used
     * to return every record in the deployment, so with workspaces enforced any
     * editor could enumerate the ids, versions and environments of every other
     * team's agents. Unfiltered while workspaces are not enforced, and for
     * administrators.
     */
    @Override
    public List<DeploymentInfo> readDeploymentInfos() {
        try {
            List<DeploymentInfo> all = deploymentStore.readDeploymentInfos();
            if (accessGuard.seesEverything()) {
                return all;
            }
            // One descriptor read per agent, not per record: an agent has a record per
            // environment and version.
            Map<String, Boolean> visible = new HashMap<>();
            return all.stream()
                    .filter(info -> info != null && info.getAgentId() != null
                            && visible.computeIfAbsent(info.getAgentId(), id -> accessGuard.hasAccess(id, AccessLevel.USE)))
                    .toList();
        } catch (IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }
}
