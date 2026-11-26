/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.DiscussionOutcome;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.lifecycle.model.DiscussionControlToken;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How a leg settles when it has run its phases: a cancel the phase loop never
 * saw still wins, a blank synthesis is no answer, and an answer whose
 * completion write did not commit is not history.
 */
class GroupConversationServiceSettlementTest {

    private IGroupConversationStore conversationStore;
    private IAgentGroupStore groupStore;
    private GroupConversationService service;

    @BeforeEach
    void setUp() {
        conversationStore = mock(IGroupConversationStore.class);
        groupStore = mock(IAgentGroupStore.class);
        service = new GroupConversationService(groupStore, conversationStore, mock(IConversationService.class),
                mock(IAgentFactory.class), mock(ITemplatingEngine.class), mock(IJsonSerialization.class), new SimpleMeterRegistry(),
                mock(AgentSigningService.class), mock(IAgentStore.class), mock(IScheduleStore.class), null,
                new CallerIdentityContext(null, null), "default", 3);
    }

    private static GroupConversation running() {
        var gc = new GroupConversation();
        gc.setId("gc-1");
        gc.setGroupId("group-1");
        gc.setUserId("user-1");
        gc.setState(GroupConversationState.IN_PROGRESS);
        gc.setAnsweredRounds(0);
        return gc;
    }

    private static TranscriptEntry synthesis(String content) {
        return new TranscriptEntry("mod", "Moderator", content, 0, "Synthesis", TranscriptEntryType.SYNTHESIS, Instant.now(), null, null);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, DiscussionControlToken> tokens() throws Exception {
        var field = GroupConversationService.class.getDeclaredField("discussionControls");
        field.setAccessible(true);
        return (ConcurrentHashMap<String, DiscussionControlToken>) field.get(service);
    }

    @Test
    void aResumePastTheLastPhase_honoursACancelThatLandedBeforeItRan() throws Exception {
        var gc = running();
        var token = new DiscussionControlToken();
        token.setSignal(ControlSignal.CANCEL_GRACEFUL);
        tokens().put(gc.getId(), token);

        // startPhaseIndex == phases.size(): the loop runs no iteration.
        var result = service.executeDiscussion(gc, new AgentGroupConfiguration(), List.of(), "Q?", null, 0);

        assertEquals(GroupConversationState.CANCELLED, result.getState());
        assertNull(result.getOutcome(), "a cancelled round has no outcome");
        verify(conversationStore, never()).updateIfState(any(), eq(GroupConversationState.COMPLETED));
    }

    /**
     * A store that throws for a missing subgroup (the historized one does), rather
     * than returning null, still reaches the parent as "not found", so the parent
     * refunds the turns it claimed for the child.
     */
    @Test
    void aSubgroupTheStoreReportsMissing_isNotFound_forBothLookups() throws Exception {
        when(groupStore.getCurrentResourceId("gone")).thenThrow(new IResourceStore.ResourceNotFoundException("no such group"));

        assertThrows(GroupConversationService.NestedGroupNotFoundException.class,
                () -> service.discussNested("gone", "Q?", "user-1", 1, null, null, null, null, null, 10));

        var id = mock(IResourceStore.IResourceId.class);
        when(id.getVersion()).thenReturn(3);
        when(groupStore.getCurrentResourceId("versionless")).thenReturn(id);
        when(groupStore.read("versionless", 3)).thenThrow(new IResourceStore.ResourceNotFoundException("no such version"));

        assertThrows(GroupConversationService.NestedGroupNotFoundException.class,
                () -> service.discussNested("versionless", "Q?", "user-1", 1, null, null, null, null, null, 10));
    }

    @Test
    void aBlankSynthesis_isNotTheAnswer_andDoesNotReplaceAnEarlierOne() throws Exception {
        var gc = running();
        gc.getTranscript().add(synthesis("the real answer"));
        gc.getTranscript().add(synthesis("   "));

        var result = service.executeDiscussion(gc, new AgentGroupConfiguration(), List.of(), "Q?", null, 0);

        assertEquals("the real answer", result.getSynthesizedAnswer());

        var blankOnly = running();
        blankOnly.setId("gc-2");
        blankOnly.getTranscript().add(synthesis(""));

        var blankResult = service.executeDiscussion(blankOnly, new AgentGroupConfiguration(), List.of(), "Q?", null, 0);

        assertNull(blankResult.getSynthesizedAnswer());
        assertEquals(DiscussionOutcome.NO_ANSWER, blankResult.getOutcome());
        assertEquals(0, blankResult.getAnsweredRounds());
    }

    @Test
    void anAnswerWhoseCompletionWriteFailed_isNotCountedAsAnEarlierAnswer() throws Exception {
        var gc = running();
        gc.getTranscript().add(synthesis("the answer"));
        // The completion write fails without committing; the store recovers for the
        // failure write, and still holds the running state.
        doThrow(new IResourceStore.ResourceStoreException("store down")).doNothing().when(conversationStore).updateIfState(any(), any());
        var stored = running();
        when(conversationStore.read("gc-1")).thenReturn(stored);

        assertThrows(Exception.class, () -> service.executeDiscussion(gc, new AgentGroupConfiguration(), List.of(), "Q?", null, 0));

        assertEquals(0, gc.getAnsweredRounds(), "the first round's answer never committed");
        assertEquals(GroupConversationState.FAILED, gc.getState(), "no round of this discussion answered");
        assertEquals(DiscussionOutcome.FAILED, gc.getOutcome());
    }

    @Test
    void aCancelAfterAFailedCompletionWrite_leavesNoOutcome() throws Exception {
        var gc = running();
        gc.getTranscript().add(synthesis("the answer"));
        var token = new DiscussionControlToken();
        tokens().put(gc.getId(), token);
        // The cancel lands while the completion write runs, and that write fails.
        var first = new AtomicBoolean(true);
        doAnswer(inv -> {
            if (first.getAndSet(false)) {
                token.setSignal(ControlSignal.CANCEL_GRACEFUL);
                throw new IResourceStore.ResourceStoreException("store down");
            }
            return null;
        }).when(conversationStore).updateIfState(any(), any());

        var result = service.executeDiscussion(gc, new AgentGroupConfiguration(), List.of(), "Q?", null, 0);

        assertEquals(GroupConversationState.CANCELLED, result.getState());
        assertNull(result.getOutcome(), "settle had classified the round, but a cancelled round has no outcome");
        assertNull(result.getOutcomeReason());
    }

    @Test
    void aFailedCompletionWrite_keepsAnswersThatDidCommit() throws Exception {
        var gc = running();
        gc.setAnsweredRounds(1);
        gc.setRound(2);
        gc.getTranscript().add(synthesis("round two"));
        doThrow(new IResourceStore.ResourceStoreException("store down")).doNothing().when(conversationStore).updateIfState(any(), any());
        var stored = running();
        when(conversationStore.read("gc-1")).thenReturn(stored);

        assertThrows(Exception.class, () -> service.executeDiscussion(gc, new AgentGroupConfiguration(), List.of(), "Q?", null, 0));

        assertEquals(1, gc.getAnsweredRounds());
        assertEquals(GroupConversationState.COMPLETED, gc.getState(), "an earlier round answered: the discussion stays continuable");
    }
}
