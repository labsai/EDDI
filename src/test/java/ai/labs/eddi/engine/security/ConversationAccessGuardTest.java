/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ConversationAccessGuard} — the shared owner-or-admin
 * gate for conversations, used by both the REST and the MCP surface.
 */
class ConversationAccessGuardTest {

    private static final String CONVERSATION_ID = "conv-1";
    private static final String OWNER = "owner-user";
    private static final String OTHER = "other-user";

    private IConversationDescriptorStore descriptorStore;

    @BeforeEach
    void setUp() {
        descriptorStore = mock(IConversationDescriptorStore.class);
    }

    private SecurityIdentity identityOf(String principalName, String... roles) {
        var identity = mock(SecurityIdentity.class);
        var principal = mock(Principal.class);
        lenient().when(principal.getName()).thenReturn(principalName);
        lenient().when(identity.getPrincipal()).thenReturn(principal);
        lenient().when(identity.isAnonymous()).thenReturn(principalName == null);
        for (String role : roles) {
            lenient().when(identity.hasRole(role)).thenReturn(true);
        }
        return identity;
    }

    /** Authenticated, not anonymous, but the token named nobody. */
    private SecurityIdentity namelessIdentity() {
        var identity = mock(SecurityIdentity.class);
        var principal = mock(Principal.class);
        lenient().when(principal.getName()).thenReturn(null);
        lenient().when(identity.getPrincipal()).thenReturn(principal);
        lenient().when(identity.isAnonymous()).thenReturn(false);
        lenient().when(identity.hasRole("eddi-viewer")).thenReturn(true);
        return identity;
    }

    private ConversationAccessGuard guardFor(SecurityIdentity identity, boolean authEnabled) {
        return new ConversationAccessGuard(identity, new OwnershipValidator(authEnabled), descriptorStore);
    }

    private void descriptorOwnedBy(String ownerId) throws Exception {
        var descriptor = new ConversationDescriptor();
        descriptor.setUserId(ownerId);
        doReturn(descriptor).when(descriptorStore).readDescriptor(anyString(), anyInt());
    }

    @Nested
    @DisplayName("requireConversationOwner")
    class RequireConversationOwner {

        @Test
        @DisplayName("denies a caller who does not own the conversation")
        void deniesNonOwner() throws Exception {
            descriptorOwnedBy(OWNER);
            var guard = guardFor(identityOf(OTHER, "eddi-viewer"), true);

            assertThrows(ForbiddenException.class, () -> guard.requireConversationOwner(CONVERSATION_ID));
        }

        @Test
        @DisplayName("admits the owner and returns the owner id")
        void admitsOwner() throws Exception {
            descriptorOwnedBy(OWNER);
            var guard = guardFor(identityOf(OWNER, "eddi-viewer"), true);

            assertEquals(OWNER, guard.requireConversationOwner(CONVERSATION_ID));
        }

        @Test
        @DisplayName("admits an admin on someone else's conversation")
        void admitsAdmin() throws Exception {
            descriptorOwnedBy(OWNER);
            var guard = guardFor(identityOf(OTHER, "eddi-admin"), true);

            assertEquals(OWNER, guard.requireConversationOwner(CONVERSATION_ID));
        }

        @Test
        @DisplayName("C1a: a soft-deleted conversation is still owner-checked, against its archived descriptor")
        void softDeletedConversationDeniesNonOwner() throws Exception {
            // A soft delete archives the descriptor and keeps the memory. This used to
            // return null ("allowed"), opening the conversation to every caller.
            doThrow(new ResourceNotFoundException("archived"))
                    .when(descriptorStore).readDescriptor(anyString(), anyInt());
            var archived = new ConversationDescriptor();
            archived.setUserId(OWNER);
            doReturn(archived).when(descriptorStore).readDescriptorWithHistory(CONVERSATION_ID, 0);
            var guard = guardFor(identityOf(OTHER, "eddi-viewer"), true);

            assertThrows(ForbiddenException.class, () -> guard.requireConversationOwner(CONVERSATION_ID));
        }

        @Test
        @DisplayName("C1a: the owner of a soft-deleted conversation is still admitted")
        void softDeletedConversationAdmitsOwner() throws Exception {
            doThrow(new ResourceNotFoundException("archived"))
                    .when(descriptorStore).readDescriptor(anyString(), anyInt());
            var archived = new ConversationDescriptor();
            archived.setUserId(OWNER);
            doReturn(archived).when(descriptorStore).readDescriptorWithHistory(CONVERSATION_ID, 0);
            var guard = guardFor(identityOf(OWNER, "eddi-viewer"), true);

            assertEquals(OWNER, guard.requireConversationOwner(CONVERSATION_ID));
        }

