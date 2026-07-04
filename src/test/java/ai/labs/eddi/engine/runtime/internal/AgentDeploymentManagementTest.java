/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.migration.IMigrationManager;
import ai.labs.eddi.configs.migration.ChannelConnectorMigration;
import ai.labs.eddi.configs.migration.V6QuteMigration;
import ai.labs.eddi.configs.migration.V6RenameMigration;
import ai.labs.eddi.configs.rules.IRuleSetStore;
import ai.labs.eddi.configs.rules.model.RuleConfiguration;
import ai.labs.eddi.configs.rules.model.RuleGroupConfiguration;
import ai.labs.eddi.configs.rules.model.RuleSetConfiguration;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.internal.readiness.IAgentsReadiness;
import ai.labs.eddi.engine.model.Deployment.Environment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("AgentDeploymentManagement Tests")
class AgentDeploymentManagementTest {

    private IDeploymentStore deploymentStore;
    private IAgentFactory agentFactory;
    private IAgentStore agentStore;
    private IAgentsReadiness agentsReadiness;
    private IConversationMemoryStore conversationMemoryStore;
    private IDocumentDescriptorStore documentDescriptorStore;
    private IMigrationManager migrationManager;
    private V6RenameMigration v6RenameMigration;
    private V6QuteMigration v6QuteMigration;
    private ChannelConnectorMigration channelConnectorMigration;
    private IRuntime runtime;
    private IWorkflowStore workflowStore;
    private IRuleSetStore ruleSetStore;
    private AgentDeploymentManagement management;

    @BeforeEach
    void setUp() {
        deploymentStore = mock(IDeploymentStore.class);
        agentFactory = mock(IAgentFactory.class);
        agentStore = mock(IAgentStore.class);
        agentsReadiness = mock(IAgentsReadiness.class);
        conversationMemoryStore = mock(IConversationMemoryStore.class);
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        migrationManager = mock(IMigrationManager.class);
        v6RenameMigration = mock(V6RenameMigration.class);
        v6QuteMigration = mock(V6QuteMigration.class);
        channelConnectorMigration = mock(ChannelConnectorMigration.class);
        runtime = mock(IRuntime.class);
        workflowStore = mock(IWorkflowStore.class);
        ruleSetStore = mock(IRuleSetStore.class);

        var scheduler = mock(ScheduledExecutorService.class);
        when(runtime.getScheduledExecutorService()).thenReturn(scheduler);

        management = new AgentDeploymentManagement(
                deploymentStore, agentFactory, agentStore, agentsReadiness,
                conversationMemoryStore, documentDescriptorStore,
                migrationManager, v6RenameMigration, v6QuteMigration,
                channelConnectorMigration, runtime, workflowStore, ruleSetStore, 30);
    }

    @Nested
    @DisplayName("checkDeployments")
    class CheckDeploymentsTests {

        @Test
        @DisplayName("deploys new agents from deployment store")
        void deploysNewAgents() throws Exception {
            var info = new DeploymentInfo();
            info.setEnvironment(Environment.production);
            info.setAgentId("agent1");
            info.setAgentVersion(1);

            when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed))
                    .thenReturn(List.of(info));

            management.checkDeployments();

