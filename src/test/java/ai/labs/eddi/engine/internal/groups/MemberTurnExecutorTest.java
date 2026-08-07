/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ContextScope;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.MemberType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.MemberFailurePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.MemberUnavailablePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionException;
import ai.labs.eddi.engine.internal.GroupConversationService;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot.ConversationStepData;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot.SimpleConversationStep;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Focused unit tests for {@link MemberTurnExecutor}, extracted from
 * {@code GroupConversationService} during the Wave R (R1 step 4) refactor.
 * Covers the pure-function methods directly; the retryable
 * {@code executeAgentTurn} call (cooperative cancellation, HITL sub-flows,
 * nested-group recursion) is already thoroughly exercised via reflection
 * through the facade's delegators by
 * {@code GroupConversationServiceHitlCoverage2Test},
 * {@code GroupConversationServiceHitlCoverage3Test}, and
 * {@code GroupConversationServiceConcurrencyTest} — re-verified green against
 * this class post-extraction rather than duplicated here.
 *
 * @author tests
 */
class MemberTurnExecutorTest {

    private static final String AGENT_A = "agent-a";

    private MemberTurnExecutor executor() {
        return new MemberTurnExecutor(
                Mockito.mock(IConversationService.class),
                Mockito.mock(IAgentFactory.class),
                new GroupSigningGuard(null, null, null, "default"),
                new GroupContextBuilder(null),
                Mockito.mock(GroupConversationService.class),
                new SimpleMeterRegistry().counter("test.member.pause.skipped"),
                180, 2);
    }

    private GroupMember member() {
        return new GroupMember(AGENT_A, "Agent A", 1, null);
    }

