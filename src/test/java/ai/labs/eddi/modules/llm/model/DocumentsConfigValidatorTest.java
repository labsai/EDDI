/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.model;

import ai.labs.eddi.engine.documents.DocumentBounds;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DocumentsConfigValidator")
class DocumentsConfigValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PATH = "langchain.task[0].documents";

    private static DocumentsConfig parse(String json) throws Exception {
        return MAPPER.readValue(json, DocumentsConfig.class);
    }

    private static Set<String> allKeysLive() {
        Set<String> live = new HashSet<>(DocumentsDesignJson.LEAF_KEYS);
        DocumentsDesignJson.NULL_IN_DESIGN.forEach(k -> live.add(k.get(0)));
        live.add("bridge");
        return live;
    }

    /** Each leaf key of the design JSON with its design value, as raw JSON. */
    static Stream<List<String>> designLeaves() throws Exception {
        var tree = MAPPER.readTree(DocumentsDesignJson.FULL);
        Stream<List<String>> withValues = DocumentsDesignJson.LEAF_KEYS.stream().map(path -> {
            var node = tree;
            for (String part : path.split("\\.")) {
                node = node.get(part);
            }
            return List.of(path, node.toString());
        });
        return Stream.concat(Stream.concat(withValues, DocumentsDesignJson.NULL_IN_DESIGN.stream()), Stream.of(List.of("bridge", "{}")));
    }

    @Test
    @DisplayName("the class carries every key of the design JSON — and no more")
    void classCoversTheDesignShape() throws Exception {
        var keys = DocumentsConfigValidator.setKeys(parse(DocumentsDesignJson.FULL)).keySet();
        assertEquals(Set.copyOf(DocumentsDesignJson.LEAF_KEYS), keys);

        var bridge = DocumentsConfigValidator.setKeys(parse(DocumentsDesignJson.BRIDGE_SET)).keySet();
        assertEquals(Set.of("bridge"), bridge);
        for (var nullKey : DocumentsDesignJson.NULL_IN_DESIGN) {
            var one = DocumentsConfigValidator.setKeys(parse(DocumentsDesignJson.withOnly(nullKey.get(0), nullKey.get(1)))).keySet();
            assertEquals(Set.of(nullKey.get(0)), one, nullKey.get(0));
        }
    }

    @Test
    @DisplayName("nothing is live in this version")
    void nothingIsLiveYet() {
        assertTrue(DocumentsConfigValidator.LIVE_KEYS.isEmpty(), DocumentsConfigValidator.LIVE_KEYS.toString());
    }

    @Nested
    @DisplayName("not available in this version")
    class NotLive {

        @ParameterizedTest(name = "{0}")
        @MethodSource("ai.labs.eddi.modules.llm.model.DocumentsConfigValidatorTest#designLeaves")
        @DisplayName("every leaf key is refused when set, even to its default")
        void everyLeafIsRefused(List<String> leaf) throws Exception {
            var config = parse(DocumentsDesignJson.withOnly(leaf.get(0), leaf.get(1)));
            var e = assertThrows(IllegalArgumentException.class, () -> DocumentsConfigValidator.validate(config, PATH));
            assertEquals(PATH + "." + leaf.get(0) + " is not available in this version", e.getMessage());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("ai.labs.eddi.modules.llm.model.DocumentsConfigValidatorTest#designLeaves")
        @DisplayName("every leaf key is accepted once live")
        void everyLeafIsAcceptedOnceLive(List<String> leaf) throws Exception {
            var config = parse(DocumentsDesignJson.withOnly(leaf.get(0), leaf.get(1)));
            assertDoesNotThrow(() -> DocumentsConfigValidator.validate(config, PATH, Set.of(leaf.get(0)), DocumentBounds.none()));
        }

        @ParameterizedTest
        @ValueSource(strings = {"{}", "{\"split\":{}}", "{\"large\":{\"summary\":{}}}", "{\"index\":{\"rag\":null}}",
                "{\"bridge\":null}"})
        @DisplayName("an absent block, an empty block and explicit nulls set nothing and are accepted")
        void emptyIsAccepted(String json) throws Exception {
            var config = parse(json);
            assertDoesNotThrow(() -> DocumentsConfigValidator.validate(config, PATH));
            assertDoesNotThrow(() -> DocumentsConfigValidator.validate(null, PATH));
        }

        @Test
        @DisplayName("the whole design JSON is accepted when every key is live — its defaults are known names")
        void wholeDesignAcceptedWhenLive() throws Exception {
            var config = parse(DocumentsDesignJson.FULL);
            assertDoesNotThrow(() -> DocumentsConfigValidator.validate(config, PATH, allKeysLive(), DocumentBounds.none()));
        }

        @Test
        @DisplayName("a value-level entry makes only that value available")
        void valueLevelLiveness() throws Exception {
            Set<String> live = Set.of("structure=ON_DEMAND");
            assertDoesNotThrow(() -> DocumentsConfigValidator.validate(parse("{\"structure\":\"ON_DEMAND\"}"), PATH, live,
                    DocumentBounds.none()));
            var e = assertThrows(IllegalArgumentException.class,
                    () -> DocumentsConfigValidator.validate(parse("{\"structure\":\"AUTO_EXPAND\"}"), PATH, live, DocumentBounds.none()));
            assertTrue(e.getMessage().endsWith("structure is not available in this version"), e.getMessage());
        }

        @Test
        @DisplayName("a list is available only if each element is")
        void listValueLevelLiveness() throws Exception {
            Set<String> live = Set.of("library.scopes=self", "library.scopes=global");
            assertDoesNotThrow(() -> DocumentsConfigValidator.validate(parse("{\"library\":{\"scopes\":[\"self\",\"global\"]}}"), PATH,
                    live, DocumentBounds.none()));
            assertThrows(IllegalArgumentException.class, () -> DocumentsConfigValidator
                    .validate(parse("{\"library\":{\"scopes\":[\"self\",\"team\"]}}"), PATH, live, DocumentBounds.none()));
        }
    }

    @Nested
    @DisplayName("values")
    class Values {

        @Test
        @DisplayName("an unknown enum name is refused, naming the known ones")
        void unknownEnum() throws Exception {
            var e = assertThrows(IllegalArgumentException.class, () -> DocumentsConfigValidator
                    .validate(parse("{\"split\":{\"strategy\":\"CHAPTERS\"}}"), PATH, Set.of("split.strategy"), DocumentBounds.none()));
            assertEquals(PATH + ".split.strategy has unknown value 'CHAPTERS' (known: AUTO, HEADINGS, OUTLINE, PAGES)", e.getMessage());
        }

        @Test
        @DisplayName("an unknown list element is refused")
        void unknownListElement() throws Exception {
            var e = assertThrows(IllegalArgumentException.class, () -> DocumentsConfigValidator
                    .validate(parse("{\"library\":{\"scopes\":[\"self\",\"world\"]}}"), PATH, Set.of("library.scopes"), DocumentBounds.none()));
            assertTrue(e.getMessage().contains("unknown value 'world'"), e.getMessage());
        }

        @Test
        @DisplayName("onReady CONTINUE is not a value in this design revision")
        void continueIsNotAValue() throws Exception {
            assertThrows(IllegalArgumentException.class, () -> DocumentsConfigValidator
                    .validate(parse("{\"large\":{\"onReady\":\"CONTINUE\"}}"), PATH, Set.of("large.onReady"), DocumentBounds.none()));
        }

        @Test
        @DisplayName("a value above its operator bound is refused naming field, value and bound; at the bound it is accepted")
        void aboveBound() throws Exception {
            var bounds = DocumentBounds.of(Map.of("sharing.maxDepth", 3));
            Set<String> live = Set.of("sharing.maxDepth");
            var e = assertThrows(IllegalArgumentException.class,
                    () -> DocumentsConfigValidator.validate(parse("{\"sharing\":{\"maxDepth\":4}}"), PATH, live, bounds));
            assertTrue(e.getMessage().startsWith(PATH + ".sharing.maxDepth is 4, above the operator bound of 3"), e.getMessage());
            assertDoesNotThrow(() -> DocumentsConfigValidator.validate(parse("{\"sharing\":{\"maxDepth\":3}}"), PATH, live, bounds));
        }

        @Test
        @DisplayName("a dollar bound is compared as a number")
        void dollarBound() throws Exception {
            var bounds = DocumentBounds.of(Map.of("large.summary.maxCostPerDocument", 5.0));
            Set<String> live = Set.of("large.summary.maxCostPerDocument");
            assertThrows(IllegalArgumentException.class, () -> DocumentsConfigValidator
                    .validate(parse("{\"large\":{\"summary\":{\"maxCostPerDocument\":5.01}}}"), PATH, live, bounds));
            assertDoesNotThrow(() -> DocumentsConfigValidator.validate(parse("{\"large\":{\"summary\":{\"maxCostPerDocument\":5.0}}}"),
                    PATH, live, bounds));
        }

        @Test
        @DisplayName("a negative number is refused")
        void negative() throws Exception {
            var e = assertThrows(IllegalArgumentException.class, () -> DocumentsConfigValidator
                    .validate(parse("{\"split\":{\"maxUnits\":-1}}"), PATH, Set.of("split.maxUnits"), DocumentBounds.none()));
            assertTrue(e.getMessage().contains("must not be negative"), e.getMessage());
        }

        @Test
        @DisplayName("the operator bound does not apply to a key that is not live — 'not available' wins")
        void notLiveBeforeBound() throws Exception {
            var bounds = DocumentBounds.of(Map.of("sharing.maxDepth", 3));
            var e = assertThrows(IllegalArgumentException.class,
                    () -> DocumentsConfigValidator.validate(parse("{\"sharing\":{\"maxDepth\":99}}"), PATH, Set.of(), bounds));
            assertTrue(e.getMessage().endsWith("is not available in this version"), e.getMessage());
        }
    }
}