            verify(agentFactory).deployAgent(Environment.production, "agent1", 1, null);
        }

        @Test
        @DisplayName("skips agents with null agentId")
        void skipsNullAgentId() throws Exception {
            var info = new DeploymentInfo();
            info.setEnvironment(Environment.production);
            info.setAgentId(null);
            info.setAgentVersion(1);

            when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed))
                    .thenReturn(List.of(info));

            management.checkDeployments();

            verify(agentFactory, never()).deployAgent(any(), any(), anyInt(), any());
        }

        @Test
        @DisplayName("skips agents with null version")
        void skipsNullVersion() throws Exception {
            var info = new DeploymentInfo();
            info.setEnvironment(Environment.production);
            info.setAgentId("agent1");
            info.setAgentVersion(null);

            when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed))
                    .thenReturn(List.of(info));

            management.checkDeployments();

            verify(agentFactory, never()).deployAgent(any(), any(), anyInt(), any());
        }

        @Test
        @DisplayName("does not re-deploy already deployed agents")
        void doesNotRedeploy() throws Exception {
            var info = new DeploymentInfo();
            info.setEnvironment(Environment.production);
            info.setAgentId("agent1");
            info.setAgentVersion(1);

            when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed))
                    .thenReturn(List.of(info));

            // First call deploys
            management.checkDeployments();
            verify(agentFactory, times(1)).deployAgent(any(), any(), anyInt(), any());

            // Second call should not re-deploy
            management.checkDeployments();
            verify(agentFactory, times(1)).deployAgent(any(), any(), anyInt(), any());
        }

        @Test
        @DisplayName("handles ResourceStoreException gracefully")
        void handlesStoreException() throws Exception {
            when(deploymentStore.readDeploymentInfos(any()))
                    .thenThrow(new IResourceStore.ResourceStoreException("DB error"));

            assertDoesNotThrow(() -> management.checkDeployments());
        }

        @Test
        @DisplayName("handles deploy failure gracefully")
        void handlesDeployFailure() throws Exception {
            var info = new DeploymentInfo();
            info.setEnvironment(Environment.production);
            info.setAgentId("agent1");
            info.setAgentVersion(1);

            when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed))
                    .thenReturn(List.of(info));
            doThrow(new IllegalAccessException("Access denied"))
                    .when(agentFactory).deployAgent(any(), any(), anyInt(), any());

            assertDoesNotThrow(() -> management.checkDeployments());
        }

        @Test
        @DisplayName("marks stale deployment as undeployed when ResourceNotFoundException")
        void marksStaleDeployment() throws Exception {
            var info = new DeploymentInfo();
            info.setEnvironment(Environment.production);
            info.setAgentId("agent1");
            info.setAgentVersion(1);

            when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed))
                    .thenReturn(List.of(info));

            var rnfe = new IResourceStore.ResourceNotFoundException("Not found");
            doThrow(new IllegalStateException("Wrapped", rnfe))
                    .when(agentFactory).deployAgent(any(), any(), anyInt(), any());

            management.checkDeployments();

            verify(deploymentStore).setDeploymentInfo(
                    eq("production"), eq("agent1"), eq(1),
                    eq(DeploymentInfo.DeploymentStatus.undeployed));
        }
    }

    @Nested
    @DisplayName("checkDeployments — Task 15: inert hitlConfig lint")
    class InertHitlConfigDeployLintTests {

        private void stubDeploymentOf(String agentId, int version) throws Exception {
            var info = new DeploymentInfo();
            info.setEnvironment(Environment.production);
            info.setAgentId(agentId);
            info.setAgentVersion(version);

            when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed))
                    .thenReturn(List.of(info));
        }

        private AgentConfiguration agentWithHitlConfig(AgentConfiguration.HitlConfig hitlConfig, URI... workflowUris) {
            var agentConfiguration = new AgentConfiguration();
            agentConfiguration.setHitlConfig(hitlConfig);
            agentConfiguration.setWorkflows(List.of(workflowUris));
            return agentConfiguration;
        }

        private WorkflowConfiguration behaviorWorkflow(URI ruleSetUri) {
            var step = new WorkflowConfiguration.WorkflowStep();
            step.setType(URI.create("eddi://ai.labs.behavior"));
            step.setConfig(java.util.Map.of("uri", ruleSetUri.toString()));

            var workflowConfiguration = new WorkflowConfiguration();
            workflowConfiguration.setWorkflowSteps(List.of(step));
            return workflowConfiguration;
        }

        private RuleSetConfiguration ruleSetWithActions(String... actions) {
            var rule = new RuleConfiguration();
            rule.setName("r1");
            rule.setActions(List.of(actions));

            var group = new RuleGroupConfiguration();
            group.setRules(List.of(rule));

            var ruleSetConfiguration = new RuleSetConfiguration();
            ruleSetConfiguration.setBehaviorGroups(List.of(group));
            return ruleSetConfiguration;
        }

        @Test
        @DisplayName("no hitlConfig -> workflow/ruleset stores are never consulted, even though the agent has workflows")
        void noHitlConfigSkipsLint() throws Exception {
            stubDeploymentOf("agent1", 1);
            var workflowUri = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/aaaaaaaaaaaaaaaaaaaaaaaa?version=1");
            var agentConfiguration = agentWithHitlConfig(null, workflowUri);
            when(agentStore.read("agent1", 1)).thenReturn(agentConfiguration);

            management.checkDeployments();

            verify(workflowStore, never()).read(any(), any());
            verify(ruleSetStore, never()).read(any(), any());
        }

        @Test
        @DisplayName("hitlConfig set, no ruleset emits PAUSE_CONVERSATION, no requireApproval -> deployment still succeeds (non-fatal)")
        void inertHitlConfigDoesNotBlockDeployment() throws Exception {
            stubDeploymentOf("agent1", 1);
            var workflowUri = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/aaaaaaaaaaaaaaaaaaaaaaaa?version=1");
            var ruleSetUri = URI.create("eddi://ai.labs.rules/rulestore/rulesets/bbbbbbbbbbbbbbbbbbbbbbbb?version=1");

            var agentConfiguration = agentWithHitlConfig(new AgentConfiguration.HitlConfig(), workflowUri);
            when(agentStore.read("agent1", 1)).thenReturn(agentConfiguration);
            when(workflowStore.read("aaaaaaaaaaaaaaaaaaaaaaaa", 1)).thenReturn(behaviorWorkflow(ruleSetUri));
            when(ruleSetStore.read("bbbbbbbbbbbbbbbbbbbbbbbb", 1)).thenReturn(ruleSetWithActions("greet_user"));

            // Non-fatal: deployment must still be recorded even though the lint fires.
            management.checkDeployments();

            verify(agentFactory).deployAgent(Environment.production, "agent1", 1, null);
        }

        @Test
        @DisplayName("hitlConfig set AND a ruleset emits PAUSE_CONVERSATION -> ruleset store is still consulted but deploy proceeds normally")
        void rulesetEmittingPauseStillDeploys() throws Exception {
            stubDeploymentOf("agent1", 1);
            var workflowUri = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/aaaaaaaaaaaaaaaaaaaaaaaa?version=1");
            var ruleSetUri = URI.create("eddi://ai.labs.rules/rulestore/rulesets/bbbbbbbbbbbbbbbbbbbbbbbb?version=1");

            var agentConfiguration = agentWithHitlConfig(new AgentConfiguration.HitlConfig(), workflowUri);
            when(agentStore.read("agent1", 1)).thenReturn(agentConfiguration);
            when(workflowStore.read("aaaaaaaaaaaaaaaaaaaaaaaa", 1)).thenReturn(behaviorWorkflow(ruleSetUri));
            when(ruleSetStore.read("bbbbbbbbbbbbbbbbbbbbbbbb", 1))
                    .thenReturn(ruleSetWithActions(ai.labs.eddi.engine.lifecycle.IConversation.PAUSE_CONVERSATION));

            management.checkDeployments();

            verify(agentFactory).deployAgent(Environment.production, "agent1", 1, null);
            verify(ruleSetStore).read("bbbbbbbbbbbbbbbbbbbbbbbb", 1);
        }

        @Test
        @DisplayName("hitlConfig set with requireApproval configured -> no ruleset lookup needed to avoid the warning, deploy proceeds")
        void requireApprovalConfiguredStillDeploys() throws Exception {
            stubDeploymentOf("agent1", 1);
            var toolApprovals = new ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig();
            toolApprovals.setRequireApproval(List.of("mcp:*"));
            var hitlConfig = new AgentConfiguration.HitlConfig();
            hitlConfig.setToolApprovals(toolApprovals);

            var agentConfiguration = agentWithHitlConfig(hitlConfig);
            when(agentStore.read("agent1", 1)).thenReturn(agentConfiguration);

            management.checkDeployments();

            verify(agentFactory).deployAgent(Environment.production, "agent1", 1, null);
        }

        @Test
        @DisplayName("agentStore lookup failure during lint does not prevent deployment from being recorded")
        void agentStoreLookupFailureIsNonFatal() throws Exception {
            stubDeploymentOf("agent1", 1);
            when(agentStore.read("agent1", 1)).thenThrow(new RuntimeException("boom"));

            assertDoesNotThrow(() -> management.checkDeployments());

            verify(agentFactory).deployAgent(Environment.production, "agent1", 1, null);
        }
    }

    @Nested
    @DisplayName("autoDeployAgents")
    class AutoDeployTests {

        @Test
        @DisplayName("runs migrations before deployment")
        void runsMigrations() throws Exception {
            when(deploymentStore.readDeploymentInfos(any())).thenReturn(List.of());

            // migrationManager.startMigrationIfFirstTimeRun runs the callback
            doAnswer(inv -> {
                IMigrationManager.IMigrationFinished callback = inv.getArgument(0);
                callback.onComplete();
                return null;
            }).when(migrationManager).startMigrationIfFirstTimeRun(any());

            management.autoDeployAgents();

            verify(v6RenameMigration).runIfNeeded();
            verify(v6QuteMigration).runIfNeeded();
            verify(migrationManager).startMigrationIfFirstTimeRun(any());
        }
    }

    @Nested
    @DisplayName("endOldConversationsWithOldAgents — Finding #7")
    class IdleSweepHitlTests {

        @Test
        @DisplayName("skips AWAITING_HUMAN conversations (no raw ENDED write)")
        void skipsPausedConversations() throws Exception {
            var paused = snapshot("conv-paused", ai.labs.eddi.engine.memory.model.ConversationState.AWAITING_HUMAN);

            when(conversationMemoryStore.loadActiveConversationMemorySnapshot("agent1", 1))
                    .thenReturn(List.of(paused));

            invokeEndOldConversations("agent1", 1);

            // A paused conversation must never be force-ENDed by the idle sweep.
            verify(conversationMemoryStore, never())
                    .setConversationState(eq("conv-paused"), any());
            // And its descriptor is never even read (we short-circuit before that).
            verify(documentDescriptorStore, never()).readDescriptor(eq("agent1"), eq(1));
        }

        @Test
        @DisplayName("Task 14/3: skips a PENDING TOOL_CALL pause identically — the spare check only reads "
                + "ConversationState, never hitlPauseType, so undeploy is allowed the same way for both pause flavors")
        void skipsPausedToolCallConversation() throws Exception {
            var toolPaused = snapshot("conv-tool-paused", ai.labs.eddi.engine.memory.model.ConversationState.AWAITING_HUMAN);
            toolPaused.setHitlPauseType("TOOL_CALL");
            var batch = new ai.labs.eddi.engine.memory.model.PendingToolCallBatch();
            batch.setPauseEpoch("epoch-undeploy-sweep");
            toolPaused.setHitlPendingToolCalls(batch);

            when(conversationMemoryStore.loadActiveConversationMemorySnapshot("agent1", 1))
                    .thenReturn(List.of(toolPaused));

            invokeEndOldConversations("agent1", 1);

            // Same guarantee as the RULE-pause variant: never force-ENDed, descriptor
            // never read — the pending tool-call batch survives the sweep untouched so
            // a later redeploy + resume can still process it.
            verify(conversationMemoryStore, never())
                    .setConversationState(eq("conv-tool-paused"), any());
            verify(documentDescriptorStore, never()).readDescriptor(eq("agent1"), eq(1));
        }
    }

    private static ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot snapshot(
                                                                                        String id,
                                                                                        ai.labs.eddi.engine.memory.model.ConversationState state) {
        var s = new ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot();
        s.setId(id);
        s.setAgentId("agent1");
        s.setAgentVersion(1);
        s.setConversationState(state);
        return s;
    }

    private void invokeEndOldConversations(String agentId, Integer agentVersion) throws Exception {
        var m = AgentDeploymentManagement.class.getDeclaredMethod(
                "endOldConversationsWithOldAgents", String.class, Integer.class);
        m.setAccessible(true);
        m.invoke(management, agentId, agentVersion);
    }
}
