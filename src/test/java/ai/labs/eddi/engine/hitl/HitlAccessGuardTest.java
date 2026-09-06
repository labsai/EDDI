/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.model.PendingApprovalSummary;
import ai.labs.eddi.engine.security.OwnershipValidator;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.time.Instant;
import java.util.List;

import jakarta.ws.rs.NotFoundException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HitlAccessGuardTest {

    OwnershipValidator ownershipValidator;
    IConversationService conversationService;
    IConversationDescriptorStore descriptorStore;
    IGroupConversationService groupConversationService;
    SecurityIdentity identity;
    HitlAccessGuard guard;

    @BeforeEach
    void setup() {
        ownershipValidator = mock(OwnershipValidator.class);
        conversationService = mock(IConversationService.class);
        descriptorStore = mock(IConversationDescriptorStore.class);
        groupConversationService = mock(IGroupConversationService.class);
        identity = mock(SecurityIdentity.class);
        guard = new HitlAccessGuard(identity, ownershipValidator, descriptorStore,
                conversationService, groupConversationService);
    }

    private void callerNamed(String name) {
        Principal p = mock(Principal.class);
        when(p.getName()).thenReturn(name);
        when(identity.getPrincipal()).thenReturn(p);
    }

    @Test
    void listScopedPendingApprovals_adminSeesAll() throws Exception {
        when(ownershipValidator.isAdmin(identity)).thenReturn(true);
        when(conversationService.listPendingApprovals(50)).thenReturn(List.of());

        List<PendingApprovalSummary> result = guard.listScopedPendingApprovals(50);

        assertTrue(result.isEmpty());
        verify(conversationService).listPendingApprovals(50);
        verify(conversationService, never()).listPendingApprovals(anyString(), anyInt());
    }

    @Test
    void listScopedPendingApprovals_approverSeesAll() throws Exception {
        when(ownershipValidator.isAdmin(identity)).thenReturn(false);
        when(ownershipValidator.isApprover(identity)).thenReturn(true);
        when(conversationService.listPendingApprovals(30)).thenReturn(List.of());

        guard.listScopedPendingApprovals(30);

        verify(conversationService).listPendingApprovals(30);
        verify(conversationService, never()).listPendingApprovals(anyString(), anyInt());
    }

    @Test
    void listScopedPendingApprovals_ownerSeesOnlyOwn() throws Exception {
        when(ownershipValidator.isAdmin(identity)).thenReturn(false);
        when(ownershipValidator.isApprover(identity)).thenReturn(false);
        callerNamed("alice");
        when(conversationService.listPendingApprovals("alice", 50)).thenReturn(List.of());

        guard.listScopedPendingApprovals(50);

        verify(conversationService).listPendingApprovals("alice", 50);
        verify(conversationService, never()).listPendingApprovals(anyInt());
    }

    @Test
    void listScopedPendingApprovals_anonymousSeesNothing() throws Exception {
        when(ownershipValidator.isAdmin(identity)).thenReturn(false);
        when(ownershipValidator.isApprover(identity)).thenReturn(false);
        when(identity.getPrincipal()).thenReturn(null);

        List<PendingApprovalSummary> result = guard.listScopedPendingApprovals(50);

        assertTrue(result.isEmpty());
        verify(conversationService, never()).listPendingApprovals(anyInt());
        verify(conversationService, never()).listPendingApprovals(anyString(), anyInt());
    }

    @Test
    void requireConversationHitlAccess_allowed_returnsOwner() throws Exception {
        ConversationDescriptor descriptor = mock(ConversationDescriptor.class);
        when(descriptor.getUserId()).thenReturn("owner1");
        when(descriptorStore.readDescriptor("c1", 0)).thenReturn(descriptor);

        String owner = guard.requireConversationHitlAccess("c1");

        assertEquals("owner1", owner);
        verify(ownershipValidator).requireOwnerAdminOrApprover(identity, "owner1", "conversation");
    }

    @Test
    void requireConversationHitlAccess_denied_throwsForbidden() throws Exception {
        ConversationDescriptor descriptor = mock(ConversationDescriptor.class);
        when(descriptor.getUserId()).thenReturn("owner1");
        when(descriptorStore.readDescriptor("c1", 0)).thenReturn(descriptor);
        doThrow(new ForbiddenException("no"))
                .when(ownershipValidator).requireOwnerAdminOrApprover(eq(identity), eq("owner1"), eq("conversation"));

        assertThrows(ForbiddenException.class, () -> guard.requireConversationHitlAccess("c1"));
    }

    @Test
    void requireConversationHitlAccess_missingDescriptor_nonAdminFailsClosed() throws Exception {
        when(descriptorStore.readDescriptor("c1", 0)).thenThrow(new ResourceNotFoundException("not found"));
        when(ownershipValidator.isAdmin(identity)).thenReturn(false);
        when(ownershipValidator.isApprover(identity)).thenReturn(false);

        assertThrows(ForbiddenException.class, () -> guard.requireConversationHitlAccess("c1"));
    }

    @Test
    void requireConversationHitlAccess_missingDescriptor_adminReturnsNull() throws Exception {
        when(descriptorStore.readDescriptor("c1", 0)).thenThrow(new ResourceNotFoundException("not found"));
        when(ownershipValidator.isAdmin(identity)).thenReturn(true);

        assertNull(guard.requireConversationHitlAccess("c1"));
    }

    // ---- group surface -----------------------------------------------------

    private PendingApprovalSummary groupSummaryOwnedBy(String gcId, String ownerId) {
        return new PendingApprovalSummary(gcId, "agent-1", ownerId, Instant.now(), "needs review", "AUTO_APPROVE");
    }

    @Test
    void listScopedGroupPendingApprovals_adminSeesAll() throws Exception {
        when(ownershipValidator.isAdmin(identity)).thenReturn(true);
        when(groupConversationService.listGroupPendingApprovals(null, 25))
                .thenReturn(List.of(groupSummaryOwnedBy("gc-1", "alice"), groupSummaryOwnedBy("gc-2", "bob")));

        List<PendingApprovalSummary> result = guard.listScopedGroupPendingApprovals(null, 25);

        assertEquals(2, result.size());
    }

    @Test
    void listScopedGroupPendingApprovals_ownerFilteredToOwn() throws Exception {
        when(ownershipValidator.isAdmin(identity)).thenReturn(false);
        when(ownershipValidator.isApprover(identity)).thenReturn(false);
        callerNamed("bob");
        when(groupConversationService.listGroupPendingApprovals("g1", 10))
                .thenReturn(List.of(groupSummaryOwnedBy("gc-bob", "bob"), groupSummaryOwnedBy("gc-other", "alice")));

        List<PendingApprovalSummary> result = guard.listScopedGroupPendingApprovals("g1", 10);

        assertEquals(1, result.size());
        assertEquals("gc-bob", result.get(0).getConversationId());
    }

    @Test
    void listScopedGroupPendingApprovals_anonymousSeesNothing() throws Exception {
        when(ownershipValidator.isAdmin(identity)).thenReturn(false);
        when(ownershipValidator.isApprover(identity)).thenReturn(false);
        when(identity.getPrincipal()).thenReturn(null);
        when(groupConversationService.listGroupPendingApprovals("g1", 10))
                .thenReturn(List.of(groupSummaryOwnedBy("gc-1", "alice")));

        assertTrue(guard.listScopedGroupPendingApprovals("g1", 10).isEmpty());
    }

    @Test
    void requireGroupConversationHitlAccess_allowed() throws Exception {
        GroupConversation gc = mock(GroupConversation.class);
        when(gc.getGroupId()).thenReturn("g1");
        when(gc.getUserId()).thenReturn("owner1");
        when(groupConversationService.readGroupConversation("gc1")).thenReturn(gc);

        guard.requireGroupConversationHitlAccess("g1", "gc1");

        verify(ownershipValidator).requireOwnerAdminOrApprover(identity, "owner1", "group conversation");
    }

    @Test
    void requireGroupConversationHitlAccess_wrongGroup_throwsNotFound() throws Exception {
        GroupConversation gc = mock(GroupConversation.class);
        when(gc.getGroupId()).thenReturn("g2");
        when(groupConversationService.readGroupConversation("gc1")).thenReturn(gc);

        assertThrows(NotFoundException.class,
                () -> guard.requireGroupConversationHitlAccess("g1", "gc1"));
        verify(ownershipValidator, never()).requireOwnerAdminOrApprover(any(), any(), any());
    }

    @Test
    void requireGroupConversationHitlAccess_denied_throwsForbidden() throws Exception {
        GroupConversation gc = mock(GroupConversation.class);
        when(gc.getGroupId()).thenReturn("g1");
        when(gc.getUserId()).thenReturn("owner1");
        when(groupConversationService.readGroupConversation("gc1")).thenReturn(gc);
        doThrow(new ForbiddenException("no"))
                .when(ownershipValidator).requireOwnerAdminOrApprover(eq(identity), eq("owner1"), eq("group conversation"));

        assertThrows(ForbiddenException.class, () -> guard.requireGroupConversationHitlAccess("g1", "gc1"));
    }

    // =================================================================
    // I6 — requireGroupHumanInputAccess (speaking ≠ approving)
    // =================================================================

    private GroupConversation humanPausedGc() throws Exception {
        GroupConversation gc = new GroupConversation();
        gc.setId("gc1");
        gc.setGroupId("g1");
        gc.setUserId("owner1");
        when(groupConversationService.readGroupConversation("gc1")).thenReturn(gc);
        return gc;
    }

    @Test
    void humanInputAccess_thePendingMemberThemselves_allowed() throws Exception {
        when(ownershipValidator.isAuthEnabled()).thenReturn(true);
        humanPausedGc();
        callerNamed("hannah");

        guard.requireGroupHumanInputAccess("g1", "gc1", "hannah");
    }

    @Test
    void humanInputAccess_adminBreakGlass_allowed() throws Exception {
        when(ownershipValidator.isAuthEnabled()).thenReturn(true);
        when(ownershipValidator.isAdmin(identity)).thenReturn(true);
        humanPausedGc();
        callerNamed("some-admin");

        guard.requireGroupHumanInputAccess("g1", "gc1", "hannah");
    }

    @Test
    void humanInputAccess_ownerAndApprover_forbidden_speakingIsNotApproving() throws Exception {
        when(ownershipValidator.isAuthEnabled()).thenReturn(true);
        when(ownershipValidator.isApprover(identity)).thenReturn(true);
        humanPausedGc();
        // The conversation OWNER, who is also an approver — may decide approvals,
        // but must never speak AS another human.
        callerNamed("owner1");

        assertThrows(ForbiddenException.class,
                () -> guard.requireGroupHumanInputAccess("g1", "gc1", "hannah"));
    }

    @Test
    void humanInputAccess_wrongGroupPath_404sWithoutLeaking() throws Exception {
        when(ownershipValidator.isAuthEnabled()).thenReturn(true);
        humanPausedGc();
        callerNamed("hannah");

        assertThrows(NotFoundException.class,
                () -> guard.requireGroupHumanInputAccess("other-group", "gc1", "hannah"));
    }

    @Test
    void humanInputAccess_authDisabled_noOp() {
        when(ownershipValidator.isAuthEnabled()).thenReturn(false);

        guard.requireGroupHumanInputAccess("g1", "gc1", "anyone");

        verify(ownershipValidator, never()).isAdmin(any());
    }

    @Test
    void readAccess_pendingMemberMayReadTheStatusOfTheirTurn() throws Exception {
        var gc = humanPausedGc();
        gc.setPendingHumanInput(new GroupConversation.PendingHumanInput("hannah", "Hannah", 0, 0, 1,
                "OPINION", "the prompt", "SKIP_TURN", Instant.now()));
        callerNamed("hannah");
        // Not owner, not admin, not approver — the strict guard would refuse.
        doThrow(new ForbiddenException("no"))
                .when(ownershipValidator).requireOwnerAdminOrApprover(any(), any(), any());

        guard.requireGroupConversationReadAccess("g1", "gc1");
    }

    @Test
    void readAccess_strangerStillRefused_andWrongGroup404s() throws Exception {
        var gc = humanPausedGc();
        gc.setPendingHumanInput(new GroupConversation.PendingHumanInput("hannah", "Hannah", 0, 0, 1,
                "OPINION", "the prompt", "SKIP_TURN", Instant.now()));
        callerNamed("mallory");
        doThrow(new ForbiddenException("no"))
                .when(ownershipValidator).requireOwnerAdminOrApprover(any(), any(), any());

        assertThrows(ForbiddenException.class, () -> guard.requireGroupConversationReadAccess("g1", "gc1"));
        assertThrows(NotFoundException.class,
                () -> guard.requireGroupConversationReadAccess("other-group", "gc1"));
    }

    @Test
    void groupInbox_pendingMemberSeesTheirTurn_withoutOwningTheConversation() throws Exception {
        when(ownershipValidator.isAdmin(identity)).thenReturn(false);
        when(ownershipValidator.isApprover(identity)).thenReturn(false);
        callerNamed("hannah");
        var ownTurn = new PendingApprovalSummary("gc1", null, "owner1", Instant.now(), "waiting", null);
        ownTurn.setPendingMemberId("hannah");
        var someoneElses = new PendingApprovalSummary("gc2", null, "owner2", Instant.now(), "waiting", null);
        someoneElses.setPendingMemberId("bob");
        when(groupConversationService.listGroupPendingApprovals(null, 50)).thenReturn(List.of(ownTurn, someoneElses));

        List<PendingApprovalSummary> result = guard.listScopedGroupPendingApprovals(null, 50);

        assertEquals(1, result.size());
        assertEquals("gc1", result.get(0).getConversationId());
    }
}
