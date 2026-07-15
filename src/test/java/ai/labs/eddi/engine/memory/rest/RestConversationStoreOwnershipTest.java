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
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import ai.labs.eddi.engine.security.OwnershipValidator;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ownership tests for the conversation-listing REST endpoint
 * ({@code GET /conversationstore/conversations} →
 * {@link RestConversationStore#readConversationDescriptors}).
 * <p>
 * Before this, the endpoint carried no {@code @RolesAllowed} and no ownership
 * filter, so it fell through to the global {@code authenticated} policy — any
 * authenticated caller could enumerate <em>every</em> user's conversation
 * descriptors (ids, agent, state, owner). It is the REST twin of the MCP
 * {@code list_conversations} gap; the same {@link ConversationAccessGuard} now
 * enforces owner-or-admin visibility here.
 * <p>
 * Each test builds the store as a concrete caller (a real guard over a mocked
 * {@link SecurityIdentity} with {@code authorization.enabled=true}) and asserts
 * which descriptors survive the listing.
 */
class RestConversationStoreOwnershipTest {

    private static final String OWNER = "owner-user";
    private static final String INTRUDER = "intruder-user";

    private IDocumentDescriptorStore documentDescriptorStore;
    private IConversationDescriptorStore conversationDescriptorStore;
    private IConversationMemoryStore conversationMemoryStore;
    private IConversationService conversationService;
    private IUserMemoryStore userMemoryStore;
    private IRuntime runtime;
    private Instance<IAttachmentStore> attachmentStorageInstance;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        conversationDescriptorStore = mock(IConversationDescriptorStore.class);
        conversationMemoryStore = mock(IConversationMemoryStore.class);
        conversationService = mock(IConversationService.class);
        userMemoryStore = mock(IUserMemoryStore.class);
        runtime = mock(IRuntime.class);
        attachmentStorageInstance = mock(Instance.class);

        // populateDataToDescriptor loads a snapshot for every descriptor; a non-null
        // snapshot with an empty step list keeps it on the normal (non-orphan) path.
        // Its userId is null so it never overrides the owner recorded on a descriptor
        // — the descriptors below carry their own owner, except the legacy case which
        // is deliberately left unowned all the way down.
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.READY);
        snapshot.setConversationSteps(new ArrayList<>());
        lenient().when(conversationMemoryStore.loadConversationMemorySnapshot(anyString())).thenReturn(snapshot);
    }

    /** A conversation descriptor already carrying its owner and agent name. */
    private ConversationDescriptor descriptor(String conversationId, String ownerId) {
        var descriptor = new ConversationDescriptor();
        descriptor.setResource(URI.create(
                "eddi://ai.labs.conversation/conversationstore/conversations/" + conversationId + "?version=1"));
        descriptor.setUserId(ownerId);
        descriptor.setAgentName("Some Agent"); // non-empty → skip the documentDescriptor lookup
        descriptor.setLastModifiedOn(new Date());
        return descriptor;
    }

    /** The store as seen by {@code caller}, with authorization enabled. */
    private RestConversationStore storeAs(String caller, String... roles) {
        var identity = mock(SecurityIdentity.class);
        var principal = mock(Principal.class);
        lenient().when(principal.getName()).thenReturn(caller);
        lenient().when(identity.getPrincipal()).thenReturn(principal);
        lenient().when(identity.isAnonymous()).thenReturn(false);
        for (String role : roles) {
            lenient().when(identity.hasRole(role)).thenReturn(true);
        }
        var guard = new ConversationAccessGuard(identity, new OwnershipValidator(true),
                mock(IConversationDescriptorStore.class));
        return new RestConversationStore(documentDescriptorStore, conversationDescriptorStore,
                conversationMemoryStore, conversationService, userMemoryStore, runtime, guard,
                30, 90, attachmentStorageInstance);
    }

    private RestConversationStore asOwner() {
        return storeAs(OWNER, "eddi-viewer");
    }

    private RestConversationStore asIntruder() {
        return storeAs(INTRUDER, "eddi-viewer");
    }

    private RestConversationStore asAdmin() {
        return storeAs("admin-user", "eddi-viewer", "eddi-admin");
    }

    /** First (and only) descriptor page the store hands back. */
    private void firstPage(ConversationDescriptor... descriptors) throws Exception {
        when(conversationDescriptorStore.readDescriptors(anyString(), any(), eq(0), anyInt(), anyBoolean()))
                .thenReturn(List.of(descriptors));
    }

    @Test
    @DisplayName("a caller sees only their own conversations, never another user's")
    void filtersToOwnConversations() throws Exception {
        firstPage(descriptor("conv-owner", OWNER), descriptor("conv-intruder", INTRUDER));

        List<ConversationDescriptor> result = asOwner().readConversationDescriptors(
                0, 20, null, null, null, null, null, null);

        assertEquals(1, result.size());
        assertEquals(OWNER, result.get(0).getUserId());
    }

    @Test
    @DisplayName("a non-owner enumerating the store sees nothing of another user's conversations")
    void nonOwnerSeesNoForeignConversations() throws Exception {
        firstPage(descriptor("conv-owner", OWNER));

        List<ConversationDescriptor> result = asIntruder().readConversationDescriptors(
                0, 20, null, null, null, null, null, null);

        assertTrue(result.isEmpty(), "an intruder must not enumerate the owner's conversation");
    }

    @Test
    @DisplayName("an admin sees every user's conversations")
    void adminSeesAllConversations() throws Exception {
        firstPage(descriptor("conv-owner", OWNER), descriptor("conv-intruder", INTRUDER));

        List<ConversationDescriptor> result = asAdmin().readConversationDescriptors(
                0, 20, null, null, null, null, null, null);

        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("an unowned (legacy) conversation stays visible — matching requireOwnerOrAdmin")
    void unownedLegacyConversationRemainsVisible() throws Exception {
        firstPage(descriptor("conv-legacy", null));

        List<ConversationDescriptor> result = asIntruder().readConversationDescriptors(
                0, 20, null, null, null, null, null, null);

        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("a personal list is back-filled across foreign pages, not starved by them")
    void personalListNotStarvedAcrossForeignPages() throws Exception {
        // The newest page is entirely other users' conversations; the caller's own
        // appears only on the next page. A single-page filter would report "none" —
        // the do-while must page forward (index is a page number: skip = index*limit)
        // until the owner's conversation is found.
        when(conversationDescriptorStore.readDescriptors(anyString(), any(), eq(0), anyInt(), anyBoolean()))
                .thenReturn(List.of(descriptor("conv-foreign-1", INTRUDER), descriptor("conv-foreign-2", INTRUDER)));
        when(conversationDescriptorStore.readDescriptors(anyString(), any(), eq(1), anyInt(), anyBoolean()))
                .thenReturn(List.of(descriptor("conv-owner", OWNER)));
        when(conversationDescriptorStore.readDescriptors(anyString(), any(), eq(2), anyInt(), anyBoolean()))
                .thenReturn(List.of());

        List<ConversationDescriptor> result = asOwner().readConversationDescriptors(
                0, 20, null, null, null, null, null, null);

        assertEquals(1, result.size());
        assertEquals(OWNER, result.get(0).getUserId());
    }
}
