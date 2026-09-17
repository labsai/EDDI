/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.model.Context;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("SecretValueScrubber")
class SecretValueScrubberTest {

    private static final String SECRET = "tok-aaaa-bbbb-1111";
    private static final String MARK = "<x>";

    @Test
    @DisplayName("nested lists and maps are copied with the plaintext replaced")
    void nested() {
        Object cleaned = SecretValueScrubber.scrubValue(Map.of("headers", List.of("Bearer " + SECRET, "keep")), SECRET, MARK);

        assertEquals(Map.of("headers", List.of("Bearer " + MARK, "keep")), cleaned);
    }

    @Test
    @DisplayName("null when nothing carries the plaintext, and for types it cannot walk")
    void nothingToDo() {
        assertNull(SecretValueScrubber.scrubValue(Map.of("a", List.of("b")), SECRET, MARK));
        assertNull(SecretValueScrubber.scrubValue(42, SECRET, MARK));
    }

    @Test
    @DisplayName("a Context keeps its type and secret flag")
    void contextKeepsFlag() {
        var context = new Context(Context.ContextType.object, Map.of("token", SECRET));
        context.setSecret(true);

        Context cleaned = assertInstanceOf(Context.class, SecretValueScrubber.scrubValue(context, SECRET, MARK));

        assertEquals(Context.ContextType.object, cleaned.getType());
        assertEquals(Boolean.TRUE, cleaned.getSecret());
        assertEquals(Map.of("token", MARK), cleaned.getValue());
    }

    @Test
    @DisplayName("scrubAll replaces every plaintext, longest first")
    void scrubAll() {
        assertEquals("a " + MARK + " b " + MARK,
                SecretValueScrubber.scrubAll("a " + SECRET + " b tok-aaaa", List.of(SECRET, "tok-aaaa"), MARK));
        assertNull(SecretValueScrubber.scrubAll("clean", List.of(SECRET), MARK));
    }

    /** An object the plain walk cannot enter. */
    record Item(String type, String text) {
    }

    @Test
    @DisplayName("scrubDeep scrubs objects through their JSON form and leaves clean ones alone")
    void deep() {
        Object cleaned = SecretValueScrubber.scrubDeep(List.of(new Item("text", "Token was " + SECRET), new Item("text", "hi")), List.of(SECRET),
                MARK);

        assertEquals(List.of(Map.of("type", "text", "text", "Token was " + MARK), new Item("text", "hi")), cleaned);
        assertNull(SecretValueScrubber.scrubDeep(new Item("text", "clean"), List.of(SECRET), MARK));
        assertNull(SecretValueScrubber.scrubDeep(42, List.of(SECRET), MARK));
    }

    @Test
    @DisplayName("collectPlaintexts walks lists and maps and keeps scalar string forms")
    void collect() {
        Set<String> found = new LinkedHashSet<>();

        SecretValueScrubber.collectPlaintexts(Map.of("a", List.of(SECRET, 12345678L), "b", true), found);

        assertEquals(Set.of(SECRET, "12345678", "true"), found);
    }
}
