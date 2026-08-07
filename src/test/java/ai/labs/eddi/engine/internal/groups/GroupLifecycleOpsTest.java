/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.ISharedArtifactStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DynamicAgentConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.LifecyclePolicy;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.internal.GroupConversationService;
import ai.labs.eddi.engine.lifecycle.model.DiscussionControlToken;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Focused unit tests for {@link GroupLifecycleOps}, extracted from
 * {@code GroupConversationService} during the Wave R (R1 step 8) refactor.
 * Covers {@code cleanupEphemeralAgents}'s lifecycle-policy branches and
 * {@code failConversation}'s terminal-state alignment directly. {@code
 * propagateDynamicAgentTracking} already has 20+ dedicated tests in {@code
 * DynamicAgentTrackingPropagationTest} (calling it through the facade's static
 * delegator, which forwards here unchanged) and isn't duplicated. The
 * post-discussion entry points (followUpWithMember/continueDiscussion/
 * closeGroupConversation/delete) are already thoroughly exercised via
 * reflection through the facade's delegators by {@code
 * GroupConversationServiceExtendedTest}, {@code RestGroupConversation*Test} and
 * the MCP group/HITL tool suites — re-verified green against this class
 * post-extraction rather than duplicated here.
 *
 * @author tests
 */
class GroupLifecycleOpsTest {

    private static final String AGENT_A = "agent-a";

    private IAgentFactory agentFactory;
    private IAgentStore agentStore;
    private IGroupConversationStore conversationStore;

    private ISharedArtifactStore sharedArtifactStore;

    private GroupLifecycleOps ops() {
        agentFactory = mock(IAgentFactory.class);
        agentStore = mock(IAgentStore.class);
        conversationStore = mock(IGroupConversationStore.class);
        sharedArtifactStore = mock(ISharedArtifactStore.class);
        return new GroupLifecycleOps(conversationStore, mock(IAgentGroupStore.class),
                mock(IConversationService.class), agentFactory, agentStore, mock(IDeploymentStore.class),
                sharedArtifactStore,
                ConcurrentHashMap.newKeySet(), new ConcurrentHashMap<String, DiscussionControlToken>(),
                Mockito.mock(GroupConversationService.class),
                new SimpleMeterRegistry().counter("test.followup"),
                new SimpleMeterRegistry().counter("test.continue"),
                new SimpleMeterRegistry().counter("test.close"),
                new SimpleMeterRegistry().counter("test.failure"));
    }

    private GroupConversation gc(String... createdAgentIds) {
        var gc = new GroupConversation();
        gc.setId("gc-1");
        gc.getCreatedAgentIds().addAll(List.of(createdAgentIds));
        return gc;
    }

    private AgentGroupConfiguration configWithPolicy(LifecyclePolicy policy) {
        var config = new AgentGroupConfiguration();
        var dynamicAgents = new DynamicAgentConfig();
        dynamicAgents.setLifecyclePolicy(policy);
        config.setDynamicAgents(dynamicAgents);
        return config;
    }

    // =================================================================
    // cleanupEphemeralAgents
    // =================================================================

    @Test
    void cleanupEphemeralAgents_noCreatedAgents_doesNothing() {
        var ops = ops();
        ops.cleanupEphemeralAgents(gc(), configWithPolicy(LifecyclePolicy.EPHEMERAL));

        verifyNoInteractions(agentFactory, agentStore);
    }

    @Test
    void cleanupEphemeralAgents_ephemeralPolicy_undeploysAndDeletes() throws Exception {
        var ops = ops();
        ops.cleanupEphemeralAgents(gc(AGENT_A), configWithPolicy(LifecyclePolicy.EPHEMERAL));

        verify(agentFactory).undeployAgent(any(), eq(AGENT_A), isNull());
        verify(agentStore).deleteAllPermanently(AGENT_A);
    }

    @Test
    void cleanupEphemeralAgents_keepDeployedPolicy_noCleanup() {
        var ops = ops();
        ops.cleanupEphemeralAgents(gc(AGENT_A), configWithPolicy(LifecyclePolicy.KEEP_DEPLOYED));

        verifyNoInteractions(agentFactory, agentStore);
    }

    @Test
    void cleanupEphemeralAgents_undeployOnlyPolicy_undeploysButKeepsRecord() throws Exception {
        var ops = ops();
        ops.cleanupEphemeralAgents(gc(AGENT_A), configWithPolicy(LifecyclePolicy.UNDEPLOY_ONLY));

        verify(agentFactory).undeployAgent(any(), eq(AGENT_A), isNull());
        verify(agentStore, never()).deleteAllPermanently(any());
    }

    @Test
    void cleanupEphemeralAgents_agentDecidesPolicy_retainedAgentSkipped() {
        var ops = ops();
        var gc = gc(AGENT_A);
        gc.getRetainedAgentIds().add(AGENT_A);

        ops.cleanupEphemeralAgents(gc, configWithPolicy(LifecyclePolicy.AGENT_DECIDES));

        verifyNoInteractions(agentFactory, agentStore);
    }

