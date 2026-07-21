/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.rest.IRestConversationStore;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.internal.IDeploymentListener;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.tenancy.QuotaExceededException;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the {@code maxAgentsPerTenant} gate on the deploy path.
 *
 * <p>
 * Two properties matter most here and are asserted explicitly: the denial must
 * surface as {@link QuotaExceededException} (so the mapper turns it into a 429)
 * rather than being rewrapped as a 500, and it must happen <em>before</em> any
 * work is submitted to the runtime.
 * </p>
 */
@DisplayName("RestAgentAdministration — maxAgentsPerTenant gate")
class RestAgentAdministrationQuotaTest {

    private static final String TENANT = "default";
    private static final String NEW_AGENT = "aabbccddeeff112233445566";

    private IRuntime runtime;
    private IAgentFactory agentFactory;
    private IDeploymentStore deploymentStore;
    private TenantQuotaService tenantQuotaService;
    private RestAgentAdministration admin;

    @BeforeEach
    void setUp() throws Exception {
        runtime = mock(IRuntime.class);
        agentFactory = mock(IAgentFactory.class);
        deploymentStore = mock(IDeploymentStore.class);
        tenantQuotaService = mock(TenantQuotaService.class);

        lenient().when(tenantQuotaService.getDefaultTenantId()).thenReturn(TENANT);
        lenient().when(agentFactory.getAllLatestAgents(any())).thenReturn(List.of());
        lenient().when(deploymentStore.readDeploymentInfos(any())).thenReturn(List.of());
        lenient().when(tenantQuotaService.checkAgentQuota(anyString(), anyInt())).thenReturn(QuotaCheckResult.OK);

        admin = new RestAgentAdministration(runtime, agentFactory, deploymentStore,
                mock(IConversationMemoryStore.class), mock(IRestConversationStore.class),
                mock(IDocumentDescriptorStore.class), mock(IDeploymentListener.class),
                mock(IScheduleStore.class), tenantQuotaService);
    }

    private static DeploymentInfo deployed(String agentId, int version) {
        DeploymentInfo info = new DeploymentInfo();
        info.setAgentId(agentId);
        info.setAgentVersion(version);
        info.setDeploymentStatus(DeploymentInfo.DeploymentStatus.deployed);
        return info;
    }

    private static IAgent liveAgent(String agentId) {
        IAgent agent = mock(IAgent.class);
        lenient().when(agent.getAgentId()).thenReturn(agentId);
        lenient().when(agent.getDeploymentStatus()).thenReturn(Deployment.Status.READY);
        return agent;
    }

    private void denyAt(int expectedCount) {
        when(tenantQuotaService.checkAgentQuota(eq(TENANT), eq(expectedCount)))
                .thenReturn(QuotaCheckResult.denied("Agent limit (" + expectedCount + ") reached"));
    }

    @Nested
    @DisplayName("denial")
    class Denial {

        @Test
        @DisplayName("throws QuotaExceededException (not 500) and submits nothing")
        void deniesNewAgent() throws Exception {
            when(deploymentStore.readDeploymentInfos(any())).thenReturn(List.of(deployed("agent-a", 1), deployed("agent-b", 1)));
            denyAt(2);

            QuotaExceededException thrown = assertThrows(QuotaExceededException.class,
                    () -> admin.deployAgent(Deployment.Environment.production, NEW_AGENT, 1, true, false));

            // assertThrows already pins the type: if the gate sat inside deployAgent's
            // try block, catch(Exception) would have rethrown InternalServerErrorException
            // and this would fail — which is exactly the 429-becomes-500 regression.
            assertEquals("Agent limit (2) reached", thrown.getMessage());
            verify(runtime, never()).submitCallable(any(Callable.class), any());
        }
    }

    @Nested
    @DisplayName("counting")
    class Counting {

        @Test
        @DisplayName("counts distinct agent ids — two versions of one agent count once")
        void twoVersionsCountOnce() throws Exception {
            when(deploymentStore.readDeploymentInfos(any())).thenReturn(List.of(deployed("agent-a", 1), deployed("agent-a", 2)));

            admin.deployAgent(Deployment.Environment.production, NEW_AGENT, 1, true, false);

            verify(tenantQuotaService).checkAgentQuota(TENANT, 1);
        }

