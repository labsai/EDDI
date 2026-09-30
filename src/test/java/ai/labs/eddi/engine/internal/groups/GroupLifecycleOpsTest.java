/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.DynamicOrigin;
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

    /**
     * The marker create_sub_agent stamps; cleanup deletes only agents carrying it
     * for this discussion.
     */
    private void stubOrigin(String agentId, String createdInGroupConversationId) throws Exception {
        IResourceStore.IResourceId current = mock(IResourceStore.IResourceId.class);
        when(current.getVersion()).thenReturn(1);
        when(agentStore.getCurrentResourceId(agentId)).thenReturn(current);
        var configuration = new AgentConfiguration();
        if (createdInGroupConversationId != null) {
            configuration.setDynamicOrigin(new DynamicOrigin("parent", "member-conv", createdInGroupConversationId, "user"));
        }
        when(agentStore.read(agentId, 1)).thenReturn(configuration);
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
        stubOrigin(AGENT_A, "gc-1");
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
        stubOrigin(AGENT_A, "gc-1");
        var gc = gc(AGENT_A); // not added to retainedAgentIds

        ops.cleanupEphemeralAgents(gc, configWithPolicy(LifecyclePolicy.AGENT_DECIDES));

        verify(agentFactory).undeployAgent(any(), eq(AGENT_A), isNull());
        verify(agentStore).deleteAllPermanently(AGENT_A);
    }

    @Test
    void cleanupEphemeralAgents_unmarkedAgent_undeployedButNeverDeleted() throws Exception {
        // Review #3: a created-list entry alone must not reach a permanent delete —
        // an agent without a dynamicOrigin may have been built by a person.
        var ops = ops();
        stubOrigin(AGENT_A, null);

        ops.cleanupEphemeralAgents(gc(AGENT_A), configWithPolicy(LifecyclePolicy.EPHEMERAL));

        verify(agentFactory).undeployAgent(any(), eq(AGENT_A), isNull());
        verify(agentStore, never()).deleteAllPermanently(any());
    }

    @Test
    void cleanupEphemeralAgents_agentFromAnotherDiscussion_leftAlone() throws Exception {
        var ops = ops();
        stubOrigin(AGENT_A, "some-other-discussion");

        ops.cleanupEphemeralAgents(gc(AGENT_A), configWithPolicy(LifecyclePolicy.EPHEMERAL));

        verifyNoInteractions(agentFactory);
        verify(agentStore, never()).deleteAllPermanently(any());
    }

    @Test
    void cleanupEphemeralAgents_unreadableOrigin_undeployedButNeverDeleted() throws Exception {
        var ops = ops();
        when(agentStore.getCurrentResourceId(AGENT_A)).thenThrow(new RuntimeException("store down"));

        ops.cleanupEphemeralAgents(gc(AGENT_A), configWithPolicy(LifecyclePolicy.EPHEMERAL));

        verify(agentFactory).undeployAgent(any(), eq(AGENT_A), isNull());
        verify(agentStore, never()).deleteAllPermanently(any());
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

    /**
     * A human rejection is terminal in its own right ({@code REJECTED}, added so
     * the Manager stops rendering a recorded decision as a red "Failed"), and an
     * operator has to be able to close it for exactly the reason a {@code FAILED}
     * one is closeable: close ends the member conversations and reclaims the
     * ephemeral agents. Leaving it out of the CAS chain would have made every
     * rejected discussion permanently uncloseable.
     */
    @Test
    void closeGroupConversation_acceptsARejectedDiscussion() throws Exception {
        var ops = ops();
        var gc = gc();
        gc.setState(GroupConversationState.REJECTED);
        var closed = gc();
        closed.setState(GroupConversationState.CLOSED);
        when(conversationStore.read("gc-1")).thenReturn(gc, closed);
        // Only the REJECTED -> CLOSED CAS succeeds; the others are tried and miss.
        when(conversationStore.compareAndSetState(eq("gc-1"), any(), eq(GroupConversationState.CLOSED)))
                .thenReturn(false);
        when(conversationStore.compareAndSetState("gc-1", GroupConversationState.REJECTED, GroupConversationState.CLOSED))
                .thenReturn(true);

        assertDoesNotThrow(() -> ops.closeGroupConversation("gc-1"));

        verify(conversationStore).compareAndSetState("gc-1", GroupConversationState.REJECTED, GroupConversationState.CLOSED);
        verify(sharedArtifactStore).deleteByGroupConversationId("gc-1");
    }

    /**
     * The message has to name the states the chain actually tries. It used to be a
     * hand-written sentence beside three hand-written {@code if} blocks, which is
     * how a state gets added to one and left out of the other.
     * <p>
     * Asserted against a LITERAL list, not against {@code CLOSEABLE_STATES}.
     * Iterating the same constant the message is built from passes for any contents
     * -- it pins the {@code .formatted(...)} wiring and nothing else. Pinning the
     * contents is the point: dropping a state from the chain has to fail here, and
     * the literal is what notices.
     */
    @Test
    void closeGroupConversation_refusalNamesEveryCloseableState() {
        var ops = ops();
        var gc = gc();
        gc.setState(GroupConversationState.IN_PROGRESS);
        try {
            when(conversationStore.read("gc-1")).thenReturn(gc);
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        var thrown = assertThrows(Exception.class, () -> ops.closeGroupConversation("gc-1"));

        assertEquals(
                List.of(GroupConversationState.COMPLETED, GroupConversationState.FAILED,
                        GroupConversationState.REJECTED, GroupConversationState.CANCELLED),
                GroupLifecycleOps.CLOSEABLE_STATES,
                "a terminal state that can be closed must be in the chain, in this order");
        for (var state : List.of("COMPLETED", "FAILED", "REJECTED", "CANCELLED")) {
            assertTrue(thrown.getMessage().contains(state),
                    "the refusal must name " + state + ", which the CAS chain tries: " + thrown.getMessage());
        }
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
