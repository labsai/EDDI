/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.IResourceFilter.ContainsIgnoreCase;
import ai.labs.eddi.datastore.IResourceFilter.QueryFilter;
import ai.labs.eddi.datastore.IResourceFilter.QueryFilters;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.memory.model.ConversationListingSummary;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import ai.labs.eddi.engine.security.OwnershipValidator;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code GET /conversationstore/conversations/search} — the caller's own
 * conversations by title. Built over a real {@link ConversationAccessGuard};
 * the descriptor store is mocked, so the assertions are on the query the search
 * hands it (owner scope, title-only text, paging) and on what it does with the
 * rows that come back.
 */
@DisplayName("RestConversationStore — search my conversations")
class RestConversationStoreSearchTest {

    private static final String OWNER = "owner-user";

    private IConversationDescriptorStore descriptorStore;
    private IConversationMemoryStore memoryStore;
    private final List<ConversationDescriptor> storeRows = new ArrayList<>();
    private final Map<String, ConversationListingSummary> memories = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        descriptorStore = mock(IConversationDescriptorStore.class);
        memoryStore = mock(IConversationMemoryStore.class);
        lenient().when(descriptorStore.readDescriptors(anyString(), any(), anyInt(), anyInt(), anyBoolean(), any())).thenAnswer(i -> storeRows);
        lenient().when(memoryStore.loadListingSummaries(any())).thenAnswer(i -> {
            Map<String, ConversationListingSummary> found = new HashMap<>();
            for (Object id : (Collection<?>) i.getArgument(0)) {
                if (memories.containsKey(id)) {
                    found.put((String) id, memories.get(id));
                }
            }
            return found;
        });
    }

    private void row(String conversationId, String owner, String title) {
        var descriptor = new ConversationDescriptor();
        descriptor.setResource(URI.create("eddi://ai.labs.conversation/conversationstore/conversations/" + conversationId + "?version=0"));
        descriptor.setUserId(owner);
        descriptor.setTitle(title);
        descriptor.setAgentName("Travel");
        storeRows.add(descriptor);
        memories.put(conversationId, new ConversationListingSummary(conversationId, owner, null, ConversationState.READY, "agent1", 1, 3));
    }

    @SuppressWarnings("unchecked")
    private RestConversationStore storeAs(String caller, boolean authEnabled, String... roles) {
        var identity = mock(SecurityIdentity.class);
        if (caller == null) {
            lenient().when(identity.isAnonymous()).thenReturn(true);
        } else {
            var principal = mock(Principal.class);
            lenient().when(principal.getName()).thenReturn(caller);
            lenient().when(identity.getPrincipal()).thenReturn(principal);
            lenient().when(identity.isAnonymous()).thenReturn(false);
        }
        for (String role : roles) {
            lenient().when(identity.hasRole(role)).thenReturn(true);
        }
        var guard = new ConversationAccessGuard(identity, new OwnershipValidator(authEnabled), descriptorStore);
        return new RestConversationStore(mock(IDocumentDescriptorStore.class), descriptorStore, memoryStore, mock(IConversationService.class),
                mock(IUserMemoryStore.class), mock(IRuntime.class), guard, mock(ResourceAccessGuard.class), 30, 90, mock(Instance.class));
    }

    @SuppressWarnings("unchecked")
    private List<QueryFilters> capturedRestrictions(Integer index, Integer limit) throws Exception {
        ArgumentCaptor<List<QueryFilters>> captor = ArgumentCaptor.forClass(List.class);
        verify(descriptorStore).readDescriptors(eq(RestConversationStore.DESCRIPTOR_TYPE), eq(null), eq(index), eq(limit), eq(false),
                captor.capture());
        return captor.getValue();
    }

    private static List<QueryFilter> flatten(List<QueryFilters> groups) {
        List<QueryFilter> all = new ArrayList<>();
        groups.forEach(group -> all.addAll(group.getQueryFilters()));
        return all;
    }

    @Test
    @DisplayName("the query is scoped to the caller by an exact owner match, and matches the title case-insensitively")
    void ownerScopedTitleQuery() throws Exception {
        storeAs(OWNER, true, "eddi-user").searchConversations("Rome", 0, 20);

        var filters = flatten(capturedRestrictions(0, 20));
        assertEquals(2, filters.size(), "exactly the owner and the title: " + filters);
        var owner = filters.get(0);
        assertEquals("userId", owner.getField());
        assertTrue(owner.isExact(), "the owner is an equality, never a pattern");
        assertEquals(OWNER, owner.getFilter());
        var title = filters.get(1);
        assertEquals(ConversationDescriptor.FIELD_TITLE, title.getField());
        assertEquals(new ContainsIgnoreCase("Rome"), title.getFilter());
    }

    @Test
    @DisplayName("an administrator searches their own conversations too, not everybody's")
    void adminIsScopedToOwnConversations() throws Exception {
        storeAs("admin", true, "eddi-admin").searchConversations("Rome", 0, 20);

        var owner = flatten(capturedRestrictions(0, 20)).get(0);
        assertEquals("userId", owner.getField());
        assertEquals("admin", owner.getFilter());
    }

    @Test
    @DisplayName("a row of another user that the store returns anyway is never handed out")
    void foreignRowIsDropped() throws Exception {
        row("aaaaaaaaaaaaaaaaaaaaaaa1", OWNER, "Trip to Rome");
        row("aaaaaaaaaaaaaaaaaaaaaaa2", "someone-else", "Rome, their trip");

        var results = storeAs(OWNER, true, "eddi-user").searchConversations("rome", 0, 20);

        assertEquals(1, results.size());
        assertEquals("Trip to Rome", results.getFirst().getTitle());
        assertEquals(OWNER, results.getFirst().getUserId());
    }

    @Test
    @DisplayName("a match whose conversation memory is gone is left out — an erased or purged conversation is not found")
    void orphanIsLeftOut() throws Exception {
        row("aaaaaaaaaaaaaaaaaaaaaaa1", OWNER, "Trip to Rome");
        row("aaaaaaaaaaaaaaaaaaaaaaa2", OWNER, "Rome again");
        memories.remove("aaaaaaaaaaaaaaaaaaaaaaa2");

        var results = storeAs(OWNER, true, "eddi-user").searchConversations("rome", 0, 20);

        assertEquals(List.of("Trip to Rome"), results.stream().map(ConversationDescriptor::getTitle).toList());
        assertEquals(ConversationState.READY, results.getFirst().getConversationState(), "filled from the memory, like the listing");
        assertEquals(3, results.getFirst().getConversationStepSize());
    }

    @Test
    @DisplayName("titles only: no other descriptor field and no free-text listing filter is searched")
    void titlesOnly() throws Exception {
        storeAs(OWNER, true, "eddi-user").searchConversations("Rome", 0, 20);

        // The listing's free-text `filter` (null here) would OR the text over userId,
        // name, agentName, description and resource. Search must not use it.
        var textFilters = flatten(capturedRestrictions(0, 20)).stream()
                .filter(f -> f.getFilter() instanceof ContainsIgnoreCase)
                .toList();
        assertEquals(1, textFilters.size());
        assertEquals(ConversationDescriptor.FIELD_TITLE, textFilters.getFirst().getField());
    }

    @Test
    @DisplayName("with authorization disabled there is no owner to scope to: the title alone")
    void authDisabledSearchesEverything() throws Exception {
        storeAs(null, false).searchConversations("Rome", 0, 20);

        var filters = flatten(capturedRestrictions(0, 20));
        assertEquals(1, filters.size());
        assertEquals(ConversationDescriptor.FIELD_TITLE, filters.getFirst().getField());
    }

    @Test
    @DisplayName("an anonymous caller with authorization on finds nothing, and the store is not asked")
    void anonymousFindsNothing() throws Exception {
        var results = storeAs(null, true).searchConversations("Rome", 0, 20);

        assertTrue(results.isEmpty());
        verify(descriptorStore, never()).readDescriptors(anyString(), any(), anyInt(), anyInt(), anyBoolean(), any());
    }

    @Test
    @DisplayName("the text is normalized like a stored title and handed over as literal text")
    void queryIsNormalizedLiteralText() throws Exception {
        storeAs(OWNER, true, "eddi-user").searchConversations("  50%_off \n (a+b)  ", 0, 20);

        var title = flatten(capturedRestrictions(0, 20)).get(1);
        // Not escaped here: ContainsIgnoreCase is literal text by contract, and each
        // backend escapes it for its own matcher (pinned in the storage tests).
        assertEquals(new ContainsIgnoreCase("50%_off (a+b)"), title.getFilter());
    }

    @Test
    @DisplayName("a blank, missing or overlong text is 400 and the store is not asked")
    void invalidQuery() throws Exception {
        var store = storeAs(OWNER, true, "eddi-user");

        assertThrows(BadRequestException.class, () -> store.searchConversations(null, 0, 20));
        assertThrows(BadRequestException.class, () -> store.searchConversations(" \t\n", 0, 20));
        assertThrows(BadRequestException.class, () -> store.searchConversations("x".repeat(101), 0, 20));
        verify(descriptorStore, never()).readDescriptors(anyString(), any(), anyInt(), anyInt(), anyBoolean(), any());
    }

    @Test
    @DisplayName("100 characters is accepted")
    void longestQueryAccepted() throws Exception {
        storeAs(OWNER, true, "eddi-user").searchConversations("x".repeat(100), 0, 20);

        assertEquals(new ContainsIgnoreCase("x".repeat(100)), flatten(capturedRestrictions(0, 20)).get(1).getFilter());
    }

    @Test
    @DisplayName("paging is passed to the store; a negative index or a page past 10,000 results is 400")
    void paging() throws Exception {
        var store = storeAs(OWNER, true, "eddi-user");
        store.searchConversations("Rome", 3, 25);
        capturedRestrictions(3, 25);

        assertThrows(BadRequestException.class, () -> store.searchConversations("Rome", -1, 20));
        assertThrows(BadRequestException.class, () -> store.searchConversations("Rome", 501, 20));
    }

    @Test
    @DisplayName("defaults: index 0, limit 20")
    void defaults() throws Exception {
        storeAs(OWNER, true, "eddi-user").searchConversations("Rome", null, null);

        capturedRestrictions(0, 20);
    }

    @Test
    @DisplayName("a match with no resource id is skipped rather than failing the page")
    void descriptorWithoutResourceIsSkipped() throws Exception {
        row("aaaaaaaaaaaaaaaaaaaaaaa1", OWNER, "Trip to Rome");
        var broken = new ConversationDescriptor();
        broken.setUserId(OWNER);
        broken.setTitle("Rome without a resource");
        storeRows.add(broken);

        var results = storeAs(OWNER, true, "eddi-user").searchConversations("rome", 0, 20);

        assertEquals(1, results.size());
        assertInstanceOf(ConversationDescriptor.class, results.getFirst());
        assertNull(broken.getConversationState());
    }
}
