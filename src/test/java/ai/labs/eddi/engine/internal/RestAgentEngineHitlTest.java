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
        @DisplayName("returns 200 OK when the service reports CANCELLED")
        void returns200Ok() throws Exception {
            doReturn(IConversationService.CancelOutcome.CANCELLED)
                    .when(conversationService).cancelConversation(CONVERSATION_ID, ControlSignal.CANCEL_GRACEFUL);

            Response response = restAgentEngine.cancelConversation(CONVERSATION_ID);

            assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
            verify(conversationService).cancelConversation(CONVERSATION_ID, ControlSignal.CANCEL_GRACEFUL);
        }

        @Test
        @DisplayName("returns 404 when the conversation does not exist")
        void returns404WhenUnknown() throws Exception {
            doReturn(IConversationService.CancelOutcome.NOT_FOUND)
                    .when(conversationService).cancelConversation(CONVERSATION_ID, ControlSignal.CANCEL_GRACEFUL);

            Response response = restAgentEngine.cancelConversation(CONVERSATION_ID);

            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
        }

        @Test
        @DisplayName("returns 409 when there is nothing to cancel")
        void returns409WhenNothingToCancel() throws Exception {
            doReturn(IConversationService.CancelOutcome.NOTHING_TO_CANCEL)
                    .when(conversationService).cancelConversation(CONVERSATION_ID, ControlSignal.CANCEL_GRACEFUL);

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
        @DisplayName("non-admin sees ONLY their own conversations (mixed ownership filtered)")
        void nonAdminSeesOnlyOwn() throws Exception {
            doReturn(false).when(ownershipValidator).isAdmin(identity);
            doReturn(false).when(ownershipValidator).isApprover(identity);
            doReturn(List.of(
                    summaryOwnedBy("conv-mine", USER_ID),
                    summaryOwnedBy("conv-theirs", "someone-else"),
                    summaryOwnedBy("conv-unowned", null)))
                    .when(conversationService).listPendingApprovals(anyInt());

            List<PendingApprovalSummary> result = restAgentEngine.listPendingApprovals(200);

            // fail-closed: other users' AND ownerless conversations are excluded
            assertEquals(1, result.size());
            assertEquals("conv-mine", result.get(0).getConversationId());
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
}