    private DiscussionPhase phase(PhaseType type) {
        return new DiscussionPhase("P-" + type, type, "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1, false);
    }

    private ProtocolConfig protocol(MemberFailurePolicy failurePolicy) {
        return new ProtocolConfig(60, failurePolicy, 2, MemberUnavailablePolicy.SKIP);
    }

    @Test
    void errorEntry_nullMember_usesUnknownFallbacks() {
        var entry = executor().errorEntry(null, 0, phase(PhaseType.OPINION), "boom");

        assertEquals("unknown", entry.speakerAgentId());
        assertEquals("Unknown", entry.speakerDisplayName());
        assertEquals(TranscriptEntryType.ERROR, entry.type());
        assertEquals("boom", entry.errorReason());
    }

    @Test
    void errorEntry_realMember_usesMemberIdentity() {
        var entry = executor().errorEntry(member(), 2, phase(PhaseType.CRITIQUE), "boom");

        assertEquals(AGENT_A, entry.speakerAgentId());
        assertEquals("Agent A", entry.speakerDisplayName());
        assertEquals(2, entry.phaseIndex());
    }

    @Test
    void handleAgentFailure_abortPolicy_throwsGroupDiscussionException() {
        var ex = assertThrows(GroupDiscussionException.class,
                () -> executor().handleAgentFailure(member(), 0, phase(PhaseType.OPINION),
                        protocol(MemberFailurePolicy.ABORT), new RuntimeException("down"), "Agent failed", null));

        assertTrue(ex.getMessage().contains("Agent failed"));
        assertTrue(ex.getMessage().contains(AGENT_A));
    }

    @Test
    void handleAgentFailure_skipPolicy_returnsSkippedEntry() throws Exception {
        var entry = executor().handleAgentFailure(member(), 1, phase(PhaseType.OPINION),
                protocol(MemberFailurePolicy.SKIP), new RuntimeException("down"), "Agent failed", "target-x");

        assertEquals(TranscriptEntryType.SKIPPED, entry.type());
        assertEquals("target-x", entry.targetAgentId());
        assertTrue(entry.errorReason().contains("down"));
    }

    // =================================================================
    // tryResolveMemberToolPause — the graceful tool-rejection resume path
    // =================================================================

    /**
     * Regression: the graceful path built its {@code TranscriptEntry} with the
     * 9-arg constructor, so a signing-enabled agent produced an <em>unsigned</em>
     * contribution whenever its tool call was auto-rejected — while the normal
     * path, three lines away, signed identically-shaped entries. Peers read both
     * through the same {@code verifyPriorEntriesIfRequired}, so the unsigned one
     * simply had nothing to verify.
     */
    @Test
    void tryResolveMemberToolPause_gracefulContribution_isSigned() throws Exception {
        var conversationService = Mockito.mock(IConversationService.class);
        var signingGuard = Mockito.mock(GroupSigningGuard.class);
        var contextBuilder = Mockito.mock(GroupContextBuilder.class);
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.READY);

        // Resume completes immediately with a tool-less answer.
        doAnswer(inv -> {
            inv.getArgument(2, ConversationResponseHandler.class).onComplete(snapshot);
            return null;
        }).when(conversationService).resumeConversation(anyString(), any(), any());
        when(contextBuilder.extractResponse(snapshot)).thenReturn("tool-less answer");
        when(signingGuard.signOutgoingMessage(eq(AGENT_A), eq("group-1"), eq("tool-less answer"), anyString()))
                .thenReturn(new GroupSigningGuard.SigningResult("sig-abc", "nonce-1", 1234L, 7));

        var executor = new MemberTurnExecutor(conversationService, Mockito.mock(IAgentFactory.class),
                signingGuard, contextBuilder, Mockito.mock(GroupConversationService.class),
                new SimpleMeterRegistry().counter("test.member.pause.skipped"), 180, 2);

        var gc = new GroupConversation();
        gc.setId("gc-1");
        gc.setGroupId("group-1");

        var entry = executor.tryResolveMemberToolPause(member(), gc, "conv-1", "input", 5, 0,
                phase(PhaseType.OPINION), TranscriptEntryType.OPINION, null);

        assertNotNull(entry, "a completed resume yields a real contribution, not a fallback");
        assertEquals("tool-less answer", entry.content());
        assertEquals("sig-abc", entry.signature());
        assertEquals("nonce-1", entry.signatureNonce());
        assertEquals(1234L, entry.signatureTimestampMs());
        assertEquals(7, entry.signatureKeyVersion());
    }

    /**
     * The signing call must stay a no-op for agents that do not sign —
     * {@code signOutgoingMessage} returns
     * {@link GroupSigningGuard.SigningResult#UNSIGNED} then, and the entry keeps
     * null envelope fields exactly as before the fix.
     */
    @Test
    void tryResolveMemberToolPause_signingDisabled_entryStaysUnsigned() throws Exception {
        var conversationService = Mockito.mock(IConversationService.class);
        var signingGuard = Mockito.mock(GroupSigningGuard.class);
        var contextBuilder = Mockito.mock(GroupContextBuilder.class);
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.READY);

        doAnswer(inv -> {
            inv.getArgument(2, ConversationResponseHandler.class).onComplete(snapshot);
            return null;
        }).when(conversationService).resumeConversation(anyString(), any(), any());
        when(contextBuilder.extractResponse(snapshot)).thenReturn("tool-less answer");
        when(signingGuard.signOutgoingMessage(any(), any(), any(), any()))
                .thenReturn(GroupSigningGuard.SigningResult.UNSIGNED);

        var executor = new MemberTurnExecutor(conversationService, Mockito.mock(IAgentFactory.class),
                signingGuard, contextBuilder, Mockito.mock(GroupConversationService.class),
                new SimpleMeterRegistry().counter("test.member.pause.skipped"), 180, 2);

        var gc = new GroupConversation();
        gc.setId("gc-1");
        gc.setGroupId("group-1");

        var entry = executor.tryResolveMemberToolPause(member(), gc, "conv-1", "input", 5, 0,
                phase(PhaseType.OPINION), TranscriptEntryType.OPINION, null);

