/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.security.OwnershipValidator;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for REST ownership validation on group conversation HITL endpoints
 * in {@link RestGroupConversation}. Covers cancel, approve, approveStreaming,
 * and getApprovalStatus — verifying owner, non-owner, and admin scenarios.
 */
class RestGroupConversationHitlTest {

    private static final String GROUP_ID = "group-1";
    private static final String GC_ID = "gc-hitl-1";
    private static final String OWNER_ID = "owner1";
    private static final String ATTACKER_ID = "attacker";
    private static final String ADMIN_ID = "admin-user";

    private IGroupConversationService groupService;
    private IJsonSerialization jsonSerialization;
    private SecurityIdentity identity;
    private OwnershipValidator ownershipValidator;
    private RestGroupConversation restGroupConversation;

    @BeforeEach
    void setUp() {
        groupService = mock(IGroupConversationService.class);
        jsonSerialization = mock(IJsonSerialization.class);
        identity = mock(SecurityIdentity.class);
        // Use a real OwnershipValidator with auth enabled to test actual logic
        ownershipValidator = new OwnershipValidator(true);

        when(identity.isAnonymous()).thenReturn(false);

        restGroupConversation = new RestGroupConversation(
                groupService, jsonSerialization, identity, ownershipValidator);
    }

    /** Creates a GC owned by the given userId. */
    private GroupConversation makeGc(String ownerId) {
        var gc = new GroupConversation();
        gc.setId(GC_ID);
        gc.setGroupId(GROUP_ID);
        gc.setUserId(ownerId);
        gc.setState(GroupConversationState.AWAITING_APPROVAL);
        return gc;
    }

    /** Configures identity to return the given principal name. */
    private void asUser(String userId) {
        var principal = mock(Principal.class);
        when(principal.getName()).thenReturn(userId);
        when(identity.getPrincipal()).thenReturn(principal);
        when(identity.hasRole("eddi-admin")).thenReturn(false);
    }

    /** Configures identity as admin. */
    private void asAdmin(String userId) {
        var principal = mock(Principal.class);
        when(principal.getName()).thenReturn(userId);
        when(identity.getPrincipal()).thenReturn(principal);
        when(identity.hasRole("eddi-admin")).thenReturn(true);
    }

    // =================================================================
    // Ownership denied tests
    // =================================================================

    @Nested
    @DisplayName("Ownership denied — non-owner gets 403")
    class OwnershipDenied {

        @Test
        @DisplayName("Non-owner calling cancelDiscussion throws ForbiddenException")
        void cancelDenied() throws Exception {
            asUser(ATTACKER_ID);
            when(groupService.readGroupConversation(GC_ID)).thenReturn(makeGc(OWNER_ID));

            assertThrows(ForbiddenException.class,
                    () -> restGroupConversation.cancelDiscussion(GROUP_ID, GC_ID),
                    "Non-owner cancel should throw ForbiddenException");
        }

        @Test
        @DisplayName("Non-owner calling approveGroupPhase throws ForbiddenException")
        void approveDenied() throws Exception {
            asUser(ATTACKER_ID);
            when(groupService.readGroupConversation(GC_ID)).thenReturn(makeGc(OWNER_ID));

            // Valid body — body validation runs before the ownership check,
            // so an empty request would short-circuit to 400 instead.
            var request = new GroupApprovalRequest();
            var decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);
            request.setDecision(decision);
            assertThrows(ForbiddenException.class,
                    () -> restGroupConversation.approveGroupPhase(GROUP_ID, GC_ID, request),
                    "Non-owner approve should throw ForbiddenException");
        }

        @Test
        @DisplayName("Empty body returns 400 before any ownership check")
        void emptyBodyRejectedBeforeAuthz() {
            asUser(ATTACKER_ID);

            var response = restGroupConversation.approveGroupPhase(GROUP_ID, GC_ID, new GroupApprovalRequest());
            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        }

