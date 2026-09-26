/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionStyle;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionEventListener;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.lifecycle.model.DiscussionControlToken;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H14a / H14b — a running discussion leg must never overwrite a state another
 * writer committed, and must never recreate a document that was deleted under
 * it.
 * <p>
 * The store here is a small compare-and-swap fake over one persisted state, so
 * the tests observe what actually reaches "the database": {@code update()}
 * writes blindly (as the old upsert did), {@code updateIfState()} writes only
 * when the persisted state matches.
 */
class GroupConversationStateRaceTest {

    private static final String GROUP_ID = "group-1";
    private static final String GC_ID = "gc-1";

    private IAgentGroupStore groupStore;
    private IGroupConversationStore conversationStore;
    private IConversationService conversationService;
    private IAgentFactory agentFactory;
    private GroupConversationService service;

    /** The one persisted fact the fake store tracks. */
    private final AtomicReference<GroupConversationState> persisted = new AtomicReference<>();
    private final AtomicBoolean deleted = new AtomicBoolean();
    private final AtomicInteger blindWrites = new AtomicInteger();
    /**
     * When set, the next read returns what it sees and THEN a racing cancel
     * commits.
     */
    private final AtomicBoolean cancelAfterNextRead = new AtomicBoolean();

    @BeforeEach
    void setUp() throws Exception {
        groupStore = mock(IAgentGroupStore.class);
        conversationStore = mock(IGroupConversationStore.class);
        conversationService = mock(IConversationService.class);
        agentFactory = mock(IAgentFactory.class);
        var templatingEngine = mock(ITemplatingEngine.class);
        var jsonSerialization = mock(IJsonSerialization.class);
        service = new GroupConversationService(groupStore, conversationStore, conversationService, agentFactory,
                templatingEngine, jsonSerialization, new SimpleMeterRegistry(), null, mock(IAgentStore.class), null, null,
                null, new CallerIdentityContext(null, null), "default", 3);

        lenient().when(jsonSerialization.serialize(any())).thenAnswer(inv -> inv.getArgument(0).toString());
        lenient().when(templatingEngine.processTemplate(anyString(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0, String.class));

        when(conversationStore.create(any())).thenAnswer(inv -> {
            persisted.set(((GroupConversation) inv.getArgument(0)).getState());
            return GC_ID;
        });
        lenient().when(conversationStore.read(GC_ID)).thenAnswer(inv -> {
            if (deleted.get()) {
                throw new IResourceStore.ResourceNotFoundException("gone");
            }
            var gc = new GroupConversation();
            gc.setId(GC_ID);
            gc.setGroupId(GROUP_ID);
            gc.setState(persisted.get());
            if (cancelAfterNextRead.compareAndSet(true, false)) {
                // The other pod's cancelDiscussion (no local token): CAS the persisted
                // state IN_PROGRESS → CANCELLED and report success — landing just after
                // the leg's phase-boundary re-check saw IN_PROGRESS.
                persisted.compareAndSet(GroupConversationState.IN_PROGRESS, GroupConversationState.CANCELLED);
            }
            return gc;
        });
        // The pre-fix write: a whole-document upsert, blind to the persisted state.
        lenient().doAnswer(inv -> {
            blindWrites.incrementAndGet();
            persisted.set(((GroupConversation) inv.getArgument(0)).getState());
            deleted.set(false);
            return null;
        }).when(conversationStore).update(any());
        lenient().doAnswer(inv -> {
            if (deleted.get()) {
                throw new IGroupConversationStore.GroupConversationGoneException("gone", null);
            }
            GroupConversationState expected = inv.getArgument(1);
            if (persisted.get() != expected) {
                throw new IResourceStore.ResourceModifiedException("persisted is " + persisted.get());
            }
            persisted.set(((GroupConversation) inv.getArgument(0)).getState());
            return null;
        }).when(conversationStore).updateIfState(any(), any());

        var rid = mock(IResourceStore.IResourceId.class);
        when(rid.getVersion()).thenReturn(1);
        when(groupStore.getCurrentResourceId(GROUP_ID)).thenReturn(rid);
        var config = new AgentGroupConfiguration();
        config.setName("Race group");
        config.setStyle(DiscussionStyle.ROUND_TABLE);
        config.setMaxRounds(2);
        config.setMembers(List.of(new GroupMember("a1", "Alice", 1, null)));
        config.setProtocol(new ProtocolConfig(60, ProtocolConfig.MemberFailurePolicy.SKIP, 2,
                ProtocolConfig.MemberUnavailablePolicy.SKIP));
        when(groupStore.read(GROUP_ID, 1)).thenReturn(config);
    }

    /**
     * The member answers; {@code duringTurn} runs inside the turn, i.e. while the
     * leg is between two of its writes — exactly where a racing writer lands.
     */
    private void stubMember(Runnable duringTurn) throws Exception {
        when(agentFactory.getLatestReadyAgent(any(Environment.class), eq("a1"))).thenReturn(mock(IAgent.class));
        when(conversationService.startConversation(any(Environment.class), eq("a1"), anyString(), any()))
                .thenReturn(new IConversationService.ConversationResult("conv-a1", null));
        doAnswer(inv -> {
            duringTurn.run();
            ConversationResponseHandler handler = inv.getArgument(8);
            var snapshot = new SimpleConversationMemorySnapshot();
            var output = new ConversationOutput();
            output.put("output", List.of("An opinion."));
            snapshot.setConversationOutputs(new ArrayList<>(List.of(output)));
            handler.onComplete(snapshot);
            return null;
        }).when(conversationService).say(any(Environment.class), eq("a1"), anyString(), any(), any(), any(),
                any(InputData.class), anyBoolean(), any(ConversationResponseHandler.class));
    }

    @Test
    @DisplayName("H14a: a cancel committed on another pod mid-phase is not overwritten by the running leg")
    void crossPodCancel_isNotOverwrittenByTheRunningLeg() throws Exception {
        // The cancel lands in the check-then-act window: after the boundary re-read
        // (which therefore sees IN_PROGRESS), before the boundary write.
        stubMember(() -> cancelAfterNextRead.set(true));
        var listener = mock(GroupDiscussionEventListener.class);

        GroupConversation result = service.discuss(GROUP_ID, "Question?", "user-1", 0, listener);

        assertEquals(GroupConversationState.CANCELLED, persisted.get(),
                "the cancel the caller was told succeeded must stay committed — the leg used to write IN_PROGRESS "
                        + "back at the phase boundary and then COMPLETED");
        assertEquals(GroupConversationState.CANCELLED, result.getState());
        assertEquals(0, blindWrites.get(), "no running-leg write may be unconditional");
        verify(listener).onCancelled(any());
        verify(listener, never()).onGroupComplete(any());
        verify(listener, never()).onGroupError(any());
    }

    @Test
    @DisplayName("H14a: without a racing writer the discussion still completes through the conditional writes")
    void noRace_completesNormally() throws Exception {
        stubMember(() -> {
        });
        var listener = mock(GroupDiscussionEventListener.class);

        GroupConversation result = service.discuss(GROUP_ID, "Question?", "user-1", 0, listener);

        assertEquals(GroupConversationState.COMPLETED, result.getState());
        assertEquals(GroupConversationState.COMPLETED, persisted.get());
        assertEquals(0, blindWrites.get());
        verify(listener).onGroupComplete(any());
    }

    @Test
    @DisplayName("H14b: a discussion deleted mid-run is not recreated and ends as a cancel, not a failure")
    void deletedMidRun_isNotResurrected_andEndsAsCancelled() throws Exception {
        stubMember(() -> deleted.set(true));
        var listener = mock(GroupDiscussionEventListener.class);

        GroupConversation result = assertDoesNotThrow(() -> service.discuss(GROUP_ID, "Question?", "user-1", 0, listener),
                "a deletion is not a discussion failure — no GroupExecutionException / 5xx");

        assertTrue(deleted.get(), "the deleted document stays deleted — the next phase write used to upsert it back");
        assertEquals(0, blindWrites.get());
        assertEquals(GroupConversationState.CANCELLED, result.getState());
        verify(listener).onCancelled(any());
        verify(listener, never()).onGroupError(any());
    }

    @Test
    @DisplayName("H14b: deleting a running discussion signals its leg to stop immediately")
    @SuppressWarnings("unchecked")
    void deleteOfRunningDiscussion_cancelsTheLeg() throws Exception {
        persisted.set(GroupConversationState.IN_PROGRESS);
        var field = GroupConversationService.class.getDeclaredField("activeTokens");
        field.setAccessible(true);
        var tokens = (ConcurrentHashMap<String, DiscussionControlToken>) field.get(service);
        var leg = new DiscussionControlToken();
        tokens.put(GC_ID, leg);

        service.deleteGroupConversation(GC_ID);

        assertEquals(ControlSignal.CANCEL_IMMEDIATE, leg.getSignal(),
                "the first discuss() leg is not an operation-in-progress, so delete used to tear its members and "
                        + "agents down under it without ever telling it to stop");
        verify(conversationStore).delete(GC_ID);
    }
}
