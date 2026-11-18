/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ContextScope;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.MemberFailurePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.MemberUnavailablePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.configs.groups.model.GroupConversation.TurnOutcome;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionException;
import ai.labs.eddi.engine.internal.GroupConversationService;
import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink.SpeakerCompleteEvent;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A member turn's outcome (K1, K9 of the November 2026 review): a member whose
 * provider fails is a FAILED turn with no content and a stable reason — never
 * the old placeholder text that was signed, quoted to peers and taken as the
 * synthesis.
 */
class MemberTurnOutcomeTest {

    private static final String AGENT_A = "agent-a";

    private final IConversationService conversationService = Mockito.mock(IConversationService.class);
    private final IAgentFactory agentFactory = Mockito.mock(IAgentFactory.class);

    private MemberTurnExecutor executor() throws Exception {
        when(agentFactory.getLatestReadyAgent(any(), eq(AGENT_A))).thenReturn(Mockito.mock(IAgent.class));
        when(conversationService.startConversation(any(), eq(AGENT_A), any(), any()))
                .thenReturn(new IConversationService.ConversationResult("conv-1", null));
        return new MemberTurnExecutor(conversationService, agentFactory, new GroupSigningGuard(null, null, "default"),
                new GroupContextBuilder(null), Mockito.mock(GroupConversationService.class),
                new SimpleMeterRegistry().counter("test.member.pause.skipped"), 5, 2);
    }

    private static GroupConversation gc() {
        var gc = new GroupConversation();
        gc.setId("gc-1");
        gc.setGroupId("group-1");
        return gc;
    }

    private static GroupMember member() {
        return new GroupMember(AGENT_A, "Agent A", 1, null);
    }

