/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.utils;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeUtilitiesTest {

    // --- checkNotNull ---

    @Test
    void checkNotNull_withNonNull_doesNotThrow() {
        assertDoesNotThrow(() -> RuntimeUtilities.checkNotNull("value", "param"));
    }

    @Test
    void checkNotNull_withNull_throwsIllegalArgument() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> RuntimeUtilities.checkNotNull(null, "myParam"));
        assertTrue(ex.getMessage().contains("myParam"));
    }

    // --- checkNotEmpty ---

    @Test
    void checkNotEmpty_withNonEmptyString_doesNotThrow() {
        assertDoesNotThrow(() -> RuntimeUtilities.checkNotEmpty("hello", "param"));
    }

    @Test
    void checkNotEmpty_withEmptyString_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeUtilities.checkNotEmpty("", "param"));
    }

    @Test
    void checkNotEmpty_withNull_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeUtilities.checkNotEmpty(null, "param"));
    }

    @Test
    void checkNotEmpty_withEmptyCollection_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeUtilities.checkNotEmpty(List.of(), "param"));
    }

    @Test
    void checkNotEmpty_withEmptyMap_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeUtilities.checkNotEmpty(Map.of(), "param"));
    }

    // --- checkCollectionNoNullElements ---

    @Test
    void checkCollectionNoNullElements_withValidCollection_doesNotThrow() {
        assertDoesNotThrow(() -> RuntimeUtilities.checkCollectionNoNullElements(List.of("a", "b"), "list"));
    }

    @Test
    void checkCollectionNoNullElements_withNullElement_throwsIllegalArgument() {
        List<String> list = new ArrayList<>();
        list.add("a");
        list.add(null);
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeUtilities.checkCollectionNoNullElements(list, "list"));
    }

    @Test
    void checkCollectionNoNullElements_withNullCollection_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeUtilities.checkCollectionNoNullElements(null, "list"));
    }

    // --- checkNotNegative ---

    @Test
    void checkNotNegative_withPositive_doesNotThrow() {
        assertDoesNotThrow(() -> RuntimeUtilities.checkNotNegative(5, "count"));
    }

    @Test
    void checkNotNegative_withZero_doesNotThrow() {
        assertDoesNotThrow(() -> RuntimeUtilities.checkNotNegative(0, "count"));
    }

    @Test
    void checkNotNegative_withNegative_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeUtilities.checkNotNegative(-1, "count"));
    }

    @Test
    void checkNotNegative_withNull_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeUtilities.checkNotNegative(null, "count"));
    }

    // --- isNullOrEmpty ---

    @Test
    void isNullOrEmpty_withNull_returnsTrue() {
        assertTrue(RuntimeUtilities.isNullOrEmpty(null));
    }

    @Test
    void isNullOrEmpty_withEmptyString_returnsTrue() {
        assertTrue(RuntimeUtilities.isNullOrEmpty(""));
    }

    @Test
    void isNullOrEmpty_withNonEmptyString_returnsFalse() {
        assertFalse(RuntimeUtilities.isNullOrEmpty("hello"));
    }

    @Test
    void isNullOrEmpty_withEmptyCollection_returnsTrue() {
        assertTrue(RuntimeUtilities.isNullOrEmpty(new ArrayList<>()));
    }

    @Test
    void isNullOrEmpty_withNonEmptyCollection_returnsFalse() {
        assertFalse(RuntimeUtilities.isNullOrEmpty(List.of("a")));
    }

    @Test
    void isNullOrEmpty_withEmptyMap_returnsTrue() {
        assertTrue(RuntimeUtilities.isNullOrEmpty(new HashMap<>()));
    }

    @Test
    void isNullOrEmpty_withNonEmptyMap_returnsFalse() {
        assertFalse(RuntimeUtilities.isNullOrEmpty(Map.of("k", "v")));
    }

    @Test
    void isNullOrEmpty_withNonStringNonCollectionObject_returnsFalse() {
        // An arbitrary object that is not String, Collection, or Map
        assertFalse(RuntimeUtilities.isNullOrEmpty(42));
    }
}
