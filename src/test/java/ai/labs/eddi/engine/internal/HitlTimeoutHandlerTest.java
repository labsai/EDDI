/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.hitl.HitlSchedules;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.memory.model.ConversationState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link HitlTimeoutHandler}.
 */
class HitlTimeoutHandlerTest {

    @Mock
    private IConversationService conversationService;

    @Mock
    private IGroupConversationService groupConversationService;

    private HitlTimeoutHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        handler = new HitlTimeoutHandler();
        // Inject mocks into @Inject fields via reflection
        setField(handler, "conversationService", conversationService);
        setField(handler, "groupConversationService", groupConversationService);
        setField(handler, "meterRegistry", new SimpleMeterRegistry());
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    // =========================================================================
    // AUTO_APPROVE
    // =========================================================================

    @Nested
    @DisplayName("AUTO_APPROVE policy")
    class AutoApprove {

        @Test
        @DisplayName("regular surface → calls resumeConversation with APPROVED verdict and decidedBy=system:timeout")
        void regularSurface_callsResumeConversationWithApproved() throws Exception {
            var metadata = Map.<String, Object>of(
                    "policy", "AUTO_APPROVE",
                    "surface", "regular",
                    "conversationId", "conv-123");

            handler.handleTimeout(metadata);

            var decisionCaptor = ArgumentCaptor.forClass(HitlDecision.class);
            verify(conversationService).resumeConversation(eq("conv-123"), decisionCaptor.capture(), isNull());
            HitlDecision captured = decisionCaptor.getValue();
            assertEquals(HitlVerdict.APPROVED, captured.getVerdict());
            assertEquals("system:timeout", captured.getDecidedBy());
            assertNotNull(captured.getNote());
            assertTrue(captured.getNote().toLowerCase().contains("approved"));

            verifyNoInteractions(groupConversationService);
        }

        @Test
        @DisplayName("group surface → calls resumeDiscussion with APPROVED verdict")
        void groupSurface_callsResumeDiscussionWithApproved() throws Exception {
            var metadata = Map.<String, Object>of(
                    "policy", "AUTO_APPROVE",
                    "surface", "group",
                    "conversationId", "gc-123");

            handler.handleTimeout(metadata);

            verify(groupConversationService).resumeDiscussion(eq("gc-123"), any(), isNull());
            verifyNoInteractions(conversationService);
        }
    }

    // =========================================================================
    // AUTO_REJECT
    // =========================================================================

    @Nested
    @DisplayName("AUTO_REJECT policy")
    class AutoReject {

        @Test
        @DisplayName("regular surface → calls resumeConversation with REJECTED verdict")
        void regularSurface_callsResumeConversationWithRejected() throws Exception {
            var metadata = Map.<String, Object>of(
                    "policy", "AUTO_REJECT",
                    "surface", "regular",
                    "conversationId", "conv-123");

            handler.handleTimeout(metadata);

            var decisionCaptor = ArgumentCaptor.forClass(HitlDecision.class);
            verify(conversationService).resumeConversation(eq("conv-123"), decisionCaptor.capture(), isNull());
            HitlDecision captured = decisionCaptor.getValue();
            assertEquals(HitlVerdict.REJECTED, captured.getVerdict());
            assertEquals("system:timeout", captured.getDecidedBy());
            assertNotNull(captured.getNote());
            assertTrue(captured.getNote().toLowerCase().contains("rejected"));

            verifyNoInteractions(groupConversationService);
        }

        @Test
        @DisplayName("group surface → calls resumeDiscussion with REJECTED verdict")
        void groupSurface_callsResumeDiscussionWithRejected() throws Exception {
            var metadata = Map.<String, Object>of(
                    "policy", "AUTO_REJECT",
                    "surface", "group",
                    "conversationId", "gc-123");

            handler.handleTimeout(metadata);

            verify(groupConversationService).resumeDiscussion(eq("gc-123"), any(), isNull());
            verifyNoInteractions(conversationService);
        }
    }

    // =========================================================================
    // ABORT
    // =========================================================================

    @Nested
    @DisplayName("ABORT policy")
    class Abort {

        @Test
        @DisplayName("regular surface → calls cancelConversation with CANCEL_GRACEFUL")
        void regularSurface_callsCancelConversation() throws Exception {
            var metadata = Map.<String, Object>of(
                    "policy", "ABORT",
                    "surface", "regular",
                    "conversationId", "conv-123");

            handler.handleTimeout(metadata);

            // system-attributed so the cancellation audit entry names the actor
            verify(conversationService).cancelConversation("conv-123", ControlSignal.CANCEL_GRACEFUL, "system:timeout");
            verifyNoInteractions(groupConversationService);
        }

