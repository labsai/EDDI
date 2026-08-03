/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
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
import ai.labs.eddi.engine.security.CallerIdentityContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Focused unit tests for {@link PhaseExecutionEngine}, extracted from
 * {@code GroupConversationService} during the Wave R (R1 step 5) refactor.
 * Mocks {@link MemberTurnExecutor} and {@link GroupContextBuilder} rather than
 * constructing real ones — their own behavior is covered by their dedicated
 * test classes; these tests verify the phase-execution turn-order logic
 * (sequential budget/ordering, parallel fan-out/fan-in, peer-targeted
 * self-exclusion) in isolation.
 *
 * @author tests
 */
class PhaseExecutionEngineTest {

    private static final String GROUP_ID = "group-1";

    private MemberTurnExecutor memberTurnExecutor;
    private GroupContextBuilder contextBuilder;

    private PhaseExecutionEngine engine() {
        memberTurnExecutor = mock(MemberTurnExecutor.class);
        contextBuilder = mock(GroupContextBuilder.class);
        when(contextBuilder.buildPhaseInput(any(), any(), any(), any(), anyInt(), any())).thenReturn("rendered-input");
        return new PhaseExecutionEngine(memberTurnExecutor, contextBuilder,
                Executors.newVirtualThreadPerTaskExecutor(), new CallerIdentityContext(null, null));
    }

    private GroupMember member(String id) {
        return new GroupMember(id, id, 1, null);
    }

    private DiscussionPhase phase(TurnOrder turnOrder) {
        return new DiscussionPhase("P", PhaseType.OPINION, "ALL", turnOrder, ContextScope.FULL, false, null, 1, false);
    }

    private ProtocolConfig protocol() {
        return new ProtocolConfig(5, MemberFailurePolicy.SKIP, 0, MemberUnavailablePolicy.SKIP);
    }

    private GroupConversation gc() {
        var g = new GroupConversation();
        g.setId("gc-1");
        g.setGroupId(GROUP_ID);
        return g;
    }

    private TranscriptEntry opinionEntry(String agentId) {
        return new TranscriptEntry(agentId, agentId, "said something", 0, "P", TranscriptEntryType.OPINION, Instant.now(), null, null);
    }

    @Test
    void sequentialPhase_visitsEachSpeaker_inOrder_untilBudgetExhausted() throws Exception {
        var engine = engine();
        when(memberTurnExecutor.executeAgentTurn(any(), any(), any(), any(), anyInt(), any(), any(), any()))
                .thenAnswer(inv -> opinionEntry(((GroupMember) inv.getArgument(0)).agentId()));
        var speakers = List.of(member("a"), member("b"), member("c"));
        var gc = gc();
        var turnCounter = new AtomicInteger(0);

        engine.executeSequentialPhase(gc, new AgentGroupConfiguration(), speakers, phase(TurnOrder.SEQUENTIAL), protocol(), "Q?", 0, null,
                turnCounter, 2);

        // maxTurns=2 caps it at the first two speakers, in order
        assertEquals(2, gc.getTranscript().size());
        assertEquals("a", gc.getTranscript().get(0).speakerAgentId());
        assertEquals("b", gc.getTranscript().get(1).speakerAgentId());
        assertEquals(2, turnCounter.get());
        verify(memberTurnExecutor, times(2)).executeAgentTurn(any(), eq(gc), any(), any(), eq(0), any(), isNull(), isNull());
    }

    @Test
    void sequentialPhase_zeroBudget_noTurnsRun() throws Exception {
        var engine = engine();
        var gc = gc();

        engine.executeSequentialPhase(gc, new AgentGroupConfiguration(), List.of(member("a")), phase(TurnOrder.SEQUENTIAL), protocol(), "Q?", 0,
                null, new AtomicInteger(0), 0);

        assertTrue(gc.getTranscript().isEmpty());
        verifyNoInteractions(memberTurnExecutor);
    }