        @Test
        @DisplayName("C1a/C1c: no descriptor at all (live or archived) is a 404 for a non-admin")
        void noDescriptorAtAllIsNotFoundForNonAdmin() throws Exception {
            // Permanently deleted, swept, never existed, or its descriptor was never
            // written: no owner to check against, so nobody but an admin gets in —
            // the audit trail of a deleted conversation included.
            doThrow(new ResourceNotFoundException("gone"))
                    .when(descriptorStore).readDescriptor(anyString(), anyInt());
            doThrow(new ResourceNotFoundException("gone"))
                    .when(descriptorStore).readDescriptorWithHistory(anyString(), anyInt());
            var guard = guardFor(identityOf(OTHER, "eddi-viewer"), true);

            assertThrows(NotFoundException.class, () -> guard.requireConversationOwner(CONVERSATION_ID));
        }

        @Test
        @DisplayName("a store that answers null for both reads is treated as no descriptor")
        void nullDescriptorsAreNotFoundForNonAdmin() throws Exception {
            doReturn(null).when(descriptorStore).readDescriptor(anyString(), anyInt());
            doReturn(null).when(descriptorStore).readDescriptorWithHistory(anyString(), anyInt());
            var guard = guardFor(identityOf(OTHER, "eddi-viewer"), true);

            assertThrows(NotFoundException.class, () -> guard.requireConversationOwner(CONVERSATION_ID));
        }

        @Test
        @DisplayName("an admin may still reach a conversation without any descriptor (orphans, audit of deleted ones)")
        void noDescriptorAtAllAdmitsAdmin() throws Exception {
            doThrow(new ResourceNotFoundException("gone"))
                    .when(descriptorStore).readDescriptor(anyString(), anyInt());
            doThrow(new ResourceNotFoundException("gone"))
                    .when(descriptorStore).readDescriptorWithHistory(anyString(), anyInt());
            var guard = guardFor(identityOf(OTHER, "eddi-admin"), true);

            assertNull(guard.requireConversationOwner(CONVERSATION_ID));
        }

        @Test
        @DisplayName("authorization disabled: a missing descriptor is still admitted, as before")
        void noDescriptorAuthDisabledAdmits() throws Exception {
            doThrow(new ResourceNotFoundException("gone"))
                    .when(descriptorStore).readDescriptor(anyString(), anyInt());
            var guard = guardFor(identityOf(OTHER), false);

            assertNull(guard.requireConversationOwner(CONVERSATION_ID));
        }

        @Test
        @DisplayName("fails closed when the archive read fails")
        void failsClosedOnArchiveStoreError() throws Exception {
            doThrow(new ResourceNotFoundException("archived"))
                    .when(descriptorStore).readDescriptor(anyString(), anyInt());
            doThrow(new ResourceStoreException("db down"))
                    .when(descriptorStore).readDescriptorWithHistory(anyString(), anyInt());
            var guard = guardFor(identityOf(OWNER, "eddi-viewer"), true);

            assertThrows(ForbiddenException.class, () -> guard.requireConversationOwner(CONVERSATION_ID));
        }

        @Test
        @DisplayName("fails closed when ownership cannot be verified (store error)")
        void failsClosedOnStoreError() throws Exception {
            doThrow(new ResourceStoreException("db down"))
                    .when(descriptorStore).readDescriptor(anyString(), anyInt());
            var guard = guardFor(identityOf(OWNER, "eddi-viewer"), true);

            assertThrows(ForbiddenException.class, () -> guard.requireConversationOwner(CONVERSATION_ID));
        }

        @Test
        @DisplayName("admits everyone when authorization is disabled")
        void authDisabledAdmitsEveryone() throws Exception {
            descriptorOwnedBy(OWNER);
            var guard = guardFor(identityOf(OTHER), false);

            assertEquals(OWNER, guard.requireConversationOwner(CONVERSATION_ID));
        }

        @Test
        @DisplayName("admits an unowned (legacy) conversation")
        void admitsUnownedConversation() throws Exception {
            descriptorOwnedBy(null);
            var guard = guardFor(identityOf(OTHER, "eddi-viewer"), true);

            assertNull(guard.requireConversationOwner(CONVERSATION_ID));
        }
    }

    @Nested
    @DisplayName("callerActor")
    class CallerActor {

        @Test
        @DisplayName("a named caller is the actor")
        void namedCaller() {
            assertEquals(OWNER, guardFor(identityOf(OWNER, "eddi-editor"), true).callerActor("system:x"));
        }

        @Test
        @DisplayName("anonymous or nameless callers fall back")
        void fallsBack() {
            assertEquals("system:x", guardFor(identityOf(null), true).callerActor("system:x"));
            assertEquals("system:x", guardFor(namelessIdentity(), true).callerActor("system:x"));
        }
    }

    @Nested
    @DisplayName("requireExistingConversationOwner")
    class RequireExistingConversationOwner {

