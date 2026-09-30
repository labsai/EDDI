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
import static org.junit.jupiter.api.Assertions.assertNull;
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
    @DisplayName("each reference becomes its counterpart on the target, whatever the version it named")
    void repointsBySourceId() {
        String source = parser(DICT + SOURCE_A + "?version=4", DICT + SOURCE_B + "?version=1");

        String repointed = NestedReferences.repointBySourceId(source, Map.of(
                SOURCE_A, URI.create(DICT + TARGET_A + "?version=2"),
                SOURCE_B, URI.create(DICT + TARGET_B + "?version=7")));

        assertEquals(parser(DICT + TARGET_A + "?version=2", DICT + TARGET_B + "?version=7"), repointed);
    }

    @Test
    @DisplayName("a reference with no counterpart is left as it is, so the document still reads as changed")
    void unmatchedReferenceIsKept() {
        String source = parser(DICT + SOURCE_A + "?version=1", DICT + SOURCE_B + "?version=1");

        String repointed = NestedReferences.repointBySourceId(source,
                Map.of(SOURCE_A, URI.create(DICT + TARGET_A + "?version=2")));

        assertEquals(parser(DICT + TARGET_A + "?version=2", DICT + SOURCE_B + "?version=1"), repointed);
    }

    @Test
    @DisplayName("a counterpart of a different kind of resource is never substituted")
    void differentTypeIsNotSubstituted() {
        String source = parser(DICT + SOURCE_A + "?version=1");

        assertSame(source, NestedReferences.repointBySourceId(source,
                Map.of(SOURCE_A, URI.create(RULES + TARGET_A + "?version=1"))));
    }

    @Test
    @DisplayName("nothing to swap hands back the very same document")
    void nothingToSwap() {
        String source = parser(DICT + SOURCE_A + "?version=1");

        assertSame(source, NestedReferences.repointBySourceId(source, Map.of()));
        assertSame(source, NestedReferences.repointBySourceId(source,
                Map.of(SOURCE_B, URI.create(DICT + TARGET_B + "?version=1"))));
        assertNull(NestedReferences.repointBySourceId(null, Map.of()));
    }

    @Test
    @DisplayName("a version is swapped whole: version=1 is never read as the start of version=10")
    void versionIsMatchedWhole() {
        String source = parser(DICT + SOURCE_A + "?version=10");

        assertEquals(parser(DICT + TARGET_A + "?version=3"), NestedReferences.repointBySourceId(source,
                Map.of(SOURCE_A, URI.create(DICT + TARGET_A + "?version=3"))));
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
