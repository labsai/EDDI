/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.properties.rest;

import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Property.Visibility;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.engine.security.GroupMemoryAccessGuard;
import ai.labs.eddi.engine.security.OwnershipValidator;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins memory self-service for end users (frontend-unification plan, B5): an
 * {@code eddi-user} reads and changes only their own memories. Runs the real
 * {@link OwnershipValidator} with authorization on, so a regression in either
 * the resource or the validator fails here — every method of the resource that
 * takes a user id, and both that address an entry, are refused for another
 * user's data before the store is touched.
 */
class RestUserMemoryStoreCrossUserTest {

    private static final String ALICE = "alice";
    private static final String BOB = "bob";

    private IUserMemoryStore store;
    private RestUserMemoryStore asAlice;

    @BeforeEach
    void setUp() {
        store = mock(IUserMemoryStore.class);
        asAlice = new RestUserMemoryStore(store, user(ALICE), new OwnershipValidator(true), mock(GroupMemoryAccessGuard.class));
    }

    private static SecurityIdentity user(String name) {
        SecurityIdentity identity = mock(SecurityIdentity.class);
        Principal principal = mock(Principal.class);
        when(principal.getName()).thenReturn(name);
        when(identity.getPrincipal()).thenReturn(principal);
        when(identity.isAnonymous()).thenReturn(false);
        when(identity.hasRole("eddi-user")).thenReturn(true);
        return identity;
    }

    private static UserMemoryEntry entryOf(String userId) {
        return new UserMemoryEntry("entry-1", userId, "fav_color", "blue", "preference", Visibility.self, "agent-1", List.of(),
                "conv-1", false, 0, Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("eddi-user A gets 403 on every read of user B's memories, and the store is never read")
    void readsOfAnotherUserAreForbidden() {
        List<Executable> reads = List.of(
                () -> asAlice.getAllMemories(BOB),
                () -> asAlice.getVisibleMemories(BOB, "agent-1", List.of(), "most_recent", 50),
                () -> asAlice.searchMemories(BOB, "color"),
                () -> asAlice.getMemoriesByCategory(BOB, "preference"),
                () -> asAlice.getMemoryByKey(BOB, "fav_color"),
                () -> asAlice.countMemories(BOB));
        for (Executable read : reads) {
            assertThrows(ForbiddenException.class, read);
        }
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("eddi-user A gets 403 writing or deleting user B's memories")
    void writesToAnotherUserAreForbidden() throws Exception {
        assertThrows(ForbiddenException.class, () -> asAlice.upsertMemory(entryOf(BOB)));
        assertThrows(ForbiddenException.class, () -> asAlice.deleteAllForUser(BOB));

        when(store.findEntryById("entry-1")).thenReturn(Optional.of(entryOf(BOB)));
        assertThrows(ForbiddenException.class, () -> asAlice.deleteMemory("entry-1"));
    }

    @Test
    @DisplayName("the same user reads their own memories")
    void ownMemoriesAreReadable() throws Exception {
        when(store.getAllEntries(ALICE)).thenReturn(List.of(entryOf(ALICE)));
        when(store.countEntries(ALICE)).thenReturn(1L);
        when(store.getVisibleEntries(any(), any(), any(), any(), anyInt())).thenReturn(List.of());

        assertDoesNotThrow(() -> asAlice.getAllMemories(ALICE));
        assertDoesNotThrow(() -> asAlice.countMemories(ALICE));
        assertDoesNotThrow(() -> asAlice.getVisibleMemories(ALICE, "agent-1", List.of(), "most_recent", 50));
    }
}
