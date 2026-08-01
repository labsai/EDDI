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
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionException;
import ai.labs.eddi.engine.internal.GroupConversationService;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

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

    @Test
    void constructedWithMockedCollaborators_doesNotThrow() {
        assertDoesNotThrow(this::executor);
    }
}
