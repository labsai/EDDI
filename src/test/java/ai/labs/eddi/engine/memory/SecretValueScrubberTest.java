/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.model.Context;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
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
    @DisplayName("a map key carrying the plaintext is scrubbed with the values")
    void mapKey() {
        assertEquals(Map.of("id-" + MARK, "v"), SecretValueScrubber.scrubValue(Map.of("id-" + SECRET, "v"), SECRET, MARK));
        assertEquals(Map.of(MARK, List.of(MARK)), SecretValueScrubber.scrubDeep(Map.of(SECRET, List.of(SECRET)), List.of(SECRET), MARK));
    }

    @Test
    @DisplayName("a number equal to a plaintext is replaced; other numbers are not")
    void number() {
        assertEquals(List.of(MARK, 87654321L), SecretValueScrubber.scrubDeep(List.of(12345678L, 87654321L), List.of("12345678"), MARK));
        assertNull(SecretValueScrubber.scrubDeep(123456789L, List.of("12345678"), MARK), "a number only matches whole");
    }

    @Test
    @DisplayName("the longest plaintext wins whatever order the caller passes")
    void orderIndependent() {
        assertEquals(MARK, SecretValueScrubber.scrubAll(SECRET, List.of("tok-aaaa", SECRET), MARK));
        assertEquals(MARK, SecretValueScrubber.scrubDeep(SECRET, List.of("tok-aaaa", SECRET), MARK));
    }

    /** A typed value that has to come back as its own type. */
    public static class Holder {
        public String header;
        public List<String> args;
    }

    @Test
    @DisplayName("scrubTyped returns a scrubbed copy of the same type, or null when clean")
    void typed() {
        var holder = new Holder();
        holder.header = "Bearer " + SECRET;
        holder.args = List.of("keep");

        Holder cleaned = SecretValueScrubber.scrubTyped(holder, Holder.class, List.of(SECRET), MARK);

        assertEquals("Bearer " + MARK, cleaned.header);
        assertEquals(List.of("keep"), cleaned.args);
        assertEquals("Bearer " + SECRET, holder.header, "the original is not mutated");
        holder.header = "clean";
        assertNull(SecretValueScrubber.scrubTyped(holder, Holder.class, List.of(SECRET), MARK));
    }

    @Test
    @DisplayName("null and empty plaintexts are ignored instead of expanding every string")
    void emptyPlaintexts() {
        assertNull(SecretValueScrubber.scrubValue("abc", "", MARK));
        assertNull(SecretValueScrubber.scrubValue("abc", null, MARK));
        assertNull(SecretValueScrubber.scrubAll("abc", Arrays.asList("", null), MARK));
        assertEquals("x " + MARK, SecretValueScrubber.scrubDeep("x " + SECRET, Arrays.asList("", null, SECRET), MARK));
    }

    @Test
    @DisplayName("collectPlaintexts walks lists and maps and keeps scalar string forms")
    void collect() {
        Set<String> found = new LinkedHashSet<>();

        SecretValueScrubber.collectPlaintexts(Map.of("a", List.of(SECRET, 12345678L), "b", true), found);

        assertEquals(Set.of(SECRET, "12345678", "true"), found);
    }
}