        @Test
        @DisplayName("a missing conversation is a 404 even for an admin")
        void missingIsNotFoundForAdmin() throws Exception {
            doThrow(new ResourceNotFoundException("gone"))
                    .when(descriptorStore).readDescriptor(anyString(), anyInt());
            doThrow(new ResourceNotFoundException("gone"))
                    .when(descriptorStore).readDescriptorWithHistory(anyString(), anyInt());
            var guard = guardFor(identityOf(OTHER, "eddi-admin"), true);

            assertThrows(NotFoundException.class, () -> guard.requireExistingConversationOwner(CONVERSATION_ID));
        }

        @Test
        @DisplayName("a soft-deleted conversation is owner-checked against its archived descriptor")
        void softDeletedIsOwnerChecked() throws Exception {
            doThrow(new ResourceNotFoundException("archived"))
                    .when(descriptorStore).readDescriptor(anyString(), anyInt());
            var archived = new ConversationDescriptor();
            archived.setUserId(OWNER);
            doReturn(archived).when(descriptorStore).readDescriptorWithHistory(CONVERSATION_ID, 0);

            assertThrows(ForbiddenException.class,
                    () -> guardFor(identityOf(OTHER, "eddi-viewer"), true).requireExistingConversationOwner(CONVERSATION_ID));
            assertEquals(OWNER, guardFor(identityOf(OWNER, "eddi-viewer"), true).requireExistingConversationOwner(CONVERSATION_ID));
        }
    }

    @Nested
    @DisplayName("canAccessConversation — must admit exactly what requireConversationOwner admits")
    class CanAccessConversation {

        @Test
        @DisplayName("owner yes, other user no")
        void ownerYesOtherNo() {
            var guard = guardFor(identityOf(OWNER, "eddi-viewer"), true);

            assertTrue(guard.canAccessConversation(OWNER));
            assertFalse(guard.canAccessConversation(OTHER));
        }

        @Test
        @DisplayName("an anonymous-* owner belongs to nobody, so a non-admin cannot see it")
        void anonymousOwnerHiddenFromNonAdmin() {
            var guard = guardFor(identityOf(OWNER, "eddi-viewer"), true);

            assertFalse(guard.canAccessConversation("anonymous-6f1c2a"));
        }

        @Test
        @DisplayName("admin sees every conversation")
        void adminSeesAll() {
            var guard = guardFor(identityOf(OTHER, "eddi-admin"), true);

            assertTrue(guard.canAccessConversation(OWNER));
            assertTrue(guard.seesAllConversations());
        }

        @Test
        @DisplayName("an unowned conversation stays visible — same rule as the read gate")
        void unownedVisible() {
            var guard = guardFor(identityOf(OWNER, "eddi-viewer"), true);

            assertTrue(guard.canAccessConversation(null));
            assertTrue(guard.canAccessConversation("  "));
        }

        @Test
        @DisplayName("a caller with no principal name sees no unowned conversation — same rule as the read gate")
        void unownedHiddenFromNamelessCaller() throws Exception {
            var guard = guardFor(namelessIdentity(), true);

            assertFalse(guard.canAccessConversation(null));
            assertFalse(guard.canAccessConversation("  "));
            assertFalse(guard.canAccessConversation(OWNER));

            descriptorOwnedBy(null);
            assertThrows(ForbiddenException.class, () -> guard.requireConversationOwner(CONVERSATION_ID));
        }

        @Test
        @DisplayName("authorization disabled: everything is visible and no filtering is needed")
        void authDisabledSeesAll() {
            var guard = guardFor(identityOf(null), false);

            assertTrue(guard.seesAllConversations());
            assertTrue(guard.canAccessConversation(OTHER));
        }
    }

    @Nested
    @DisplayName("resolveOwnerUserId")
    class ResolveOwnerUserId {

        @Test
        @DisplayName("stamps the caller as owner when no userId is requested")
        void stampsCaller() {
            var guard = guardFor(identityOf(OWNER, "eddi-viewer"), true);

            assertEquals(OWNER, guard.resolveOwnerUserId(null));
        }

        @Test
        @DisplayName("rejects a non-admin naming another user")
        void rejectsImpersonation() {
            var guard = guardFor(identityOf(OWNER, "eddi-viewer"), true);

            assertThrows(ForbiddenException.class, () -> guard.resolveOwnerUserId(OTHER));
        }

        @Test
        @DisplayName("lets an admin act on another user's behalf")
        void adminMayImpersonate() {
            var guard = guardFor(identityOf(OWNER, "eddi-admin"), true);

            assertEquals(OTHER, guard.resolveOwnerUserId(OTHER));
        }

        @Test
        @DisplayName("authorization disabled: no owner is stamped (engine assigns an anonymous id, as before)")
        void authDisabledKeepsNull() {
            var guard = guardFor(identityOf(null), false);

            assertNull(guard.resolveOwnerUserId(null));
        }
    }
}