        @Test
        @DisplayName("group surface → calls cancelDiscussion with CANCEL_GRACEFUL")
        void groupSurface_callsCancelDiscussion() throws Exception {
            var metadata = Map.<String, Object>of(
                    "policy", "ABORT",
                    "surface", "group",
                    "conversationId", "gc-123");

            handler.handleTimeout(metadata);

            verify(groupConversationService).cancelDiscussion("gc-123", ControlSignal.CANCEL_GRACEFUL);
            verifyNoInteractions(conversationService);
        }
    }

    // =========================================================================
    // WAIT_INDEFINITELY
    // =========================================================================

    @Nested
    @DisplayName("WAIT_INDEFINITELY policy")
    class WaitIndefinitely {

        @Test
        @DisplayName("no service methods called")
        void noMethodsCalled() {
            var metadata = Map.<String, Object>of(
                    "policy", "WAIT_INDEFINITELY",
                    "surface", "regular",
                    "conversationId", "conv-123");

            handler.handleTimeout(metadata);

            verifyNoInteractions(conversationService);
            verifyNoInteractions(groupConversationService);
        }
    }

    // =========================================================================
    // Unknown policy
    // =========================================================================

    @Nested
    @DisplayName("Unknown policy string")
    class UnknownPolicy {

        @Test
        @DisplayName("no method calls and no exception thrown")
        void unknownPolicy_noCallsNoException() {
            var metadata = Map.<String, Object>of(
                    "policy", "DOES_NOT_EXIST",
                    "surface", "regular",
                    "conversationId", "conv-123");

            assertDoesNotThrow(() -> handler.handleTimeout(metadata));

            verifyNoInteractions(conversationService);
            verifyNoInteractions(groupConversationService);
        }
    }

    // =========================================================================
    // I6 — human-turn timeouts (surface group-human, OnHumanTimeout policies)
    // =========================================================================

    @Nested
    @DisplayName("group-human surface (I6)")
    class HumanTurnTimeouts {

        @Test
        @DisplayName("SKIP_TURN → skipHumanTurnOnTimeout, never the approval resume path")
        void skipTurn_routesToSkipHumanTurn() {
            var metadata = Map.<String, Object>of(
                    "policy", "SKIP_TURN",
                    "surface", "group-human",
                    "conversationId", "gc-1");

            handler.handleTimeout(metadata);

            verify(groupConversationService).skipHumanTurnOnTimeout("gc-1");
            verifyNoMoreInteractions(groupConversationService);
            verifyNoInteractions(conversationService);
        }

        @Test
        @DisplayName("ABORT → graceful cancel of the discussion")
        void abort_cancelsDiscussion() throws Exception {
            var metadata = Map.<String, Object>of(
                    "policy", "ABORT",
                    "surface", "group-human",
                    "conversationId", "gc-1");

            handler.handleTimeout(metadata);

            verify(groupConversationService).cancelDiscussion("gc-1", ControlSignal.CANCEL_GRACEFUL);
            verifyNoMoreInteractions(groupConversationService);
        }

        @Test
        @DisplayName("an unknown human policy degrades to SKIP_TURN — a lost turn beats a stuck discussion")
        void unknownHumanPolicy_degradesToSkip() {
            var metadata = Map.<String, Object>of(
                    "policy", "SOMETHING_NEW",
                    "surface", "group-human",
                    "conversationId", "gc-1");

            assertDoesNotThrow(() -> handler.handleTimeout(metadata));

            verify(groupConversationService).skipHumanTurnOnTimeout("gc-1");
        }
    }

    // =========================================================================
    // H2/H3 — a decision that cannot be applied yet keeps the timeout armed;
    // a decision is bound to the pause it was armed for
    // =========================================================================

    @Nested
    @DisplayName("Failure before execution and pause binding")
    class RetryAndPauseBinding {

        private Map<String, Object> regular(String policy) {
            return HitlSchedules.timeoutMetadata(policy, HitlSchedules.SURFACE_REGULAR, "conv-1", "1700000000000");
        }

        @Test
        @DisplayName("the decision names the pause the timeout was armed for")
        void decisionCarriesPauseId() throws Exception {
            handler.handleTimeout(regular("AUTO_REJECT"));

            var captor = ArgumentCaptor.forClass(HitlDecision.class);
            verify(conversationService).resumeConversation(eq("conv-1"), captor.capture(), isNull());
            assertEquals("1700000000000", captor.getValue().getPauseId());
        }

        @Test
        @DisplayName("agent undeployed while still paused → RetryLaterException, so the fire is FAILED and re-armed, not COMPLETED")
        void undeployedAgent_isRetried() throws Exception {
            doThrow(new IllegalStateException("Agent not deployed for resume"))
                    .when(conversationService).resumeConversation(eq("conv-1"), any(), isNull());
            when(conversationService.getConversationState("conv-1")).thenReturn(ConversationState.AWAITING_HUMAN);

            assertThrows(HitlTimeoutHandler.RetryLaterException.class, () -> handler.handleTimeout(regular("AUTO_REJECT")));
        }

