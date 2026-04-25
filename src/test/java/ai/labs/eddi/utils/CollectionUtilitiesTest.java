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
}
