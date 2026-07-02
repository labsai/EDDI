/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.PendingApprovalSummary;
import ai.labs.eddi.engine.security.OwnershipValidator;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.security.Principal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for HITL REST endpoints in {@link RestAgentEngine}.
 */
class RestAgentEngineHitlTest {

    private static final String CONVERSATION_ID = "conv-rest-hitl";
    private static final String USER_ID = "test-user";
    private static final int AGENT_TIMEOUT = 30;

    @Mock
    private IConversationService conversationService;
    @Mock
    private IConversationDescriptorStore conversationDescriptorStore;
    @Mock
    private SecurityIdentity identity;
    @Mock
    private OwnershipValidator ownershipValidator;
    @Mock
    private Principal principal;

    private RestAgentEngine restAgentEngine;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);

        // Default: ownership check passes (descriptor found, owner matches)
        var descriptor = new ConversationDescriptor();
        descriptor.setUserId(USER_ID);
        doReturn(descriptor).when(conversationDescriptorStore).readDescriptor(anyString(), anyInt());
        doNothing().when(ownershipValidator).requireOwnerOrAdmin(any(), any(), any());

        // Identity principal for resumeConversation (not tested here but needed)
        doReturn(principal).when(identity).getPrincipal();
        doReturn(USER_ID).when(principal).getName();

        restAgentEngine = new RestAgentEngine(
                conversationService, conversationDescriptorStore,
                identity, ownershipValidator, AGENT_TIMEOUT);
    }

    // =========================================================================
    // cancelConversation
    // =========================================================================

    @Nested
    @DisplayName("cancelConversation")
    class CancelConversation {

        @Test
        @DisplayName("returns 200 OK when the service reports CANCELLED, attributing the caller")
        void returns200Ok() throws Exception {
            doReturn(IConversationService.CancelOutcome.CANCELLED)
                    .when(conversationService).cancelConversation(eq(CONVERSATION_ID), eq(ControlSignal.CANCEL_GRACEFUL), any());

            Response response = restAgentEngine.cancelConversation(CONVERSATION_ID);

            assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
            // the cancelling principal is threaded through for audit attribution
            verify(conversationService).cancelConversation(CONVERSATION_ID, ControlSignal.CANCEL_GRACEFUL, USER_ID);
        }

        @Test
        @DisplayName("returns 404 when the conversation does not exist")
        void returns404WhenUnknown() throws Exception {
            doReturn(IConversationService.CancelOutcome.NOT_FOUND)
                    .when(conversationService).cancelConversation(eq(CONVERSATION_ID), eq(ControlSignal.CANCEL_GRACEFUL), any());

            Response response = restAgentEngine.cancelConversation(CONVERSATION_ID);

            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
        }

        @Test
        @DisplayName("returns 409 when there is nothing to cancel")
        void returns409WhenNothingToCancel() throws Exception {
            doReturn(IConversationService.CancelOutcome.NOTHING_TO_CANCEL)
                    .when(conversationService).cancelConversation(eq(CONVERSATION_ID), eq(ControlSignal.CANCEL_GRACEFUL), any());

            Response response = restAgentEngine.cancelConversation(CONVERSATION_ID);

            assertEquals(Response.Status.CONFLICT.getStatusCode(), response.getStatus());
        }
    }

    // =========================================================================
    // listPendingApprovals
    // =========================================================================

    @Nested
    @DisplayName("listPendingApprovals")
    class ListPendingApprovals {

        private PendingApprovalSummary summaryOwnedBy(String conversationId, String ownerId) {
            return new PendingApprovalSummary(
                    conversationId, "agent-1", ownerId, Instant.now(), "needs review", "AUTO_APPROVE");
        }

        @Test
        @DisplayName("admin sees all summaries")
        void adminSeesAll() throws Exception {
            doReturn(true).when(ownershipValidator).isAdmin(identity);
            doReturn(List.of(summaryOwnedBy("conv-1", USER_ID), summaryOwnedBy("conv-2", "someone-else")))
                    .when(conversationService).listPendingApprovals(anyInt());

            List<PendingApprovalSummary> result = restAgentEngine.listPendingApprovals(200);

            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("approver sees all summaries (they must be able to list what they can decide)")
        void approverSeesAll() throws Exception {
            doReturn(false).when(ownershipValidator).isAdmin(identity);
            doReturn(true).when(ownershipValidator).isApprover(identity);
            doReturn(List.of(summaryOwnedBy("conv-1", USER_ID), summaryOwnedBy("conv-2", "someone-else")))
                    .when(conversationService).listPendingApprovals(anyInt());

            List<PendingApprovalSummary> result = restAgentEngine.listPendingApprovals(200);

            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("non-admin uses the owner-scoped query (filter applied before the limit)")
        void nonAdminSeesOnlyOwn() throws Exception {
            doReturn(false).when(ownershipValidator).isAdmin(identity);
            doReturn(false).when(ownershipValidator).isApprover(identity);
            // the owner filter is pushed into the query — the service is invoked
            // with the caller's id, never post-filtered from the global listing
            doReturn(List.of(summaryOwnedBy("conv-mine", USER_ID)))
                    .when(conversationService).listPendingApprovals(eq(USER_ID), anyInt());

            List<PendingApprovalSummary> result = restAgentEngine.listPendingApprovals(200);

            assertEquals(1, result.size());
            assertEquals("conv-mine", result.get(0).getConversationId());
            verify(conversationService).listPendingApprovals(USER_ID, 200);
            verify(conversationService, never()).listPendingApprovals(anyInt());
        }

        @Test
        @DisplayName("anonymous caller sees nothing (fail-closed)")
        void anonymousSeesNothing() throws Exception {
            doReturn(false).when(ownershipValidator).isAdmin(identity);
            doReturn(false).when(ownershipValidator).isApprover(identity);
            doReturn(null).when(identity).getPrincipal();
            doReturn(List.of(summaryOwnedBy("conv-1", USER_ID)))
                    .when(conversationService).listPendingApprovals(anyInt());

            List<PendingApprovalSummary> result = restAgentEngine.listPendingApprovals(200);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("returns empty list when no pending approvals")
        void returnsEmptyList() throws Exception {
            doReturn(true).when(ownershipValidator).isAdmin(identity);
            doReturn(List.of()).when(conversationService).listPendingApprovals(anyInt());

            List<PendingApprovalSummary> result = restAgentEngine.listPendingApprovals(200);

            assertNotNull(result);
            assertTrue(result.isEmpty());
        }
    }

    // =========================================================================
    // resumeConversation — input bounds
    // =========================================================================

    @Nested
    @DisplayName("resumeConversation input validation")
    class ResumeInputValidation {

        @Test
        @DisplayName("decision note longer than 4096 chars → 400, service never called")
        void oversizedNoteRejected() throws Exception {
            var decision = new ai.labs.eddi.engine.lifecycle.model.HitlDecision();
            decision.setVerdict(ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict.APPROVED);
            decision.setNote("x".repeat(4097));

            Response response = restAgentEngine.resumeConversation(CONVERSATION_ID, decision);

            assertEquals(400, response.getStatus(), "oversized note must be rejected with 400");
            verify(conversationService, never()).resumeConversation(anyString(), any(), any());
        }

        @Test
        @DisplayName("note of exactly 4096 chars is accepted")
        void maxLengthNoteAccepted() throws Exception {
            var decision = new ai.labs.eddi.engine.lifecycle.model.HitlDecision();
            decision.setVerdict(ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict.APPROVED);
            decision.setNote("x".repeat(4096));

            Response response = restAgentEngine.resumeConversation(CONVERSATION_ID, decision);

            assertEquals(200, response.getStatus());
            verify(conversationService).resumeConversation(eq(CONVERSATION_ID), any(), any());
        }

        @Test
        @DisplayName("missing verdict → 400, service never called")
        void missingVerdictRejected() throws Exception {
            var decision = new ai.labs.eddi.engine.lifecycle.model.HitlDecision();

            Response response = restAgentEngine.resumeConversation(CONVERSATION_ID, decision);

            assertEquals(400, response.getStatus());
            verify(conversationService, never()).resumeConversation(anyString(), any(), any());
        }
    }

    // =========================================================================
    // getApprovalStatus — approver read scope
    // =========================================================================

    @Nested
    @DisplayName("getApprovalStatus — approver read scope")
    class GetApprovalStatus {

        private ConversationMemorySnapshot snapshotInState(ConversationState state) throws Exception {
            var snapshot = new ConversationMemorySnapshot();
            snapshot.setConversationState(state);
            doReturn(snapshot).when(conversationService).getConversationMemorySnapshot(CONVERSATION_ID);
            return snapshot;
        }

        @Test
        @DisplayName("approver-only caller may read detail=full while paused")
        void approverFullWhilePaused() throws Exception {
            var snapshot = snapshotInState(ConversationState.AWAITING_HUMAN);

            Response response = restAgentEngine.getApprovalStatus(CONVERSATION_ID, "full");

            assertEquals(200, response.getStatus());
            assertSame(snapshot, response.getEntity(), "Full view should return the memory snapshot");
        }

        @Test
        @DisplayName("approver-only caller gets 403 for detail=full on a non-paused conversation")
        void approverFullDeniedWhenNotPaused() throws Exception {
            snapshotInState(ConversationState.READY);
            doReturn(false).when(ownershipValidator).isAdmin(identity);
            doReturn(false).when(ownershipValidator).isOwner(eq(identity), any());

            Response response = restAgentEngine.getApprovalStatus(CONVERSATION_ID, "full");

            assertEquals(403, response.getStatus(),
                    "Approver role must not be a universal read-everything grant");
        }

        @Test
        @DisplayName("owner may read detail=full even when not paused")
        void ownerFullWhenNotPaused() throws Exception {
            var snapshot = snapshotInState(ConversationState.ENDED);
            doReturn(false).when(ownershipValidator).isAdmin(identity);
            doReturn(true).when(ownershipValidator).isOwner(identity, USER_ID);

            Response response = restAgentEngine.getApprovalStatus(CONVERSATION_ID, "full");

            assertEquals(200, response.getStatus());
            assertSame(snapshot, response.getEntity());
        }

        @Test
        @DisplayName("admin may read detail=full even when not paused")
        void adminFullWhenNotPaused() throws Exception {
            snapshotInState(ConversationState.ENDED);
            doReturn(true).when(ownershipValidator).isAdmin(identity);

            Response response = restAgentEngine.getApprovalStatus(CONVERSATION_ID, "full");

            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("summary suppresses stale bookmark fields when not paused")
        void summarySuppressesStaleFields() throws Exception {
            var snapshot = snapshotInState(ConversationState.ENDED);
            snapshot.setHitlPauseReason("stale reason");

            Response response = restAgentEngine.getApprovalStatus(CONVERSATION_ID, "summary");

            assertEquals(200, response.getStatus());
            @SuppressWarnings("unchecked")
            var summary = (java.util.Map<String, Object>) response.getEntity();
            assertEquals("", summary.get("pauseReason"), "Stale pause fields must be suppressed");
        }
    }
}