        @Test
        @DisplayName("a node draining for shutdown is retried too")
        void shutdownDrain_isRetried() throws Exception {
            doThrow(new RejectedExecutionException("shutting down"))
                    .when(conversationService).resumeConversation(eq("conv-1"), any(), isNull());
            when(conversationService.getConversationState("conv-1")).thenReturn(ConversationState.AWAITING_HUMAN);

            assertThrows(HitlTimeoutHandler.RetryLaterException.class, () -> handler.handleTimeout(regular("AUTO_APPROVE")));
        }

        @Test
        @DisplayName("a stale fire for an earlier pause completes without deciding anything")
        void pauseMismatch_isDone() throws Exception {
            doThrow(new IConversationService.PauseMismatchException("changed"))
                    .when(conversationService).resumeConversation(eq("conv-1"), any(), isNull());

            assertDoesNotThrow(() -> handler.handleTimeout(regular("AUTO_REJECT")));
            verify(conversationService, never()).getConversationState(anyString());
        }

        @Test
        @DisplayName("a conversation no longer paused (decided, cancelled) completes without retrying")
        void noLongerPaused_isDone() throws Exception {
            doThrow(new IllegalStateException("not AWAITING_HUMAN"))
                    .when(conversationService).resumeConversation(eq("conv-1"), any(), isNull());
            when(conversationService.getConversationState("conv-1")).thenReturn(ConversationState.READY);

            assertDoesNotThrow(() -> handler.handleTimeout(regular("AUTO_REJECT")));
        }

        @Test
        @DisplayName("group: still awaiting approval → retried; mismatch → done")
        void group() throws Exception {
            var md = HitlSchedules.timeoutMetadata("AUTO_REJECT", HitlSchedules.SURFACE_GROUP, "gc-1", "42");
            doThrow(new IllegalStateException("agent missing")).when(groupConversationService).resumeDiscussion(eq("gc-1"), any(), isNull());
            var gc = new GroupConversation();
            gc.setState(GroupConversation.GroupConversationState.AWAITING_APPROVAL);
            when(groupConversationService.readGroupConversation("gc-1")).thenReturn(gc);
            assertThrows(HitlTimeoutHandler.RetryLaterException.class, () -> handler.handleTimeout(md));

            reset(groupConversationService);
            doThrow(new IGroupConversationService.GroupPauseMismatchException("changed"))
                    .when(groupConversationService).resumeDiscussion(eq("gc-1"), any(), isNull());
            assertDoesNotThrow(() -> handler.handleTimeout(md));
        }

        @Test
        @DisplayName("ABORT whose cancel fails while still paused is retried, not dropped")
        void abortRegular_isRetried() throws Exception {
            doThrow(new IllegalStateException("store blip"))
                    .when(conversationService).cancelConversation(eq("conv-1"), any(), anyString());
            when(conversationService.getConversationState("conv-1")).thenReturn(ConversationState.AWAITING_HUMAN);
            assertThrows(HitlTimeoutHandler.RetryLaterException.class, () -> handler.handleTimeout(regular("ABORT")));

            when(conversationService.getConversationState("conv-1")).thenReturn(ConversationState.ENDED);
            assertDoesNotThrow(() -> handler.handleTimeout(regular("ABORT")));
        }

        @Test
        @DisplayName("group ABORT and an expired human turn are retried while their pause is still waiting")
        void abortGroupAndHumanTurn_areRetried() throws Exception {
            var abort = HitlSchedules.timeoutMetadata("ABORT", HitlSchedules.SURFACE_GROUP, "gc-1", "42");
            doThrow(new IllegalStateException("store blip")).when(groupConversationService).cancelDiscussion(eq("gc-1"), any());
            var gc = new GroupConversation();
            gc.setState(GroupConversation.GroupConversationState.AWAITING_APPROVAL);
            when(groupConversationService.readGroupConversation("gc-1")).thenReturn(gc);
            assertThrows(HitlTimeoutHandler.RetryLaterException.class, () -> handler.handleTimeout(abort));

            var human = HitlSchedules.timeoutMetadata("SKIP_TURN", HitlSchedules.SURFACE_GROUP_HUMAN, "gc-1", "42");
            doThrow(new IllegalStateException("store blip")).when(groupConversationService).skipHumanTurnOnTimeout("gc-1");
            gc.setState(GroupConversation.GroupConversationState.AWAITING_HUMAN_INPUT);
            assertThrows(HitlTimeoutHandler.RetryLaterException.class, () -> handler.handleTimeout(human));

            gc.setState(GroupConversation.GroupConversationState.COMPLETED);
            assertDoesNotThrow(() -> handler.handleTimeout(human));
        }
    }
}