    private static DiscussionPhase phase(PhaseType type) {
        return new DiscussionPhase("P-" + type, type, "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1, false);
    }

    private static ProtocolConfig protocol(MemberFailurePolicy policy, int maxRetries) {
        return new ProtocolConfig(5, policy, maxRetries, MemberUnavailablePolicy.SKIP);
    }

    /** The member's conversation ends in ERROR with no output keys. */
    private void memberAnswersWithErrorState() throws Exception {
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.ERROR);
        doAnswer(inv -> {
            inv.getArgument(8, ConversationResponseHandler.class).onComplete(snapshot);
            return null;
        }).when(conversationService).say(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void errorStateWithoutOutput_isAFailedTurnWithoutContent() throws Exception {
        var executor = executor();
        memberAnswersWithErrorState();

        TranscriptEntry entry = executor.executeAgentTurn(member(), gc(), "input", protocol(MemberFailurePolicy.SKIP, 1), 0,
                phase(PhaseType.SYNTHESIS), null, null);

        assertEquals(TurnOutcome.FAILED, entry.outcome());
        assertNull(entry.content(), "a failed turn has nothing a peer or the synthesis could quote");
        assertFalse(entry.answered());
        assertEquals(MemberTurnExecutor.REASON_ERROR_STATE, entry.errorReason());
        assertEquals(TranscriptEntryType.SKIPPED, entry.type(), "never a SYNTHESIS the answer extraction could pick");
    }

    @Test
    void errorStateWithoutOutput_countsAgainstTheRetryPolicy() throws Exception {
        var executor = executor();
        memberAnswersWithErrorState();

        TranscriptEntry entry = executor.executeAgentTurn(member(), gc(), "input", protocol(MemberFailurePolicy.RETRY, 2), 0,
                phase(PhaseType.OPINION), null, null);

        verify(conversationService, times(3)).say(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());
        assertEquals(TurnOutcome.FAILED, entry.outcome());
    }

    @Test
    void errorStateWithoutOutput_abortsUnderAbort() throws Exception {
        var executor = executor();
        memberAnswersWithErrorState();

        assertThrows(GroupDiscussionException.class, () -> executor.executeAgentTurn(member(), gc(), "input",
                protocol(MemberFailurePolicy.ABORT, 0), 0, phase(PhaseType.OPINION), null, null));
    }

    /**
     * K7: a RETRY after a timeout used to ask the same member conversation again
     * while the timed-out turn still ran — the retry queued behind it and both were
     * billed. A turn that cannot be stopped is now not retried.
     */
    @Test
    void timeoutRetry_isSkippedWhenTheTimedOutTurnCannotBeStopped() throws Exception {
        var executor = executor();
        // say never answers, and the cancel does not end the turn either.
        var protocol = new ProtocolConfig(1, MemberFailurePolicy.RETRY, 2, MemberUnavailablePolicy.SKIP);

        TranscriptEntry entry = executor.executeAgentTurn(member(), gc(), "input", protocol, 0, phase(PhaseType.OPINION), null, null);

        verify(conversationService, times(1)).say(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());
        verify(conversationService).cancelConversation(eq("conv-1"), any(), any());
        assertEquals(TurnOutcome.TIMED_OUT, entry.outcome());
        assertEquals(MemberTurnExecutor.REASON_TIMEOUT_NOT_RETRIED, entry.errorReason());
    }

    @Test
    void providerExceptionText_neverReachesTheTranscript() throws Exception {
        var executor = executor();
        doThrow(new IllegalStateException("upstream 503: key sk-live-123 rejected by api.example"))
                .when(conversationService).say(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());

        TranscriptEntry entry = executor.executeAgentTurn(member(), gc(), "input", protocol(MemberFailurePolicy.SKIP, 0), 0,
                phase(PhaseType.OPINION), null, null);

        assertEquals(TurnOutcome.FAILED, entry.outcome());
        assertEquals(MemberTurnExecutor.REASON_EXECUTION_FAILED, entry.errorReason());
        assertFalse(entry.errorReason().contains("sk-live"), entry.errorReason());
    }

    @Test
    void parallelErrorEntry_storesTheStableReasonNotTheMessage() throws Exception {
        TranscriptEntry entry = executor().errorEntry(member(), 0, phase(PhaseType.OPINION), "driver said: password=hunter2");

        assertEquals(TurnOutcome.FAILED, entry.outcome());
        assertEquals(MemberTurnExecutor.REASON_EXECUTION_FAILED, entry.errorReason());
    }

    @Test
    void notAnsweredEntries_neverCarryContent() {
        var entry = new TranscriptEntry(AGENT_A, "Agent A", "some text", 0, "P", TranscriptEntryType.OPINION, Instant.now(), null, null,
                null, null, null, null, null, TurnOutcome.FAILED);

        assertNull(entry.content());
        assertFalse(entry.answered());
    }

    @Test
    void entriesWithoutAnOutcome_deriveItFromTheirType() {
        assertEquals(TurnOutcome.ANSWERED,
                new TranscriptEntry(AGENT_A, "A", "x", 0, "P", TranscriptEntryType.OPINION, Instant.now(), null, null).outcome());
        assertEquals(TurnOutcome.SKIPPED,
                new TranscriptEntry(AGENT_A, "A", null, 0, "P", TranscriptEntryType.SKIPPED, Instant.now(), "r", null).outcome());
        assertEquals(TurnOutcome.FAILED,
                new TranscriptEntry(AGENT_A, "A", null, 0, "P", TranscriptEntryType.ERROR, Instant.now(), "r", null).outcome());
    }

    /** A transcript stored before the field existed still loads, as ANSWERED. */
    @Test
    void storedEntryWithoutOutcome_readsAsAnswered() throws Exception {
        String legacy = """
                {"transcript":[{"speakerAgentId":"agent-a","speakerDisplayName":"A","content":"an opinion","phaseIndex":0,
                "phaseName":"P","type":"OPINION"},{"speakerAgentId":"agent-b","phaseIndex":0,"type":"SKIPPED","errorReason":"Timeout"}]}
                """;
        var gc = new ObjectMapper().findAndRegisterModules().readValue(legacy, GroupConversation.class);

        assertEquals(TurnOutcome.ANSWERED, gc.getTranscript().get(0).outcome());
        assertEquals("an opinion", gc.getTranscript().get(0).content());
        assertEquals(TurnOutcome.SKIPPED, gc.getTranscript().get(1).outcome());
        assertNull(gc.getOutcome(), "a legacy discussion has no recorded outcome");
    }

    @Test
    void outcomeRoundTripsThroughJson() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var gc = gc();
        gc.getTranscript().add(TranscriptEntry.notAnswered(AGENT_A, "A", 0, "P", TranscriptEntryType.SKIPPED, TurnOutcome.TIMED_OUT,
                "Timeout after 5s", null));
        gc.setOutcome(GroupConversation.DiscussionOutcome.NO_ANSWER);
        gc.setOutcomeReason("TURN_LIMIT_REACHED");

        var read = mapper.readValue(mapper.writeValueAsString(gc), GroupConversation.class);

        assertEquals(TurnOutcome.TIMED_OUT, read.getTranscript().getFirst().outcome());
        assertEquals(GroupConversation.DiscussionOutcome.NO_ANSWER, read.getOutcome());
        assertEquals("TURN_LIMIT_REACHED", read.getOutcomeReason());
    }

    @Test
    void speakerComplete_carriesTheOutcomeOnEveryPath() {
        var failed = TranscriptEntry.notAnswered(AGENT_A, "A", 0, "P", TranscriptEntryType.SKIPPED, TurnOutcome.FAILED, "r", null);
        var timedOut = TranscriptEntry.notAnswered(AGENT_A, "A", 0, "P", TranscriptEntryType.SKIPPED, TurnOutcome.TIMED_OUT, "r", null);
        var skipped = TranscriptEntry.notAnswered(AGENT_A, "A", 0, "P", TranscriptEntryType.SKIPPED, TurnOutcome.SKIPPED, "r", null);
        var answered = new TranscriptEntry(AGENT_A, "A", "hello", 0, "P", TranscriptEntryType.CRITIQUE, Instant.now(), null, "agent-b");

        assertEquals(SpeakerCompleteEvent.OUTCOME_ERROR, SpeakerCompleteEvent.of(failed, 0, "P", null, null).outcome());
        assertEquals(SpeakerCompleteEvent.OUTCOME_TIMEOUT, SpeakerCompleteEvent.of(timedOut, 0, "P", null, null).outcome());
        assertEquals(SpeakerCompleteEvent.OUTCOME_SKIPPED, SpeakerCompleteEvent.of(skipped, 0, "P", null, null).outcome());
        var ok = SpeakerCompleteEvent.of(answered, 0, "P", "agent-b", "B");
        assertNull(ok.outcome());
        assertEquals("hello", ok.response());
        assertEquals("agent-b", ok.targetAgentId());
        assertNull(SpeakerCompleteEvent.of(failed, 0, "P", null, null).response());
    }
}