    @Test
    void cleanupEphemeralAgents_agentDecidesPolicy_nonRetainedAgentCleaned() throws Exception {
        var ops = ops();
        var gc = gc(AGENT_A); // not added to retainedAgentIds

        ops.cleanupEphemeralAgents(gc, configWithPolicy(LifecyclePolicy.AGENT_DECIDES));

        verify(agentFactory).undeployAgent(any(), eq(AGENT_A), isNull());
        verify(agentStore).deleteAllPermanently(AGENT_A);
    }

    @Test
    void cleanupEphemeralAgents_undeployThrows_swallowedAndLogged() throws Exception {
        var ops = ops();
        doThrow(new RuntimeException("undeploy failed")).when(agentFactory).undeployAgent(any(), eq(AGENT_A), isNull());

        assertDoesNotThrow(() -> ops.cleanupEphemeralAgents(gc(AGENT_A), configWithPolicy(LifecyclePolicy.EPHEMERAL)));
    }

    // =================================================================
    // I17 — artifact cascade on lifecycle ends
    // =================================================================

    @Test
    void deleteGroupConversation_cascadesToArtifacts_beforeTheDocumentGoes() throws Exception {
        var ops = ops();
        var gc = gc();
        gc.setState(GroupConversationState.COMPLETED);
        doReturn(gc).when(conversationStore).read("gc-1");

        ops.deleteGroupConversation("gc-1");

        var order = inOrder(sharedArtifactStore, conversationStore);
        order.verify(sharedArtifactStore).deleteByGroupConversationId("gc-1");
        order.verify(conversationStore).delete("gc-1");
    }

    @Test
    void deleteGroupConversation_artifactCascadeFails_discussionIsStillDeleted() throws Exception {
        var ops = ops();
        var gc = gc();
        gc.setState(GroupConversationState.COMPLETED);
        doReturn(gc).when(conversationStore).read("gc-1");
        doThrow(new IResourceStore.ResourceStoreException("artifact store down"))
                .when(sharedArtifactStore).deleteByGroupConversationId("gc-1");

        assertDoesNotThrow(() -> ops.deleteGroupConversation("gc-1"),
                "a broken artifact store must not make discussions undeletable");
        verify(conversationStore).delete("gc-1");
    }

    @Test
    void closeGroupConversation_cascadesToArtifacts() throws Exception {
        var ops = ops();
        var gc = gc();
        gc.setState(GroupConversationState.COMPLETED);
        var closed = gc();
        closed.setState(GroupConversationState.CLOSED);
        // close re-reads after the CAS — the two-read stub is load-bearing.
        when(conversationStore.read("gc-1")).thenReturn(gc, closed);
        when(conversationStore.compareAndSetState("gc-1", GroupConversationState.COMPLETED, GroupConversationState.CLOSED))
                .thenReturn(true);

        ops.closeGroupConversation("gc-1");

        verify(sharedArtifactStore).deleteByGroupConversationId("gc-1");
    }

    // =================================================================
    // failConversation
    // =================================================================

    @Test
    void failConversation_nonTerminalPersistedState_updatesToFailed() throws Exception {
        var ops = ops();
        var gc = gc();
        gc.setState(GroupConversationState.IN_PROGRESS);
        var persisted = gc();
        persisted.setState(GroupConversationState.IN_PROGRESS);
        doReturn(persisted).when(conversationStore).read("gc-1");

        ops.failConversation(gc);

        assertEquals(GroupConversationState.FAILED, gc.getState());
        verify(conversationStore).updateIfState(gc, GroupConversationState.IN_PROGRESS);
    }

    @Test
    void failConversation_alreadyTerminalPersistedState_alignsInsteadOfOverwriting() throws Exception {
        var ops = ops();
        var gc = gc();
        gc.setState(GroupConversationState.IN_PROGRESS);
        var persisted = gc();
        persisted.setState(GroupConversationState.COMPLETED);
        doReturn(persisted).when(conversationStore).read("gc-1");

        ops.failConversation(gc);

        // Must align with the already-terminal persisted state, not overwrite it with
        // FAILED.
        assertEquals(GroupConversationState.COMPLETED, gc.getState());
        verify(conversationStore, never()).updateIfState(any(), any());
    }

    @Test
    void failConversation_storeReadThrows_fallsBackToInMemoryState() throws Exception {
        var ops = ops();
        var gc = gc();
        gc.setState(GroupConversationState.IN_PROGRESS);
        doThrow(new RuntimeException("store down")).when(conversationStore).read("gc-1");

        assertDoesNotThrow(() -> ops.failConversation(gc));
        verify(conversationStore).updateIfState(gc, GroupConversationState.IN_PROGRESS);
    }

    @Test
    void constructedWithMockedCollaborators_doesNotThrow() {
        assertDoesNotThrow(this::ops);
    }
}
