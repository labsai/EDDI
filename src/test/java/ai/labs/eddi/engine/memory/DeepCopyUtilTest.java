/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DeepCopyUtil Tests")
class DeepCopyUtilTest {

    @Nested
    @DisplayName("Null and Empty")
    class NullAndEmptyTests {

        @Test
        @DisplayName("Should return empty immutable map for null input")
        void testNullInput() {
            Map<String, Object> result = DeepCopyUtil.deepCopy(null);
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should return empty immutable map for empty input")
        void testEmptyInput() {
            Map<String, Object> result = DeepCopyUtil.deepCopy(Map.of());
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("Primitive Values")
    class PrimitiveTests {

        @Test
        @DisplayName("Should copy string values")
        void testStringValues() {
            Map<String, Object> original = new LinkedHashMap<>();
            original.put("name", "test");
            original.put("count", 42);
            original.put("active", true);

            Map<String, Object> copy = DeepCopyUtil.deepCopy(original);

            assertEquals("test", copy.get("name"));
            assertEquals(42, copy.get("count"));
            assertEquals(true, copy.get("active"));
        }

        @Test
        @DisplayName("Should handle null values in map")
        void testNullValues() {
            Map<String, Object> original = new LinkedHashMap<>();
            original.put("key", null);

            Map<String, Object> copy = DeepCopyUtil.deepCopy(original);
            assertTrue(copy.containsKey("key"));
            assertNull(copy.get("key"));
        }
    }

    @Nested
    @DisplayName("Nested Collections")
    class NestedCollectionTests {

        @Test
        @DisplayName("Should deep-copy nested maps")
        void testNestedMap() {
            Map<String, Object> inner = new LinkedHashMap<>();
            inner.put("nested", "value");

            Map<String, Object> original = new LinkedHashMap<>();
            original.put("child", inner);

            Map<String, Object> copy = DeepCopyUtil.deepCopy(original);

            // Modify original inner map
            inner.put("nested", "modified");

            // Copy should be unaffected
            @SuppressWarnings("unchecked")
            Map<String, Object> copiedChild = (Map<String, Object>) copy.get("child");
            assertEquals("value", copiedChild.get("nested"));
        }

        @Test
        @DisplayName("Should deep-copy nested lists")
        void testNestedList() {
            List<Object> innerList = new ArrayList<>();
            innerList.add("item1");
            innerList.add("item2");

            Map<String, Object> original = new LinkedHashMap<>();
            original.put("items", innerList);

            Map<String, Object> copy = DeepCopyUtil.deepCopy(original);

            // Modify original list
            innerList.add("item3");

            // Copy should be unaffected
            @SuppressWarnings("unchecked")
            List<Object> copiedList = (List<Object>) copy.get("items");
            assertEquals(2, copiedList.size());
        }

        @Test
        @DisplayName("Should deep-copy nested sets")
        void testNestedSet() {
            Set<Object> innerSet = new LinkedHashSet<>();
            innerSet.add("a");
            innerSet.add("b");

            Map<String, Object> original = new LinkedHashMap<>();
            original.put("tags", innerSet);

            Map<String, Object> copy = DeepCopyUtil.deepCopy(original);

            // Modify original
            innerSet.add("c");

            @SuppressWarnings("unchecked")
            Set<Object> copiedSet = (Set<Object>) copy.get("tags");
            assertEquals(2, copiedSet.size());
        }
    }

    @Nested
    @DisplayName("Immutability")
    class ImmutabilityTests {

        @Test
        @DisplayName("Should return an unmodifiable map")
        void testImmutable() {
            Map<String, Object> original = new LinkedHashMap<>();
            original.put("key", "value");

            Map<String, Object> copy = DeepCopyUtil.deepCopy(original);

            assertThrows(UnsupportedOperationException.class, () -> copy.put("new", "entry"));
        }
    }

    @Nested
    @DisplayName("Depth Guard")
    class DepthGuardTests {

        @Test
        @DisplayName("Should throw on nesting exceeding MAX_DEPTH")
        void testExceedsMaxDepth() {
            // Build a structure deeper than MAX_DEPTH
            Map<String, Object> nested = new LinkedHashMap<>();
            nested.put("leaf", "value");
            for (int i = 0; i < DeepCopyUtil.MAX_DEPTH + 5; i++) {
                Map<String, Object> wrapper = new LinkedHashMap<>();
                wrapper.put("child", nested);
                nested = wrapper;
            }

            Map<String, Object> tooDeep = nested;
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> DeepCopyUtil.deepCopy(tooDeep));
            assertTrue(ex.getMessage().contains("maximum nesting depth"));
        }

        @Test
        @DisplayName("Should succeed at exactly MAX_DEPTH nesting")
        void testAtMaxDepth() {
            // Build a structure at exactly MAX_DEPTH levels (deepCopy starts at 1)
            Map<String, Object> nested = new LinkedHashMap<>();
            nested.put("leaf", "value");
            for (int i = 0; i < DeepCopyUtil.MAX_DEPTH - 1; i++) {
                Map<String, Object> wrapper = new LinkedHashMap<>();
                wrapper.put("child", nested);
                nested = wrapper;
            }

            // Should not throw
            Map<String, Object> result = DeepCopyUtil.deepCopy(nested);
            assertNotNull(result);
        }
    }
}
