/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ContentHashes} — change detection for ingestion dedup.
 */
class ContentHashesTest {

    @Test
    @DisplayName("hashes the known SHA-256 of a fixed input")
    void matchesKnownVector() {
        // Pinned to a published vector rather than to the implementation: if this
        // ever changes, every knowledge base re-embeds itself on the next run.
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                ContentHashes.sha256("hello"));
    }

    @Test
    @DisplayName("is stable across calls")
    void isStable() {
        assertEquals(ContentHashes.sha256("some content"), ContentHashes.sha256("some content"));
    }

    @Test
    @DisplayName("distinguishes different content")
    void distinguishesContent() {
        assertNotEquals(ContentHashes.sha256("version one"), ContentHashes.sha256("version two"));
    }

    @Test
    @DisplayName("is whitespace sensitive — a reformatted page is a changed page")
    void isWhitespaceSensitive() {
        assertNotEquals(ContentHashes.sha256("a b"), ContentHashes.sha256("a  b"));
    }

    @Test
    @DisplayName("null hashes as empty rather than as the string \"null\"")
    void nullHashesAsEmpty() {
        assertEquals(ContentHashes.sha256(""), ContentHashes.sha256(null));
        assertNotEquals(ContentHashes.sha256("null"), ContentHashes.sha256(null));
    }

    @Test
    @DisplayName("returns lowercase hex of fixed length")
    void returnsLowercaseHex() {
        String hash = ContentHashes.sha256("content");

        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"), hash);
    }

    @Test
    @DisplayName("handles non-ASCII content")
    void handlesNonAscii() {
        assertNotEquals(ContentHashes.sha256("naive"), ContentHashes.sha256("naïve"));
        assertEquals(64, ContentHashes.sha256("日本語のドキュメント").length());
    }
}
