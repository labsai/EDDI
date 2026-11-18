/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments.blob;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("BlobKeys")
class BlobKeysTest {

    @Test
    void randomKeysAreValidHexAndDistinct() {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String key = BlobKeys.random();
            assertTrue(key.matches("[0-9a-f]{32}"), key);
            assertEquals(key, BlobKeys.requireValidKey(key));
            keys.add(key);
        }
        assertEquals(1000, keys.size());
    }

    @Test
    void acceptsTheKeyAlphabetUpToTheMaximumLength() {
        assertEquals("a", BlobKeys.requireValidKey("a"));
        assertEquals("Ab-9", BlobKeys.requireValidKey("Ab-9"));
        String longest = "k".repeat(BlobKeys.MAX_LENGTH);
        assertEquals(longest, BlobKeys.requireValidKey(longest));
        assertEquals("", BlobKeys.requireValidPrefix(""));
        assertEquals("-x", BlobKeys.requireValidPrefix("-x"));
    }

    @Test
    void refusesWildcardsPathsAndOverlongValues() {
        for (String bad : new String[]{"", "-a", "a_b", "a%", "a.b", "a/b", "..", "k".repeat(BlobKeys.MAX_LENGTH + 1)}) {
            assertThrows(IllegalArgumentException.class, () -> BlobKeys.requireValidKey(bad), bad);
        }
        for (String bad : new String[]{"%", "_", ".", "a/", "k".repeat(BlobKeys.MAX_LENGTH + 1)}) {
            assertThrows(IllegalArgumentException.class, () -> BlobKeys.requireValidPrefix(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> BlobKeys.requireValidKey(null));
        assertThrows(IllegalArgumentException.class, () -> BlobKeys.requireValidPrefix(null));
    }
}
