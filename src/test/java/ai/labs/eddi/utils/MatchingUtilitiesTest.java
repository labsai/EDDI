package ai.labs.eddi.utils;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MatchingUtilitiesTest {

    @Test
    void executeValuePath_equalsMatch_returnsTrue() {
        Map<String, Object> values = Map.of("name", "John");
        assertTrue(MatchingUtilities.executeValuePath(values, "name", "John", null));
    }

    @Test
    void executeValuePath_equalsMismatch_returnsFalse() {
        Map<String, Object> values = Map.of("name", "John");
        assertFalse(MatchingUtilities.executeValuePath(values, "name", "Jane", null));
    }

    @Test
    void executeValuePath_containsInString_returnsTrue() {
        Map<String, Object> values = Map.of("greeting", "hello world");
        assertTrue(MatchingUtilities.executeValuePath(values, "greeting", null, "world"));
    }

    @Test
    void executeValuePath_containsInList_returnsTrue() {
        Map<String, Object> values = Map.of("tags", List.of("red", "blue", "green"));
        assertTrue(MatchingUtilities.executeValuePath(values, "tags", null, "blue"));
    }

    @Test
    void executeValuePath_containsNotInList_returnsFalse() {
        Map<String, Object> values = Map.of("tags", List.of("red", "blue"));
        assertFalse(MatchingUtilities.executeValuePath(values, "tags", null, "yellow"));
    }

    @Test
    void executeValuePath_booleanTrue_returnsTrue() {
        Map<String, Object> values = Map.of("active", true);
        assertTrue(MatchingUtilities.executeValuePath(values, "active", null, null));
    }

    @Test
    void executeValuePath_booleanFalse_returnsFalse() {
        Map<String, Object> values = Map.of("active", false);
        assertFalse(MatchingUtilities.executeValuePath(values, "active", null, null));
    }

    @Test
    void executeValuePath_existsCheck_noEqualsOrContains_returnsTrue() {
        Map<String, Object> values = Map.of("key", "anyValue");
        assertTrue(MatchingUtilities.executeValuePath(values, "key", null, null));
    }

    @Test
    void executeValuePath_missingKey_returnsFalse() {
        Map<String, Object> values = Map.of("other", "value");
        assertFalse(MatchingUtilities.executeValuePath(values, "missing", "something", null));
    }

    @Test
    void executeValuePath_emptyMap_returnsFalse() {
        assertFalse(MatchingUtilities.executeValuePath(new HashMap<>(), "key", "val", null));
    }
}