        @Test
        @DisplayName("Non-owner calling getGroupApprovalStatus throws ForbiddenException")
        void statusDenied() throws Exception {
            asUser(ATTACKER_ID);
            when(groupService.readGroupConversation(GC_ID)).thenReturn(makeGc(OWNER_ID));

            assertThrows(ForbiddenException.class,
                    () -> restGroupConversation.getGroupApprovalStatus(GROUP_ID, GC_ID, null),
                    "Non-owner status check should throw ForbiddenException");
        }
    }

    // =================================================================
    // Owner approved tests
    // =================================================================

    @Nested
    @DisplayName("Owner approved — 200 OK")
    class OwnerApproved {

        @Test
        @DisplayName("Owner calling approveGroupPhase returns 200")
        void ownerApproveOk() throws Exception {
            asUser(OWNER_ID);
            var gc = makeGc(OWNER_ID);
            when(groupService.readGroupConversation(GC_ID)).thenReturn(gc);
            when(groupService.resumeDiscussion(eq(GC_ID), any(), any())).thenReturn(gc);

            var request = new GroupApprovalRequest();
            var decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);
            request.setDecision(decision);

            Response response = restGroupConversation.approveGroupPhase(GROUP_ID, GC_ID, request);

            assertEquals(200, response.getStatus(),
                    "Owner approve should return 200 OK");
            verify(groupService).resumeDiscussion(eq(GC_ID), any(), any());
        }

        @Test
        @DisplayName("Owner calling cancelDiscussion returns 200")
        void ownerCancelOk() throws Exception {
            asUser(OWNER_ID);
            var gc = makeGc(OWNER_ID);
            when(groupService.readGroupConversation(GC_ID)).thenReturn(gc);
            when(groupService.cancelDiscussion(eq(GC_ID), any())).thenReturn(true);

            Response response = restGroupConversation.cancelDiscussion(GROUP_ID, GC_ID);

            assertEquals(200, response.getStatus(),
                    "Owner cancel should return 200 OK");
        }

        @Test
        @DisplayName("Cancel of already-terminal GC returns 409")
        void ownerCancelAlreadyTerminal() throws Exception {
            asUser(OWNER_ID);
            var gc = makeGc(OWNER_ID);
            when(groupService.readGroupConversation(GC_ID)).thenReturn(gc);
            when(groupService.cancelDiscussion(eq(GC_ID), any())).thenReturn(false);

            Response response = restGroupConversation.cancelDiscussion(GROUP_ID, GC_ID);

            assertEquals(409, response.getStatus(),
                    "Cancel of a terminal conversation should return 409 Conflict");
        }

        @Test
        @DisplayName("Owner calling getGroupApprovalStatus returns 200")
        void ownerStatusOk() throws Exception {
            asUser(OWNER_ID);
            var gc = makeGc(OWNER_ID);
            when(groupService.readGroupConversation(GC_ID)).thenReturn(gc);

            Response response = restGroupConversation.getGroupApprovalStatus(GROUP_ID, GC_ID, null);

            assertEquals(200, response.getStatus(),
                    "Owner status check should return 200 OK");
        }
    }

    // =================================================================
    // Admin bypass tests
    // =================================================================

    @Nested
    @DisplayName("Admin bypass — admin can access anyone's GC")
    class AdminBypass {

        @Test
        @DisplayName("Admin calling approve for another user's GC returns 200")
        void adminApproveBypass() throws Exception {
            asAdmin(ADMIN_ID);
            var gc = makeGc(OWNER_ID); // owned by someone else
            when(groupService.readGroupConversation(GC_ID)).thenReturn(gc);
            when(groupService.resumeDiscussion(eq(GC_ID), any(), any())).thenReturn(gc);

            var request = new GroupApprovalRequest();
            var decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);
            request.setDecision(decision);

            Response response = restGroupConversation.approveGroupPhase(GROUP_ID, GC_ID, request);

            assertEquals(200, response.getStatus(),
                    "Admin should bypass ownership check and get 200 OK");
        }

        @Test
        @DisplayName("Admin calling cancel for another user's GC returns 200")
        void adminCancelBypass() throws Exception {
            asAdmin(ADMIN_ID);
            var gc = makeGc(OWNER_ID);
            when(groupService.readGroupConversation(GC_ID)).thenReturn(gc);
            when(groupService.cancelDiscussion(eq(GC_ID), any())).thenReturn(true);

            Response response = restGroupConversation.cancelDiscussion(GROUP_ID, GC_ID);

            assertEquals(200, response.getStatus(),
                    "Admin should bypass ownership check and get 200 OK");
        }

        @Test
        @DisplayName("Admin calling status for another user's GC returns 200")
        void adminStatusBypass() throws Exception {
            asAdmin(ADMIN_ID);
            var gc = makeGc(OWNER_ID);
            when(groupService.readGroupConversation(GC_ID)).thenReturn(gc);

            Response response = restGroupConversation.getGroupApprovalStatus(GROUP_ID, GC_ID, null);

            assertEquals(200, response.getStatus(),
                    "Admin should bypass ownership check and get 200 OK");
        }
    }

    // =================================================================
    // decidedBy server-side override
    // =================================================================

    @Nested
    @DisplayName("decidedBy server-side enforcement")
    class DecidedByServerSide {

        @Test
        @DisplayName("Request body decidedBy=attacker is overwritten with authenticated user")
        void decidedByOverwritten() throws Exception {
            asUser(OWNER_ID);
            var gc = makeGc(OWNER_ID);
            when(groupService.readGroupConversation(GC_ID)).thenReturn(gc);
            when(groupService.resumeDiscussion(eq(GC_ID), any(), any())).thenReturn(gc);

            // Attacker tries to set decidedBy to their name in the request body
            var request = new GroupApprovalRequest();
            var decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);
            decision.setDecidedBy(ATTACKER_ID); // should be overwritten
            request.setDecision(decision);

            restGroupConversation.approveGroupPhase(GROUP_ID, GC_ID, request);

            // After setDecidedByFromIdentity, decidedBy should be the actual caller
            assertEquals(OWNER_ID, request.getDecision().getDecidedBy(),
                    "decidedBy should be set server-side to the authenticated user, not the attacker's value");
        }

        @Test
        @DisplayName("decidedBy is set even when request body has null decidedBy")
        void decidedBySetWhenNull() throws Exception {
            asUser(OWNER_ID);
            var gc = makeGc(OWNER_ID);
            when(groupService.readGroupConversation(GC_ID)).thenReturn(gc);
            when(groupService.resumeDiscussion(eq(GC_ID), any(), any())).thenReturn(gc);

            var request = new GroupApprovalRequest();
            var decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);
            // decidedBy deliberately left null
            request.setDecision(decision);

            restGroupConversation.approveGroupPhase(GROUP_ID, GC_ID, request);

            assertEquals(OWNER_ID, request.getDecision().getDecidedBy(),
                    "decidedBy should be set server-side even when request body leaves it null");
        }
    }

    // =================================================================
    // Edge case: approve with CONFLICT (ResourceModifiedException)
    // =================================================================

    @Nested
    @DisplayName("Approve conflict")
    class ApproveConflict {

        @Test
        @DisplayName("ResourceModifiedException returns 409 CONFLICT")
        void conflictReturns409() throws Exception {
            asUser(OWNER_ID);
            var gc = makeGc(OWNER_ID);
            when(groupService.readGroupConversation(GC_ID)).thenReturn(gc);
            when(groupService.resumeDiscussion(eq(GC_ID), any(), any()))
                    .thenThrow(new IResourceStore.ResourceModifiedException("State changed"));

            var request = new GroupApprovalRequest();
            var decision = new HitlDecision();
            decision.setVerdict(HitlVerdict.APPROVED);
            request.setDecision(decision);

            Response response = restGroupConversation.approveGroupPhase(GROUP_ID, GC_ID, request);

            assertEquals(Response.Status.CONFLICT.getStatusCode(), response.getStatus(),
                    "ResourceModifiedException should map to 409 CONFLICT");
        }
    }
}
