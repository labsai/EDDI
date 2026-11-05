/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.memory.rest.IRestConversationStore.ConversationPatch;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import ai.labs.eddi.engine.security.OwnershipValidator;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code PATCH /conversationstore/conversations/{id}} — rename and pin. Owner
 * only: built over a real {@link ConversationAccessGuard} with authorization
 * on, reading the owner from the same descriptor store the write goes to.
 */
@DisplayName("RestConversationStore — rename and pin")
class RestConversationStorePatchTest {

    private static final String CONVERSATION = "0123456789abcdef01234567";
    private static final String OWNER = "owner-user";

    private IConversationDescriptorStore descriptorStore;
    private IConversationMemoryStore memoryStore;
    private ConversationDescriptor stored;

    @BeforeEach
    void setUp() throws Exception {
        descriptorStore = mock(IConversationDescriptorStore.class);
        memoryStore = mock(IConversationMemoryStore.class);
        stored = new ConversationDescriptor();
        stored.setUserId(OWNER);
        lenient().when(descriptorStore.readDescriptor(eq(CONVERSATION), any())).thenReturn(stored);
        lenient().when(descriptorStore.writeOwnerEdit(eq(CONVERSATION), any(), any())).thenReturn(true);
    }

    @SuppressWarnings("unchecked")
    private RestConversationStore storeAs(String caller, String... roles) {
        var identity = mock(SecurityIdentity.class);
        var principal = mock(Principal.class);
        lenient().when(principal.getName()).thenReturn(caller);
        lenient().when(identity.getPrincipal()).thenReturn(principal);
        lenient().when(identity.isAnonymous()).thenReturn(false);
        for (String role : roles) {
            lenient().when(identity.hasRole(role)).thenReturn(true);
        }
        var guard = new ConversationAccessGuard(identity, new OwnershipValidator(true), descriptorStore);
        return new RestConversationStore(mock(IDocumentDescriptorStore.class), descriptorStore, memoryStore, mock(IConversationService.class),
                mock(IUserMemoryStore.class), mock(IRuntime.class), guard, mock(ResourceAccessGuard.class), 30, 90, mock(Instance.class));
    }

    @Test
    @DisplayName("the owner renames and pins; the title is marked USER by the store")
    void ownerRenames() throws Exception {
        var result = storeAs(OWNER, "eddi-user").patchConversation(CONVERSATION, new ConversationPatch("  Trip   to Rome ", true));

        verify(descriptorStore).writeOwnerEdit(CONVERSATION, "Trip to Rome", true);
        assertSame(stored, result);
    }

    @Test
    @DisplayName("another user gets 403 and nothing is written")
    void otherUserForbidden() throws Exception {
        var store = storeAs("intruder", "eddi-user");

        assertThrows(ForbiddenException.class, () -> store.patchConversation(CONVERSATION, new ConversationPatch("Mine now", null)));
        verify(descriptorStore, never()).writeOwnerEdit(anyString(), any(), any());
    }

    @Test
    @DisplayName("a reviewer role does not help: eddi-editor on someone else's conversation is 403")
    void editorForbidden() throws Exception {
        var store = storeAs("editor", "eddi-editor");

        assertThrows(ForbiddenException.class, () -> store.patchConversation(CONVERSATION, new ConversationPatch(null, true)));
        verify(descriptorStore, never()).writeOwnerEdit(anyString(), any(), any());
    }

    @Test
    @DisplayName("an admin may act, as on every other conversation endpoint")
    void adminAllowed() throws Exception {
        storeAs("admin", "eddi-admin").patchConversation(CONVERSATION, new ConversationPatch(null, false));

        verify(descriptorStore).writeOwnerEdit(CONVERSATION, null, false);
    }

    @Test
    @DisplayName("an empty body, a blank title or an overlong one is 400")
    void invalidBody() {
        var store = storeAs(OWNER, "eddi-user");

        assertThrows(BadRequestException.class, () -> store.patchConversation(CONVERSATION, new ConversationPatch(null, null)));
        assertThrows(BadRequestException.class, () -> store.patchConversation(CONVERSATION, null));
        assertThrows(BadRequestException.class, () -> store.patchConversation(CONVERSATION, new ConversationPatch("  ", null)));
        assertThrows(BadRequestException.class, () -> store.patchConversation(CONVERSATION, new ConversationPatch("x".repeat(201), null)));
    }

    @Test
    @DisplayName("a soft-deleted conversation (no live descriptor) is 404")
    void deletedIsNotFound() {
        when(descriptorStore.writeOwnerEdit(eq(CONVERSATION), any(), any())).thenReturn(false);
        var store = storeAs(OWNER, "eddi-user");

        assertThrows(NotFoundException.class, () -> store.patchConversation(CONVERSATION, new ConversationPatch("Title", null)));
    }
}
