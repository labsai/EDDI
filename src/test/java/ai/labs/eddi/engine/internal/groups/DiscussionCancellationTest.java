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
import ai.labs.eddi.engine.internal.GroupConversationService;
import ai.labs.eddi.engine.internal.GroupConversationService.MemberTurnCancelledException;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.lifecycle.model.DiscussionControlToken;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cancel reaches the work (K2) and nested groups inherit their parent's cancel
 * and turn budget (K8).
 */
class DiscussionCancellationTest {

    @Test
    void cancelHooks_hearEveryCancel_andAHookAddedLateRunsAtOnce() {
        var token = new DiscussionControlToken();
        List<ControlSignal> heard = new ArrayList<>();
        Runnable unlink = token.onCancel(heard::add);

        token.setSignal(ControlSignal.CONTINUE);
        token.setSignal(ControlSignal.CANCEL_GRACEFUL, "alice");
        unlink.run();
        token.setSignal(ControlSignal.CANCEL_IMMEDIATE);

        assertEquals(List.of(ControlSignal.CANCEL_GRACEFUL), heard);
        List<ControlSignal> late = new ArrayList<>();
        token.onCancel(late::add);
        assertEquals(List.of(ControlSignal.CANCEL_IMMEDIATE), late);
    }

    @Test
    void checkpoint_throwsOnlyOnceCancelled() {
        var token = new DiscussionControlToken();
        DiscussionCancellation.checkpoint(null);
        DiscussionCancellation.checkpoint(token);

        token.setSignal(ControlSignal.CANCEL_GRACEFUL);

        assertThrows(MemberTurnCancelledException.class, () -> DiscussionCancellation.checkpoint(token));
    }

    @Test
    void link_releasesTheTurnOnImmediateOnly() {
        var token = new DiscussionControlToken();
        var graceful = DiscussionCancellation.link(token);
        var awaited = new CompletableFuture<String>();
        graceful.cancellation().register(awaited);

        token.setSignal(ControlSignal.CANCEL_GRACEFUL);
        assertFalse(awaited.isDone(), "a graceful cancel lets the running turn finish");

        token.setSignal(ControlSignal.CANCEL_IMMEDIATE);
        assertTrue(awaited.isCompletedExceptionally(), "an immediate cancel releases it");
        assertTrue(graceful.cancellation().stopsInFlight(), "and stops the member's own turn");
        graceful.close();

        assertNull(DiscussionCancellation.link(null).cancellation());
    }

    // --- nested GROUP members (K8) ---

    private static DiscussionPhase opinion() {
        return new DiscussionPhase("P", PhaseType.OPINION, "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1, false);
    }

    private static ProtocolConfig protocol() {
        return new ProtocolConfig(60, MemberFailurePolicy.SKIP, 0, MemberUnavailablePolicy.SKIP);
    }

    private static MemberTurnExecutor executor(GroupConversationService service) {
        return new MemberTurnExecutor(Mockito.mock(IConversationService.class), Mockito.mock(IAgentFactory.class),
                new GroupSigningGuard(null, null, "default"), new GroupContextBuilder(null), service,
                new SimpleMeterRegistry().counter("test.member.pause.skipped"), 180, 2);
    }

    private static GroupConversation parent() {
        var gc = new GroupConversation();
        gc.setId("gc-parent");
        gc.setGroupId("group-parent");
        return gc;
    }

    private static final GroupMember SUB_GROUP = new GroupMember("sub-group", "Sub", 1, null, MemberType.GROUP);

    @Test
    void nestedGroup_inheritsTheRemainingTurns_andChargesItsOwnBack() throws Exception {
        var service = Mockito.mock(GroupConversationService.class);
        var token = new DiscussionControlToken();
        when(service.controlTokenOf("gc-parent")).thenReturn(token);
        var child = new GroupConversation();
        child.setId("gc-child");
        child.setSynthesizedAnswer("child answer");
        child.bindLegTurnBudget(new AtomicInteger(7), 10);
        when(service.discussNested(eq("sub-group"), any(), any(), anyInt(), any(), any(), any(), eq("gc-parent"), any(), any()))
                .thenReturn(child);
        var gc = parent();
        var parentCounter = new AtomicInteger(30);
        gc.bindLegTurnBudget(parentCounter, 50);

        var entry = executor(service).executeAgentTurn(SUB_GROUP, gc, "input", protocol(), 0, opinion(), null, null);

        assertEquals("child answer", entry.content());
        verify(service).discussNested(eq("sub-group"), any(), any(), anyInt(), any(), any(), any(), eq("gc-parent"), Mockito.same(token),
                eq(20));
        assertEquals(37, parentCounter.get(), "the child's turns count against the parent");
    }

    @Test
    void nestedGroup_withNoTurnLeft_startsNoChild() throws Exception {
        var service = Mockito.mock(GroupConversationService.class);
        var gc = parent();
        gc.bindLegTurnBudget(new AtomicInteger(50), 50);

        var entry = executor(service).executeAgentTurn(SUB_GROUP, gc, "input", protocol(), 0, opinion(), null, null);

        assertEquals(TranscriptEntryType.SKIPPED, entry.type());
        assertEquals(MemberTurnExecutor.REASON_TURN_BUDGET, entry.errorReason());
        verify(service, never()).discussNested(any(), any(), any(), anyInt(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void nestedGroup_parentCancelled_isACancelNotAFailedTurn() throws Exception {
        var service = Mockito.mock(GroupConversationService.class);
        var token = new DiscussionControlToken();
        token.setSignal(ControlSignal.CANCEL_GRACEFUL);
        when(service.controlTokenOf("gc-parent")).thenReturn(token);

        var thrown = assertThrows(MemberTurnCancelledException.class,
                () -> executor(service).executeAgentTurn(SUB_GROUP, parent(), "input", protocol(), 0, opinion(), null, null));

        assertSame(MemberTurnCancelledException.class, thrown.getClass());
        verify(service, never()).discussNested(any(), any(), any(), anyInt(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void legCap_aChildNeverGetsMoreThanItsParentLeft() {
        assertEquals(50, TurnBudget.legCap(0, null));
        assertEquals(12, TurnBudget.legCap(0, 12));
        assertEquals(5, TurnBudget.legCap(5, 12));
        assertEquals(0, TurnBudget.legCap(5, -3));
    }
}
