/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.properties;

import ai.labs.eddi.configs.properties.model.Property.Visibility;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * H9c: the {@code _gdpr_} keys are EDDI's own GDPR bookkeeping, not user
 * memories — the contract helpers every write path relies on.
 */
class UserMemoryReservedKeysTest {

    @Test
    void isReservedKey_matchesThePrefixExactly() {
        assertTrue(IUserMemoryStore.isReservedKey("_gdpr_processing_restricted"));
        assertTrue(IUserMemoryStore.isReservedKey("_gdpr_"));
        assertFalse(IUserMemoryStore.isReservedKey("agdpr1"));
        assertFalse(IUserMemoryStore.isReservedKey("gdpr_processing_restricted"));
        assertFalse(IUserMemoryStore.isReservedKey("_GDPR_processing_restricted"));
        assertFalse(IUserMemoryStore.isReservedKey(null));
    }

    @Test
    void rejectReservedKey_throwsAnIllegalArgumentNamingTheKey() {
        var e = assertThrows(IUserMemoryStore.ReservedMemoryKeyException.class,
                () -> IUserMemoryStore.rejectReservedKey("_gdpr_processing_restricted"));
        assertInstanceOf(IllegalArgumentException.class, e);
        assertTrue(e.getMessage().contains("_gdpr_processing_restricted"));
        assertDoesNotThrow(() -> IUserMemoryStore.rejectReservedKey("favorite_color"));
    }

    /**
     * The REST and MCP "delete all memories" surfaces used deleteAllForUser, so a
     * restricted user clearing their memories lifted their own Art. 18 flag.
     */
    @Test
    void deleteAllExceptReserved_keepsTheGdprRows() throws Exception {
        IUserMemoryStore store = mock(IUserMemoryStore.class, CALLS_REAL_METHODS);
        Instant now = Instant.now();
        doReturn(List.of(
                new UserMemoryEntry("m1", "u1", "favorite_color", "blue", "preference", Visibility.self, "a1", List.of(), null, false, 0,
                        now, now),
                new UserMemoryEntry("flag", "u1", "_gdpr_processing_restricted", "true", "gdpr", Visibility.global, null, List.of(), null,
                        false, 0, now, now),
                new UserMemoryEntry("m2", "u1", "diet", "vegan", "fact", Visibility.global, null, List.of(), null, false, 0, now, now)))
                .when(store).getAllEntries("u1");
        doNothing().when(store).deleteEntry(anyString());

        assertEquals(2, store.deleteAllExceptReserved("u1"));

        verify(store).deleteEntry("m1");
        verify(store).deleteEntry("m2");
        verify(store, never()).deleteEntry("flag");
        verify(store, never()).deleteAllForUser(any());
    }
}