        @Test
        @DisplayName("redeploying an already-counted agent skips the quota entirely")
        void redeployIsFree() throws Exception {
            when(deploymentStore.readDeploymentInfos(any())).thenReturn(List.of(deployed(NEW_AGENT, 1)));
            denyAt(1);

            admin.deployAgent(Deployment.Environment.production, NEW_AGENT, 2, true, false);

            verify(tenantQuotaService, never()).checkAgentQuota(anyString(), anyInt());
            verify(runtime).submitCallable(any(Callable.class), any());
        }

        @Test
        @DisplayName("LOOPHOLE: live agents with no deployed row still count")
        void autoDeployFalseAgentsCount() throws Exception {
            // autoDeploy=false never writes a `deployed` row, but the in-memory deploy
            // is unconditional and getLatestReadyAgent serves such agents without
            // consulting the store. Counting rows alone would allow unlimited agents.
            // Build the agent mocks BEFORE the outer when(...), or their own stubbing
            // runs inside it and Mockito reports UnfinishedStubbing.
            List<IAgent> ghosts = List.of(liveAgent("ghost-a"), liveAgent("ghost-b"));
            when(deploymentStore.readDeploymentInfos(any())).thenReturn(List.of());
            when(agentFactory.getAllLatestAgents(Deployment.Environment.production)).thenReturn(ghosts);

            admin.deployAgent(Deployment.Environment.production, NEW_AGENT, 1, false, false);

            verify(tenantQuotaService).checkAgentQuota(TENANT, 2);
        }

        @Test
        @DisplayName("an agent both persisted and live is not double counted")
        void noDoubleCount() throws Exception {
            List<IAgent> live = List.of(liveAgent("agent-a"));
            when(deploymentStore.readDeploymentInfos(any())).thenReturn(List.of(deployed("agent-a", 1)));
            when(agentFactory.getAllLatestAgents(Deployment.Environment.production)).thenReturn(live);

            admin.deployAgent(Deployment.Environment.production, NEW_AGENT, 1, true, false);

            verify(tenantQuotaService).checkAgentQuota(TENANT, 1);
        }

        @Test
        @DisplayName("non-READY agents are not counted")
        void skipsNonReadyAgents() throws Exception {
            IAgent inProgress = mock(IAgent.class);
            lenient().when(inProgress.getAgentId()).thenReturn("pending-a");
            when(inProgress.getDeploymentStatus()).thenReturn(Deployment.Status.IN_PROGRESS);
            List<IAgent> pending = List.of(inProgress);
            when(agentFactory.getAllLatestAgents(Deployment.Environment.production)).thenReturn(pending);

            admin.deployAgent(Deployment.Environment.production, NEW_AGENT, 1, true, false);

            verify(tenantQuotaService).checkAgentQuota(TENANT, 0);
        }

        @Test
        @DisplayName("rows with a null agent id are skipped")
        void skipsNullAgentIds() throws Exception {
            when(deploymentStore.readDeploymentInfos(any())).thenReturn(List.of(deployed(null, 1), deployed("agent-a", 1)));

            admin.deployAgent(Deployment.Environment.production, NEW_AGENT, 1, true, false);

            verify(tenantQuotaService).checkAgentQuota(TENANT, 1);
        }
    }

    @Nested
    @DisplayName("failure isolation")
    class FailureIsolation {

        @Test
        @DisplayName("fails open when the deployment store errors")
        void failsOpenOnStoreError() throws Exception {
            when(deploymentStore.readDeploymentInfos(any())).thenThrow(new IResourceStore.ResourceStoreException("mongo down"));

            admin.deployAgent(Deployment.Environment.production, NEW_AGENT, 1, true, false);

            verify(tenantQuotaService, never()).checkAgentQuota(anyString(), anyInt());
            verify(runtime).submitCallable(any(Callable.class), any());
        }
    }
}
