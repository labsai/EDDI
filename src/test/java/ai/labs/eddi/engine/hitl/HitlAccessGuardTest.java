/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl;

import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.model.PendingApprovalSummary;
import ai.labs.eddi.engine.security.OwnershipValidator;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    SecurityIdentity identity;
    HitlAccessGuard guard;

    @BeforeEach
    void setup() {
        ownershipValidator = mock(OwnershipValidator.class);
        conversationService = mock(IConversationService.class);
        descriptorStore = mock(IConversationDescriptorStore.class);
        identity = mock(SecurityIdentity.class);
        guard = new HitlAccessGuard(identity, ownershipValidator, descriptorStore, conversationService);
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
}
