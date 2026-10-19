/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.descriptor;

import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import ai.labs.eddi.engine.security.OwnershipValidator;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.security.Principal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@link ConversationDescriptorMemo}: one descriptor read per request, shared
 * by the ownership checks and the soft-delete check, without sharing a
 * decision.
 */
class ConversationDescriptorMemoTest {

    private static final String ID = "6720f0a1b2c3d4e5f6a7b8c9";

    private IConversationDescriptorStore store;
    private Instance<ConversationDescriptorMemo> memo;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        store = mock(IConversationDescriptorStore.class);
        memo = mock(Instance.class);
        var requestMemo = new ConversationDescriptorMemo();
        when(memo.isResolvable()).thenReturn(true);
        when(memo.get()).thenReturn(requestMemo);
    }

    private static ConversationDescriptor ownedBy(String owner) {
        var descriptor = new ConversationDescriptor();
        descriptor.setUserId(owner);
        return descriptor;
    }

    @Test
    void readsTheStoreOncePerRequest() throws Exception {
        doReturn(ownedBy("alice")).when(store).readDescriptor(ID, 0);

        var first = ConversationDescriptorMemo.resolve(store, memo, ID);
        var second = ConversationDescriptorMemo.resolve(store, memo, ID);

        assertSame(first.descriptor(), second.descriptor());
        assertFalse(second.archived());
        verify(store, times(1)).readDescriptor(ID, 0);
    }

    @Test
    void remembersASoftDeletedConversationAsArchived() throws Exception {
        doThrow(new ResourceNotFoundException("gone")).when(store).readDescriptor(ID, 0);
        doReturn(ownedBy("alice")).when(store).readDescriptorWithHistory(ID, 0);

        assertTrue(ConversationDescriptorMemo.resolve(store, memo, ID).archived());
        assertTrue(ConversationDescriptorMemo.resolve(store, memo, ID).archived());
        verify(store, times(1)).readDescriptorWithHistory(ID, 0);
    }

    @Test
    void remembersThatThereIsNoDescriptor() throws Exception {
        doThrow(new ResourceNotFoundException("gone")).when(store).readDescriptor(ID, 0);
        doThrow(new ResourceNotFoundException("gone")).when(store).readDescriptorWithHistory(ID, 0);

        assertSame(ConversationDescriptorMemo.Resolved.NONE, ConversationDescriptorMemo.resolve(store, memo, ID));
        assertSame(ConversationDescriptorMemo.Resolved.NONE, ConversationDescriptorMemo.resolve(store, memo, ID));
        verify(store, times(1)).readDescriptor(ID, 0);
    }

    @Test
    void aStoreFailureIsNotRemembered() throws Exception {
        doThrow(new ResourceStoreException("down")).doReturn(ownedBy("alice")).when(store).readDescriptor(ID, 0);

        assertThrows(ResourceStoreException.class, () -> ConversationDescriptorMemo.resolve(store, memo, ID));
        assertEquals("alice", ConversationDescriptorMemo.resolve(store, memo, ID).descriptor().getUserId());
        verify(store, times(2)).readDescriptor(ID, 0);
    }

    @Test
    void aWriteForgetsTheConversation() throws Exception {
        doReturn(ownedBy("alice")).doReturn(ownedBy("bob")).when(store).readDescriptor(ID, 0);

        assertEquals("alice", ConversationDescriptorMemo.resolve(store, memo, ID).descriptor().getUserId());
        ConversationDescriptorMemo.forget(memo, ID);
        assertEquals("bob", ConversationDescriptorMemo.resolve(store, memo, ID).descriptor().getUserId());
    }

    @Test
    void outsideARequestEveryLookupReadsTheStore() throws Exception {
        when(memo.get()).thenThrow(new ContextNotActiveException("no request"));
        doReturn(ownedBy("alice")).when(store).readDescriptor(ID, 0);

        ConversationDescriptorMemo.resolve(store, memo, ID);
        ConversationDescriptorMemo.resolve(store, null, ID);
        ConversationDescriptorMemo.resolve(store, memo, ID);

        verify(store, times(3)).readDescriptor(ID, 0);
    }

    @Test
    void theGuardSharesTheReadButStillDecidesPerCaller() throws Exception {
        doReturn(ownedBy("alice")).when(store).readDescriptor(anyString(), anyInt());
        var owner = guard(identity("alice"));
        var stranger = guard(identity("mallory"));

        assertEquals("alice", owner.requireConversationOwner(ID));
        assertEquals("alice", owner.requireConversationOwner(ID));
        assertThrows(ForbiddenException.class, () -> stranger.requireConversationOwner(ID));

        verify(store, times(1)).readDescriptor(ID, 0);
    }

    private ConversationAccessGuard guard(SecurityIdentity identity) throws Exception {
        var guard = new ConversationAccessGuard(identity, new OwnershipValidator(true), store);
        Field field = ConversationAccessGuard.class.getDeclaredField("descriptorMemoInstance");
        field.setAccessible(true);
        field.set(guard, memo);
        return guard;
    }

    private static SecurityIdentity identity(String name) {
        var identity = mock(SecurityIdentity.class);
        var principal = mock(Principal.class);
        lenient().when(principal.getName()).thenReturn(name);
        lenient().when(identity.getPrincipal()).thenReturn(principal);
        lenient().when(identity.hasRole("eddi-user")).thenReturn(true);
        return identity;
    }
}
