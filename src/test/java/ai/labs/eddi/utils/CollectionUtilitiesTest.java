/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.utils;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CollectionUtilitiesTest {

    @Test
    void addAllWithoutDuplicates_addsNewItems() {
        List<String> collection = new ArrayList<>(List.of("a", "b"));
        CollectionUtilities.addAllWithoutDuplicates(collection, List.of("c", "d"));
        assertEquals(List.of("a", "b", "c", "d"), collection);
    }

    @Test
    void addAllWithoutDuplicates_skipsDuplicates() {
        List<String> collection = new ArrayList<>(List.of("a", "b"));
        CollectionUtilities.addAllWithoutDuplicates(collection, List.of("b", "c"));
        assertEquals(List.of("a", "b", "c"), collection);
    }

    @Test
    void addAllWithoutDuplicates_allDuplicates_noChanges() {
        List<String> collection = new ArrayList<>(List.of("a", "b"));
        CollectionUtilities.addAllWithoutDuplicates(collection, List.of("a", "b"));
        assertEquals(List.of("a", "b"), collection);
    }

    @Test
    void addAllWithoutDuplicates_emptyAddTo_noChanges() {
        List<String> collection = new ArrayList<>(List.of("a"));
        CollectionUtilities.addAllWithoutDuplicates(collection, List.of());
        assertEquals(List.of("a"), collection);
    }

    /**
     * The parameters were named {@code (collection, addTo)} while the body added
     * INTO the first from the second. Renaming them to (target, source) documents
     * what actually happens; this pins the direction so a future "fix" to the body
     * cannot quietly reverse it.
     */
    @Test
    void addAllWithoutDuplicates_addsIntoTheFirstArgumentAndLeavesTheSecondAlone() {
        List<String> target = new ArrayList<>(List.of("a"));
        List<String> source = List.of("b");

        CollectionUtilities.addAllWithoutDuplicates(target, source);

        assertEquals(List.of("a", "b"), target);
        assertEquals(List.of("b"), source, "the source must not be mutated");
    }
}