    @Test
    void sequentialPhase_withStartSpeakerIdx_resumesOnlyRemainingSpeakers() throws Exception {
        var engine = engine();
        when(memberTurnExecutor.executeAgentTurn(any(), any(), any(), any(), anyInt(), any(), any(), any()))
                .thenAnswer(inv -> opinionEntry(((GroupMember) inv.getArgument(0)).agentId()));
        var speakers = List.of(member("a"), member("b"), member("c"));
        var gc = gc();
        var turnCounter = new AtomicInteger(0);

        // F2: a pause bookmarked speaker index 1 ("b") — resume must skip "a"
        // entirely (already spoke before the pause) and run only "b" and "c".
        engine.executeSequentialPhase(gc, new AgentGroupConfiguration(), speakers, phase(TurnOrder.SEQUENTIAL), protocol(), "Q?", 0, null,
                turnCounter, 10, 1);

        assertEquals(2, gc.getTranscript().size());
        assertEquals("b", gc.getTranscript().get(0).speakerAgentId());
        assertEquals("c", gc.getTranscript().get(1).speakerAgentId());
        assertEquals(2, turnCounter.get());
        verify(memberTurnExecutor, never()).executeAgentTurn(argThat(m -> "a".equals(m.agentId())), any(), any(), any(), anyInt(), any(), any(),
                any());
    }

    @Test
    void sequentialPhase_startSpeakerIdxAtOrBeyondSize_clampsToNoTurns() throws Exception {
        var engine = engine();
        var gc = gc();
        var turnCounter = new AtomicInteger(0);

        // F2 Javadoc contract: an out-of-range offset (config edited to remove
        // members while paused) clamps to speakers.size() — zero turns, not an
        // IndexOutOfBoundsException. GroupHitlCoordinator's drift guard is what
        // is meant to catch this before it gets here; this only proves the
        // fallback itself is safe.
        engine.executeSequentialPhase(gc, new AgentGroupConfiguration(), List.of(member("a"), member("b")), phase(TurnOrder.SEQUENTIAL),
                protocol(), "Q?", 0, null, turnCounter, 10, 5);

        assertTrue(gc.getTranscript().isEmpty());
        assertEquals(0, turnCounter.get());
        verifyNoInteractions(memberTurnExecutor);
    }

    @Test
    void parallelPhase_allSpeakers_produceEntries() throws Exception {
        var engine = engine();
        when(memberTurnExecutor.executeAgentTurn(any(), any(), any(), any(), anyInt(), any(), any(), any(), any()))
                .thenAnswer(inv -> opinionEntry(((GroupMember) inv.getArgument(0)).agentId()));
        var speakers = List.of(member("a"), member("b"), member("c"));
        var gc = gc();
        var turnCounter = new AtomicInteger(0);

        engine.executeParallelPhase(gc, new AgentGroupConfiguration(), speakers, phase(TurnOrder.PARALLEL), protocol(), "Q?", 0, null, turnCounter,
                10);

        assertEquals(3, gc.getTranscript().size());
        assertEquals(3, turnCounter.get());
        var speakerIds = gc.getTranscript().stream().map(TranscriptEntry::speakerAgentId).sorted().toList();
        assertEquals(List.of("a", "b", "c"), speakerIds);
    }

    @Test
    void parallelPhase_zeroRemainingBudget_noop() throws Exception {
        var engine = engine();
        var gc = gc();
        var turnCounter = new AtomicInteger(5);

        engine.executeParallelPhase(gc, new AgentGroupConfiguration(), List.of(member("a")), phase(TurnOrder.PARALLEL), protocol(), "Q?", 0, null,
                turnCounter, 5);

        assertTrue(gc.getTranscript().isEmpty());
        verifyNoInteractions(memberTurnExecutor);
    }

    @Test
    void peerTargetedPhase_excludesSelf_targetsEveryOtherConfiguredMember() throws Exception {
        var engine = engine();
        when(memberTurnExecutor.executeAgentTurn(any(), any(), any(), any(), anyInt(), any(), any(), any()))
                .thenAnswer(inv -> new TranscriptEntry(((GroupMember) inv.getArgument(0)).agentId(), ((GroupMember) inv.getArgument(0)).agentId(),
                        "said something", 0, "P", TranscriptEntryType.OPINION, Instant.now(), null, (String) inv.getArgument(6)));
        var a = member("a");
        var b = member("b");
        var config = new AgentGroupConfiguration();
        config.setMembers(List.of(a, b));
        var gc = gc();
        var turnCounter = new AtomicInteger(0);

        engine.executePeerTargetedPhase(gc, config, List.of(a, b), phase(TurnOrder.SEQUENTIAL), protocol(), "Q?", 0, null, turnCounter, 10);

        // a→b and b→a: 2 turns, never a speaker targeting themselves
        assertEquals(2, gc.getTranscript().size());
        assertEquals("b", gc.getTranscript().get(0).targetAgentId());
        assertEquals("a", gc.getTranscript().get(1).targetAgentId());
    }
}