        assertNotNull(entry);
        assertNull(entry.signature());
        assertNull(entry.signatureNonce());
        assertNull(entry.signatureTimestampMs());
        assertNull(entry.signatureKeyVersion());
    }

    @Test
    void constructedWithMockedCollaborators_doesNotThrow() {
        assertDoesNotThrow(this::executor);
    }

    // =================================================================
    // GroupCostLedger wiring (Wave 0, F5)
    // =================================================================

    @Test
    void executeAgentTurn_costTrackedInSnapshot_accumulatesIntoGroupConversation() throws Exception {
        var conversationService = Mockito.mock(IConversationService.class);
        var agentFactory = Mockito.mock(IAgentFactory.class);
        when(agentFactory.getLatestReadyAgent(any(), eq(AGENT_A))).thenReturn(Mockito.mock(IAgent.class));
        when(conversationService.startConversation(any(), eq(AGENT_A), any(), any()))
                .thenReturn(new IConversationService.ConversationResult("conv-1", null));

        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.READY);
        var step = new SimpleConversationStep();
        step.getConversationStep().add(new ConversationStepData(MemoryKeys.AUDIT_COST, 0.07, null, null));
        snapshot.getConversationSteps().add(step);

        doAnswer(inv -> {
            inv.getArgument(8, IConversationService.ConversationResponseHandler.class).onComplete(snapshot);
            return null;
        }).when(conversationService).say(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());

        var executor = new MemberTurnExecutor(conversationService, agentFactory,
                new GroupSigningGuard(null, null, null, "default"), new GroupContextBuilder(null),
                Mockito.mock(GroupConversationService.class),
                new SimpleMeterRegistry().counter("test.member.pause.skipped"), 180, 2);

        var gc = new GroupConversation();
        gc.setId("gc-1");
        gc.setGroupId("group-1");

        executor.executeAgentTurn(member(), gc, "input", protocol(MemberFailurePolicy.SKIP), 0, phase(PhaseType.OPINION), null, null, null);

        assertEquals(0.07, gc.getMemberCosts().get(AGENT_A));
        assertEquals(0.07, gc.getTotalCost());
    }

    @Test
    void executeGroupMemberTurn_rollsUpChildDiscussionCost() throws Exception {
        var groupConversationService = Mockito.mock(GroupConversationService.class);
        var subConversation = new GroupConversation();
        subConversation.setId("sub-gc-1");
        subConversation.setSynthesizedAnswer("Nested answer");
        subConversation.setTotalCost(0.42);
        // The 7-arg overload — I1 added the inherited-cost-ceiling parameter, and a
        // nested GROUP member now dispatches through it.
        when(groupConversationService.discuss(eq("sub-group-1"), any(), any(), anyInt(), any(), any(), any())).thenReturn(subConversation);

        var executor = new MemberTurnExecutor(Mockito.mock(IConversationService.class), Mockito.mock(IAgentFactory.class),
                new GroupSigningGuard(null, null, null, "default"), new GroupContextBuilder(null),
                groupConversationService, new SimpleMeterRegistry().counter("test.member.pause.skipped"), 180, 2);

        var gc = new GroupConversation();
        gc.setId("gc-1");
        gc.setGroupId("group-1");
        var groupMember = new GroupMember("sub-group-1", "Sub Group", 1, null, MemberType.GROUP);

        var entry = executor.executeAgentTurn(groupMember, gc, "input", protocol(MemberFailurePolicy.SKIP), 0, phase(PhaseType.OPINION), null,
                null, null);

        assertEquals("Nested answer", entry.content());
        // Attribution is keyed per child discussion (agentId:childId) so a member's
        // later children add to — rather than replace — its earlier ones.
        assertEquals(0.42, gc.getMemberCosts().get("sub-group-1:sub-gc-1"));
        assertEquals(0.42, gc.getTotalCost());
    }
}
