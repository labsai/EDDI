/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.rest.IRestConversationStore;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.model.DeploymentImpact;
import ai.labs.eddi.engine.model.DeploymentImpact.Outcome;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.internal.IDeploymentListener;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Undeploy and the deployment impact preview, once conversations can move
 * between compatible versions: a conversation that has somewhere to go neither
 * blocks an undeploy nor is ended by it, and nothing changes for one that has
 * not.
 */
class RestAgentAdministrationVersionFollowingTest {

    private static final Deployment.Environment ENV = Deployment.Environment.production;
    private static final String AGENT = "agent-1";

    private IAgentFactory agentFactory;
    private IAgentStore agentStore;
    private IDeploymentStore deploymentStore;
    private IConversationMemoryStore conversationMemoryStore;
    private IRestConversationStore restConversationStore;
    private IScheduleStore scheduleStore;
    private IRuntime runtime;
    private RestAgentAdministration admin;
    private final List<DeploymentInfo> records = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        agentFactory = mock(IAgentFactory.class);
        agentStore = mock(IAgentStore.class);
        deploymentStore = mock(IDeploymentStore.class);
        conversationMemoryStore = mock(IConversationMemoryStore.class);
        restConversationStore = mock(IRestConversationStore.class);
        scheduleStore = mock(IScheduleStore.class);
        runtime = mock(IRuntime.class);
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(records);
        when(agentFactory.getAllDeployedAgents(any())).thenReturn(List.of());
        when(conversationMemoryStore.getActiveConversationCount(anyString(), any())).thenReturn(0L);
        // Run the undeploy inline so its side effects (schedules) can be asserted.
        when(runtime.submitCallable(any(Callable.class), any())).thenAnswer(invocation -> {
            ((Callable<Void>) invocation.getArgument(0)).call();
            return CompletableFuture.completedFuture(null);
        });
        admin = new RestAgentAdministration(runtime, agentFactory, agentStore, deploymentStore, conversationMemoryStore,
                restConversationStore, mock(IDocumentDescriptorStore.class), mock(IDeploymentListener.class), scheduleStore,
                mock(TenantQuotaService.class), mock(ResourceAccessGuard.class));
    }

    private void version(int version, Integer generation) throws Exception {
        var config = new AgentConfiguration();
        config.setCompatibilityGeneration(generation);
        when(agentStore.read(AGENT, version)).thenReturn(config);
    }

    private void deployed(int version) {
        var info = new DeploymentInfo();
        info.setAgentId(AGENT);
        info.setAgentVersion(version);
        info.setEnvironment(ENV);
        info.setDeploymentStatus(DeploymentInfo.DeploymentStatus.deployed);
        records.add(info);
    }

    private void activeConversations(int version, long count) {
        when(conversationMemoryStore.getActiveConversationCount(AGENT, version)).thenReturn(count);
    }

    @Nested
    @DisplayName("undeploy")
    class Undeploy {

        @Test
        @DisplayName("conversations with a compatible version deployed move there: no 409, nothing ended")
        void compatibleSuccessorAbsorbsConversations() throws Exception {
            version(1, 2);
            version(2, 2);
            deployed(1);
            deployed(2);
            activeConversations(1, 5);

            Response response = admin.undeployAgent(ENV, AGENT, 1, false, false);

            assertEquals(202, response.getStatus());
            verify(restConversationStore, never()).endActiveConversations(any(), any());
            verify(agentFactory).undeployAgent(ENV, AGENT, 1);
        }

        @Test
        @DisplayName("with endAllActiveConversations, conversations that can move are still not ended")
        void compatibleSuccessorNotEndedEvenWithEndAll() throws Exception {
            version(1, 2);
            version(2, 2);
            deployed(1);
            deployed(2);
            activeConversations(1, 5);

            assertEquals(202, admin.undeployAgent(ENV, AGENT, 1, true, false).getStatus());

            verify(restConversationStore, never()).endActiveConversations(any(), any());
        }

        /** Guarantee: nothing changes for conversations that have nowhere to go. */
        @Test
        @DisplayName("a breaking successor absorbs nothing: 409 as before")
        void breakingSuccessorStill409() throws Exception {
            version(1, 2);
            version(2, 3);
            deployed(1);
            deployed(2);
            activeConversations(1, 5);

            assertEquals(409, admin.undeployAgent(ENV, AGENT, 1, false, false).getStatus());
            verify(agentFactory, never()).undeployAgent(any(), anyString(), any());
        }

        @Test
        @DisplayName("a version without a generation absorbs nothing, and is absorbed by nothing")
        void legacyVersionsStill409() throws Exception {
            version(1, null);
            version(2, null);
            deployed(1);
            deployed(2);
            activeConversations(1, 5);

            assertEquals(409, admin.undeployAgent(ENV, AGENT, 1, false, false).getStatus());
        }

        @Test
        @DisplayName("ending conversations that have nowhere to go records that their version was retired")
        void endedWithRetiredReason() throws Exception {
            version(1, 2);
            deployed(1);
            activeConversations(1, 5);
            when(restConversationStore.getActiveConversations(AGENT, 1)).thenReturn(List.of());
            when(restConversationStore.endActiveConversations(any(), any())).thenReturn(Response.ok().build());

            assertEquals(202, admin.undeployAgent(ENV, AGENT, 1, true, false).getStatus());

            verify(restConversationStore).endActiveConversations(any(), eq(IConversationService.END_REASON_AGENT_VERSION_RETIRED));
        }

        /**
         * The undeploys of one call run asynchronously: while the loop looks at v1, the
         * record for v2 — undeployed a moment ago by this same call — may still read
         * "deployed". Counting it as a successor would strand v1's conversations on two
         * versions that are both about to be gone.
         */
        @Test
        @DisplayName("a version undeployed by the same call is never a successor")
        void sameCallVersionsAreNotSuccessors() throws Exception {
            version(1, 2);
            version(2, 2);
            deployed(1);
            deployed(2);
            activeConversations(1, 5);

            assertEquals(409, admin.undeployAgent(ENV, AGENT, 2, false, true).getStatus());
        }

        @Test
        @DisplayName("a compatible version deployed without a record (autoDeploy=false) counts as a successor")
        void unrecordedRuntimeDeploymentCounts() throws Exception {
            version(1, 2);
            version(2, 2);
            deployed(1);
            IAgent running = mock(IAgent.class);
            when(running.getAgentId()).thenReturn(AGENT);
            when(running.getAgentVersion()).thenReturn(2);
            when(running.getDeploymentStatus()).thenReturn(Deployment.Status.READY);
            when(agentFactory.getAllDeployedAgents(ENV)).thenReturn(List.of(running));
            activeConversations(1, 5);

            assertEquals(202, admin.undeployAgent(ENV, AGENT, 1, false, false).getStatus());
        }

        @Test
        @DisplayName("a compatible version in another environment is not a successor")
        void otherEnvironmentIsNotASuccessor() throws Exception {
            version(1, 2);
            version(2, 2);
            deployed(1);
            var testDeployment = new DeploymentInfo();
            testDeployment.setAgentId(AGENT);
            testDeployment.setAgentVersion(2);
            testDeployment.setEnvironment(Deployment.Environment.test);
            records.add(testDeployment);
            activeConversations(1, 5);

            assertEquals(409, admin.undeployAgent(ENV, AGENT, 1, false, false).getStatus());
        }

        /**
         * Schedules belong to the agent. Disabling them on any undeploy switched off
         * every heartbeat each time an old version was retired after a new one went
         * live — the normal way to roll a version out.
         */
        @Test
        @DisplayName("schedules stay enabled while another version remains deployed")
        void schedulesSurviveRetiringAnOldVersion() throws Exception {
            version(1, 2);
            version(2, 3);
            deployed(1);
            deployed(2);
            var schedule = new ScheduleConfiguration();
            schedule.setEnabled(true);
            when(scheduleStore.readSchedulesByAgentId(AGENT)).thenReturn(List.of(schedule));

            assertEquals(202, admin.undeployAgent(ENV, AGENT, 1, false, false).getStatus());

            verify(scheduleStore, never()).setScheduleEnabled(any(), eq(false), any(), any());
        }

        @Test
        @DisplayName("schedules are disabled when the last deployed version goes")
        void schedulesDisabledWithTheLastVersion() throws Exception {
            version(1, 2);
            deployed(1);
            var schedule = new ScheduleConfiguration();
            schedule.setId("sched-1");
            schedule.setEnabled(true);
            when(scheduleStore.readSchedulesByAgentId(AGENT)).thenReturn(List.of(schedule));

            assertEquals(202, admin.undeployAgent(ENV, AGENT, 1, false, false).getStatus());

            verify(scheduleStore).setScheduleEnabled("sched-1", false, null, ScheduleConfiguration.DISABLED_BY_UNDEPLOY);
        }
    }

    @Nested
    @DisplayName("deployment impact")
    class Impact {

        @Test
        @DisplayName("older versions of the same generation FOLLOW; other generations and newer versions STAY")
        void classifiesEveryDeployedVersion() throws Exception {
            version(1, 1);
            version(2, 2);
            version(3, 2);
            version(4, 2);
            version(5, 2);
            deployed(1);
            deployed(2);
            deployed(3);
            deployed(5);
            activeConversations(1, 7);
            activeConversations(2, 4);
            activeConversations(3, 2);
            activeConversations(5, 1);

            DeploymentImpact impact = admin.getDeploymentImpact(ENV, AGENT, 4);

            assertEquals(2, impact.compatibilityGeneration());
            var rows = impact.deployedVersions();
            assertEquals(List.of(5, 3, 2, 1), rows.stream().map(DeploymentImpact.VersionImpact::version).toList(),
                    "every other deployed version, highest first");
            assertEquals(Outcome.STAY, rows.get(0).outcome(), "v5 is newer: its conversations stay on it");
            assertEquals(Outcome.FOLLOW, rows.get(1).outcome());
            assertEquals(Outcome.FOLLOW, rows.get(2).outcome());
            assertEquals(4, rows.get(2).activeConversations());
            assertEquals(Outcome.STAY, rows.get(3).outcome(), "v1 is another generation: a breaking change");
            assertEquals(7, rows.get(3).activeConversations());
        }

        @Test
        @DisplayName("a version without a generation is followed by nothing")
        void legacyTargetFollowsNothing() throws Exception {
            version(1, null);
            version(2, null);
            deployed(1);
            activeConversations(1, 3);

            DeploymentImpact impact = admin.getDeploymentImpact(ENV, AGENT, 2);

            assertNull(impact.compatibilityGeneration());
            assertEquals(Outcome.STAY, impact.deployedVersions().getFirst().outcome());
        }

        @Test
        @DisplayName("the version itself is not listed, deployed or not")
        void excludesItself() throws Exception {
            version(2, 1);
            deployed(2);

            assertEquals(List.of(), admin.getDeploymentImpact(ENV, AGENT, 2).deployedVersions());
        }
    }
}
