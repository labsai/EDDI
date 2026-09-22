/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("NestedReferences — a parser document's dictionaries across instances")
class NestedReferencesTest {

    private static final String DICT = "eddi://ai.labs.dictionary/dictionarystore/dictionaries/";
    private static final String RULES = "eddi://ai.labs.rules/rulestore/rulesets/";

    private static final String SOURCE_A = "aaaa11112222333344445555";
    private static final String SOURCE_B = "bbbb11112222333344445555";
    private static final String TARGET_A = "cccc11112222333344445555";
    private static final String TARGET_B = "dddd11112222333344445555";

    private static String parser(String... uris) {
        StringBuilder dictionaries = new StringBuilder();
        for (String uri : uris) {
            if (!dictionaries.isEmpty()) {
                dictionaries.append(',');
            }
            dictionaries.append("{\"type\":\"eddi://ai.labs.parser.dictionaries.regular\",\"config\":{\"uri\":\"")
                    .append(uri).append("\"}}");
        }
        return "{\"extensions\":{\"dictionaries\":[" + dictionaries + "]}}";
    }

    @Test
    @DisplayName("each source reference becomes the target's reference at the same position")
    void pairsByPosition() {
        String source = parser(DICT + SOURCE_A + "?version=4", DICT + SOURCE_B + "?version=1");
        String target = parser(DICT + TARGET_A + "?version=2", DICT + TARGET_B + "?version=7");

        assertEquals(target, NestedReferences.repointAgainst(source, target, Map.of()),
                "the same parser on both sides must compare equal once its ids are the target's");
    }

    @Test
    @DisplayName("a dictionary this run wrote is named at the version it was written at")
    void movesOntoTheVersionJustWritten() {
        String source = parser(DICT + SOURCE_A + "?version=4");
        String target = parser(DICT + TARGET_A + "?version=2");

        String repointed = NestedReferences.repointAgainst(source, target,
                Map.of(TARGET_A, URI.create(DICT + TARGET_A + "?version=3")));

        assertEquals(parser(DICT + TARGET_A + "?version=3"), repointed);
    }

    @Test
    @DisplayName("documents that do not line up are left as the source wrote them")
    void mismatchedCountIsNotGuessed() {
        String source = parser(DICT + SOURCE_A + "?version=1", DICT + SOURCE_B + "?version=1");
        String target = parser(DICT + TARGET_A + "?version=1");

        assertSame(source, NestedReferences.repointAgainst(source, target, Map.of()),
                "a dictionary added on the source is a change the preview has to show");
    }

    @Test
    @DisplayName("a position naming a different kind of resource is not paired")
    void mismatchedTypeIsNotGuessed() {
        String source = parser(DICT + SOURCE_A + "?version=1");
        String target = parser(RULES + TARGET_A + "?version=1");

        assertSame(source, NestedReferences.repointAgainst(source, target, Map.of()));
    }

    @Test
    @DisplayName("one source reference paired with two different targets is not guessed either")
    void conflictingPairingIsNotGuessed() {
        String source = parser(DICT + SOURCE_A + "?version=1", DICT + SOURCE_A + "?version=1");
        String target = parser(DICT + TARGET_A + "?version=1", DICT + TARGET_B + "?version=1");

        assertSame(source, NestedReferences.repointAgainst(source, target, Map.of()));
    }

    @Test
    @DisplayName("with no target copy, or nothing to pair, the source comes back unchanged")
    void nothingToPair() {
        String source = parser(DICT + SOURCE_A + "?version=1");

        assertSame(source, NestedReferences.repointAgainst(source, null, Map.of()));
        String empty = parser();
        assertSame(empty, NestedReferences.repointAgainst(empty, parser(), Map.of()));
    }

    @Test
    @DisplayName("namesAny answers by resource id, whatever the version")
    void namesAny() {
        String doc = parser(DICT + TARGET_A + "?version=9");

        assertTrue(NestedReferences.namesAny(doc, Set.of(TARGET_A)));
        assertFalse(NestedReferences.namesAny(doc, List.of(TARGET_B)));
        assertFalse(NestedReferences.namesAny(null, Set.of(TARGET_A)));
    }
}
