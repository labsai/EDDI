/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.utils;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MatchingUtilitiesTest {

    // ── Null / missing value paths ─────────────────────────────────

    @Nested
    class NullAndMissingValues {

        @Test
        void nullValue_returnsFalse() {
            Map<String, Object> values = new HashMap<>();
            values.put("key", null);
            assertFalse(MatchingUtilities.executeValuePath(values, "key", null, null));
        }

        @Test
        void emptyMap_returnsFalse() {
            assertFalse(MatchingUtilities.executeValuePath(
                    new HashMap<>(), "key", "val", null));
        }

        @Test
        void nullValuePath_returnsFalse() {
            assertFalse(MatchingUtilities.executeValuePath(
                    Map.of("key", "value"), null, null, null));
        }

        @Test
        void emptyValuePath_returnsFalse() {
            assertFalse(MatchingUtilities.executeValuePath(
                    Map.of("key", "value"), "", null, null));
        }

        @Test
        void invalidPath_returnsFalse() {
            // Unresolvable nested paths return null, so executeValuePath should return
            // false
            assertFalse(MatchingUtilities.executeValuePath(
                    Map.of("a", "b"), "a.b.c.d.e", "something", null));
        }
    }

    // ── Equals checks ──────────────────────────────────────────────

    @Nested
    class EqualsChecks {

        @Test
        void equalsMatch_returnsTrue() {
            Map<String, Object> values = Map.of("name", "John");
            assertTrue(MatchingUtilities.executeValuePath(values, "name", "John", null));
        }

        @Test
        void equalsMismatch_returnsFalse() {
            Map<String, Object> values = Map.of("name", "John");
            assertFalse(MatchingUtilities.executeValuePath(values, "name", "Jane", null));
        }

        @Test
        void equalsWithEmptyString_equalsIsEmpty_treatedAsNoEquals() {
            // isNullOrEmpty("") returns true, so empty equals = existence check
            Map<String, Object> values = Map.of("name", "John");
            assertTrue(MatchingUtilities.executeValuePath(values, "name", "", null));
        }

        @Test
        void equalsOnIntegerValue_usesToString() {
            Map<String, Object> values = Map.of("count", 42);
            assertTrue(MatchingUtilities.executeValuePath(values, "count", "42", null));
        }

        @Test
        void equalsOnIntegerValue_mismatch_returnsFalse() {
            Map<String, Object> values = Map.of("count", 42);
            assertFalse(MatchingUtilities.executeValuePath(values, "count", "99", null));
        }

        @Test
        void equalsOnDoubleValue_usesToString() {
            Map<String, Object> values = Map.of("price", 19.99);
            assertTrue(MatchingUtilities.executeValuePath(values, "price", "19.99", null));
        }
    }

    // ── Contains checks ────────────────────────────────────────────

    @Nested
    class ContainsChecks {

        @Test
        void containsInString_returnsTrue() {
            Map<String, Object> values = Map.of("greeting", "hello world");
            assertTrue(MatchingUtilities.executeValuePath(values, "greeting", null, "world"));
        }

        @Test
        void containsNotInString_returnsFalse() {
            Map<String, Object> values = Map.of("greeting", "hello world");
            assertFalse(MatchingUtilities.executeValuePath(values, "greeting", null, "xyz"));
        }

        @Test
        void containsInList_returnsTrue() {
            Map<String, Object> values = Map.of("tags", List.of("red", "blue", "green"));
            assertTrue(MatchingUtilities.executeValuePath(values, "tags", null, "blue"));
        }

        @Test
        void containsNotInList_returnsFalse() {
            Map<String, Object> values = Map.of("tags", List.of("red", "blue"));
            assertFalse(MatchingUtilities.executeValuePath(values, "tags", null, "yellow"));
        }

        @Test
        void containsOnNonStringNonList_returnsFalse() {
            // Integer is neither String nor List — contains can't match
            Map<String, Object> values = Map.of("count", 42);
            assertFalse(MatchingUtilities.executeValuePath(values, "count", null, "42"));
        }

        @Test
        void containsOnBoolean_returnsFalse() {
            // Boolean is neither String nor List — contains can't match
            Map<String, Object> values = Map.of("active", true);
            assertFalse(MatchingUtilities.executeValuePath(values, "active", null, "true"));
        }

        @Test
        void containsWithEmptyString_treatedAsNoContains() {
            // isNullOrEmpty("") returns true, so empty contains = existence check
            Map<String, Object> values = Map.of("key", "anyValue");
            assertTrue(MatchingUtilities.executeValuePath(values, "key", null, ""));
        }
    }

    // ── Boolean value handling ──────────────────────────────────────

    @Nested
    class BooleanValues {

        @Test
        void booleanTrue_existenceCheck_returnsTrue() {
            Map<String, Object> values = Map.of("active", true);
            assertTrue(MatchingUtilities.executeValuePath(values, "active", null, null));
        }

        @Test
        void booleanFalse_existenceCheck_returnsFalse() {
            Map<String, Object> values = Map.of("active", false);
            assertFalse(MatchingUtilities.executeValuePath(values, "active", null, null));
        }

        @Test
        void booleanTrue_equalsTrue_returnsTrue() {
            // Normal type-conversion path via toString()
            Map<String, Object> values = Map.of("active", true);
            assertTrue(MatchingUtilities.executeValuePath(values, "active", "true", null));
        }

        @Test
        void booleanFalse_equalsFalse_returnsTrue() {
            // Normal type-conversion path via toString()
            Map<String, Object> values = Map.of("active", false);
            assertTrue(MatchingUtilities.executeValuePath(values, "active", "false", null));
        }

        @Test
        void booleanTrue_equalsFalse_returnsFalse() {
            // THE BUG FIX: equals says "false" but value is true — should fail
            Map<String, Object> values = Map.of("active", true);
            assertFalse(MatchingUtilities.executeValuePath(values, "active", "false", null));
        }

        @Test
        void booleanFalse_equalsTrue_returnsFalse() {
            // equals says "true" but value is false — should fail
            Map<String, Object> values = Map.of("active", false);
            assertFalse(MatchingUtilities.executeValuePath(values, "active", "true", null));
        }

        @Test
        void booleanTrue_equalsArbitraryString_returnsFalse() {
            // THE BUG FIX: equals says "yes" but value is Boolean(true)
            // Previously returned true (Boolean branch override), now correctly returns
            // false
            Map<String, Object> values = Map.of("active", true);
            assertFalse(MatchingUtilities.executeValuePath(values, "active", "yes", null));
        }

        @Test
        void booleanFalse_equalsArbitraryString_returnsFalse() {
            Map<String, Object> values = Map.of("active", false);
            assertFalse(MatchingUtilities.executeValuePath(values, "active", "no", null));
        }
    }

    // ── Existence checks (no equals, no contains) ──────────────────

    @Nested
    class ExistenceChecks {

        @Test
        void existsCheck_stringValue_returnsTrue() {
            Map<String, Object> values = Map.of("key", "anyValue");
            assertTrue(MatchingUtilities.executeValuePath(values, "key", null, null));
        }

        @Test
        void existsCheck_integerValue_returnsTrue() {
            Map<String, Object> values = Map.of("count", 42);
            assertTrue(MatchingUtilities.executeValuePath(values, "count", null, null));
        }

        @Test
        void existsCheck_listValue_returnsTrue() {
            Map<String, Object> values = Map.of("items", List.of("a", "b"));
            assertTrue(MatchingUtilities.executeValuePath(values, "items", null, null));
        }

        @Test
        void existsCheck_mapValue_returnsTrue() {
            Map<String, Object> values = Map.of("nested", Map.of("a", 1));
            assertTrue(MatchingUtilities.executeValuePath(values, "nested", null, null));
        }

        @Test
        void existsCheck_missingKey_returnsFalse() {
            Map<String, Object> values = Map.of("other", "value");
            assertFalse(MatchingUtilities.executeValuePath(values, "missing", null, null));
        }
    }

    // ── Equals + Contains combined ─────────────────────────────────

    @Nested
    class EqualsAndContainsCombined {

        @Test
        void equalsMatchesTakesPriority_containsIgnored() {
            // When equals matches, contains is not evaluated
            Map<String, Object> values = Map.of("name", "John");
            assertTrue(MatchingUtilities.executeValuePath(values, "name", "John", "xyz"));
        }

        @Test
        void equalsMismatch_containsFallbackMatches_returnsTrue() {
            // equals doesn't match but contains does — OR semantics
            Map<String, Object> values = Map.of("name", "John");
            assertTrue(MatchingUtilities.executeValuePath(values, "name", "Jane", "Joh"));
        }

        @Test
        void equalsMismatch_containsFallbackAlsoFails_returnsFalse() {
            Map<String, Object> values = Map.of("name", "John");
            assertFalse(MatchingUtilities.executeValuePath(values, "name", "Jane", "xyz"));
        }
    }

    // ── Nested path navigation ─────────────────────────────────────

    @Nested
    class NestedPaths {

        @Test
        void nestedMapPath_equalsMatch() {
            Map<String, Object> values = Map.of(
                    "memory", Map.of("current", Map.of("input", "hello")));
            assertTrue(MatchingUtilities.executeValuePath(
                    values, "memory.current.input", "hello", null));
        }

        @Test
        void arrayIndexPath_equalsMatch() {
            Map<String, Object> values = Map.of(
                    "items", List.of(Map.of("city", "Vienna"), Map.of("city", "Berlin")));
            assertTrue(MatchingUtilities.executeValuePath(
                    values, "items[0].city", "Vienna", null));
        }

        @Test
        void deepNestedMissing_returnsFalse() {
            Map<String, Object> values = Map.of("a", Map.of("b", "c"));
            assertFalse(MatchingUtilities.executeValuePath(
                    values, "a.b.c.d", null, null));
        }
    }
}
