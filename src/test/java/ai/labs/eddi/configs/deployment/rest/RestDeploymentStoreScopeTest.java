/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.deployment.rest;

import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** F5 — the deployment listing is scoped like the deployment-status listing. */
@DisplayName("RestDeploymentStore scope")
class RestDeploymentStoreScopeTest {

    private static DeploymentInfo deployed(String agentId, int version) {
        var info = new DeploymentInfo();
        info.setAgentId(agentId);
        info.setAgentVersion(version);
        return info;
    }

    @Test
    @DisplayName("workspaces on: only the agents the caller may use are listed, one access read per agent")
    void filteredToUsableAgents() throws Exception {
        var store = mock(IDeploymentStore.class);
        var guard = mock(ResourceAccessGuard.class);
        when(store.readDeploymentInfos()).thenReturn(List.of(deployed("mine", 1), deployed("mine", 2), deployed("theirs", 1)));
        when(guard.hasAccess("mine", AccessLevel.USE)).thenReturn(true);
        when(guard.hasAccess("theirs", AccessLevel.USE)).thenReturn(false);

        var listed = new RestDeploymentStore(store, guard).readDeploymentInfos();

        assertEquals(List.of("mine", "mine"), listed.stream().map(DeploymentInfo::getAgentId).toList());
        verify(guard, times(1)).hasAccess("mine", AccessLevel.USE);
    }

    @Test
    @DisplayName("workspaces off, or an administrator: everything, with no access reads")
    void unfilteredWhenSeeingEverything() throws Exception {
        var store = mock(IDeploymentStore.class);
        var guard = mock(ResourceAccessGuard.class);
        when(guard.seesEverything()).thenReturn(true);
        when(store.readDeploymentInfos()).thenReturn(List.of(deployed("a", 1), deployed("b", 1)));

        assertEquals(2, new RestDeploymentStore(store, guard).readDeploymentInfos().size());
        verify(guard, never()).hasAccess(any(), any());
    }
}
