/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.properties;

import ai.labs.eddi.configs.properties.model.Property.Visibility;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;

/**
 * The default {@code insertIfAbsent} matches the upsert identity, not the key:
 * a self or group entry with the same key is a different memory and must not
 * stop a global one from being written (the v5 migration skipped exactly that
 * case).
 */
class IUserMemoryStoreInsertIfAbsentTest {

    private static UserMemoryEntry entry(String key, Visibility visibility, String agent) {
        return new UserMemoryEntry("id-" + key + visibility, "user-1", key, "v", "c", visibility, agent, List.of(), null, false, 0, null, null);
    }

    private static IUserMemoryStore storeHolding(UserMemoryEntry... entries) throws Exception {
        IUserMemoryStore store = mock(IUserMemoryStore.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        doReturn(List.of(entries)).when(store).getAllEntries("user-1");
        doReturn("new-id").when(store).upsert(any());
        return store;
    }

    @Test
    @DisplayName("a scoped entry with the same key does not stand in for the global one")
    void scopedEntryDoesNotBlockGlobal() throws Exception {
        var store = storeHolding(entry("lang", Visibility.self, "agent-a"));

        assertTrue(store.insertIfAbsent(entry("lang", Visibility.global, null)));
        verify(store).upsert(any());
    }

    @Test
    @DisplayName("an existing global entry is kept")
    void existingGlobalWins() throws Exception {
        var store = storeHolding(entry("lang", Visibility.global, "agent-a"));

        assertFalse(store.insertIfAbsent(entry("lang", Visibility.global, null)));
        verify(store, never()).upsert(any());
    }

    @Test
    @DisplayName("a self entry is blocked only by this agent's own row")
    void selfIdentityIsPerAgent() throws Exception {
        var store = storeHolding(entry("lang", Visibility.self, "agent-b"), entry("lang", Visibility.global, null));

        assertTrue(store.insertIfAbsent(entry("lang", Visibility.self, "agent-a")));
    }
}
