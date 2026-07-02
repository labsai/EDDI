/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
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

            verify(conversationService).cancelConversation("conv-123", ControlSignal.CANCEL_GRACEFUL);
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
}
