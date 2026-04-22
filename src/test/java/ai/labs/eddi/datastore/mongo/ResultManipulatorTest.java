package ai.labs.eddi.datastore.mongo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ResultManipulator}.
 *
 * @since 6.0.0
 */
@DisplayName("ResultManipulator")
class ResultManipulatorTest {

    // ─── Simple test POJO ─────────────────────────────────
    @SuppressWarnings("unused")
    public static class Item {
        private final String name;
        private final String description;
        private final int priority;

        public Item(String name, String description, int priority) {
            this.name = name;
            this.description = description;
            this.priority = priority;
        }

        public String getName() {
            return name;
        }
        public String getDescription() {
            return description;
        }
        public int getPriority() {
            return priority;
        }
    }

    // ─── Constructor ──────────────────────────────────────

    @Test
    @DisplayName("constructor — rejects null list")
    void constructor_nullList_throws() {
        assertThrows(IllegalArgumentException.class, () -> new ResultManipulator<>(null, Item.class));
    }

    @Test
    @DisplayName("constructor — rejects null class")
    void constructor_nullClass_throws() {
        assertThrows(IllegalArgumentException.class, () -> new ResultManipulator<>(new ArrayList<>(), null));
    }

    @Test
    @DisplayName("getManipulatedList — returns the same list reference")
    void getManipulatedList_sameReference() {
        var list = new ArrayList<>(List.of(new Item("a", "b", 1)));
        var manipulator = new ResultManipulator<>(list, Item.class);
        assertSame(list, manipulator.getManipulatedList());
    }

    // ─── filterEntities ───────────────────────────────────

    @Nested
    @DisplayName("filterEntities")
    class FilterTests {

        @Test
        @DisplayName("partial match — keeps items containing search string")
        void partialMatch_keepsMatching() throws ResultManipulator.FilterEntriesException {
            var list = new ArrayList<>(List.of(
                    new Item("chatbot", "An AI assistant", 1),
                    new Item("weather", "Weather service", 2),
                    new Item("chat-support", "Support chat", 3)));
            var manipulator = new ResultManipulator<>(list, Item.class);

            manipulator.filterEntities("chat");

            assertEquals(2, manipulator.getManipulatedList().size());
            assertTrue(manipulator.getManipulatedList().stream()
                    .allMatch(i -> i.getName().contains("chat") || i.getDescription().contains("chat")));
        }

        @Test
        @DisplayName("exact match — keeps only exact string matches")
        void exactMatch_keepsExact() throws ResultManipulator.FilterEntriesException {
            var list = new ArrayList<>(List.of(
                    new Item("chatbot", "assistant", 1),
                    new Item("chat", "support", 2),
                    new Item("mychat", "other", 3)));
            var manipulator = new ResultManipulator<>(list, Item.class);

            manipulator.filterEntities("\"chat\"");

            // Only the item with name exactly "chat" should remain
            assertEquals(1, manipulator.getManipulatedList().size());
            assertEquals("chat", manipulator.getManipulatedList().getFirst().getName());
        }

        @Test
        @DisplayName("exact match with empty quoted string — filters out items whose getters return non-empty values")
        void exactMatch_emptyQuotedString() throws ResultManipulator.FilterEntriesException {
            var list = new ArrayList<>(List.of(
                    new Item("hello", "world", 1)));
            var manipulator = new ResultManipulator<>(list, Item.class);

            // "" → searchString is empty string; only items with a getter returning ""
            // would match
            manipulator.filterEntities("\"\"");

            // "hello" and "world" don't equal "" → item is filtered out
            assertTrue(manipulator.getManipulatedList().isEmpty());
        }

        @Test
        @DisplayName("no match — removes all items")
        void noMatch_removesAll() throws ResultManipulator.FilterEntriesException {
            var list = new ArrayList<>(List.of(
                    new Item("alpha", "first", 1),
                    new Item("beta", "second", 2)));
            var manipulator = new ResultManipulator<>(list, Item.class);

            manipulator.filterEntities("zzz_nonexistent");

            assertTrue(manipulator.getManipulatedList().isEmpty());
        }

        @Test
        @DisplayName("null item in list — throws FilterEntriesException")
        void nullItem_throwsException() {
            var list = new ArrayList<Item>();
            list.add(null);
            var manipulator = new ResultManipulator<>(list, Item.class);

            var ex = assertThrows(ResultManipulator.FilterEntriesException.class,
                    () -> manipulator.filterEntities("test"));
            assertTrue(ex.getMessage().contains("Null values are not allowed"));
        }

        @Test
        @DisplayName("null filter — throws IllegalArgumentException")
        void nullFilter_throws() {
            var list = new ArrayList<>(List.of(new Item("x", "y", 1)));
            var manipulator = new ResultManipulator<>(list, Item.class);

            assertThrows(IllegalArgumentException.class,
                    () -> manipulator.filterEntities(null));
        }

        @Test
        @DisplayName("empty list — no-op")
        void emptyList_noop() throws ResultManipulator.FilterEntriesException {
            var list = new ArrayList<Item>();
            var manipulator = new ResultManipulator<>(list, Item.class);

            manipulator.filterEntities("anything");

            assertTrue(manipulator.getManipulatedList().isEmpty());
        }

        @Test
        @DisplayName("match found in description (non-name getter)")
        void matchInDescription() throws ResultManipulator.FilterEntriesException {
            var list = new ArrayList<>(List.of(
                    new Item("alpha", "machine learning tool", 1),
                    new Item("beta", "web scraper", 2)));
            var manipulator = new ResultManipulator<>(list, Item.class);

            manipulator.filterEntities("machine");

            assertEquals(1, manipulator.getManipulatedList().size());
            assertEquals("alpha", manipulator.getManipulatedList().getFirst().getName());
        }

        @Test
        @DisplayName("numeric getter value matches filter string")
        void numericGetterMatch() throws ResultManipulator.FilterEntriesException {
            var list = new ArrayList<>(List.of(
                    new Item("a", "x", 42),
                    new Item("b", "y", 7)));
            var manipulator = new ResultManipulator<>(list, Item.class);

            manipulator.filterEntities("42");

            assertEquals(1, manipulator.getManipulatedList().size());
            assertEquals("a", manipulator.getManipulatedList().getFirst().getName());
        }
    }

    // ─── sortEntities ─────────────────────────────────────

    @Nested
    @DisplayName("sortEntities")
    class SortTests {

        @Test
        @DisplayName("ascending sort")
        void ascending() {
            var list = new ArrayList<>(List.of(
                    new Item("charlie", "c", 3),
                    new Item("alpha", "a", 1),
                    new Item("bravo", "b", 2)));
            var manipulator = new ResultManipulator<>(list, Item.class);
            Comparator<Item> byName = Comparator.comparing(Item::getName);

            manipulator.sortEntities(byName, ResultManipulator.ASCENDING);

            assertEquals("alpha", manipulator.getManipulatedList().get(0).getName());
            assertEquals("bravo", manipulator.getManipulatedList().get(1).getName());
            assertEquals("charlie", manipulator.getManipulatedList().get(2).getName());
        }

        @Test
        @DisplayName("descending sort")
        void descending() {
            var list = new ArrayList<>(List.of(
                    new Item("alpha", "a", 1),
                    new Item("charlie", "c", 3),
                    new Item("bravo", "b", 2)));
            var manipulator = new ResultManipulator<>(list, Item.class);
            Comparator<Item> byName = Comparator.comparing(Item::getName);

            manipulator.sortEntities(byName, ResultManipulator.DESCENDING);

            assertEquals("charlie", manipulator.getManipulatedList().get(0).getName());
            assertEquals("bravo", manipulator.getManipulatedList().get(1).getName());
            assertEquals("alpha", manipulator.getManipulatedList().get(2).getName());
        }

        @Test
        @DisplayName("invalid order — throws IllegalArgumentException")
        void invalidOrder_throws() {
            var list = new ArrayList<>(List.of(new Item("a", "b", 1)));
            var manipulator = new ResultManipulator<>(list, Item.class);
            Comparator<Item> byName = Comparator.comparing(Item::getName);

            assertThrows(IllegalArgumentException.class,
                    () -> manipulator.sortEntities(byName, "invalid"));
        }

        @Test
        @DisplayName("empty order string — no-op")
        void emptyOrder_noop() {
            var list = new ArrayList<>(List.of(
                    new Item("bravo", "b", 2),
                    new Item("alpha", "a", 1)));
            var manipulator = new ResultManipulator<>(list, Item.class);
            Comparator<Item> byName = Comparator.comparing(Item::getName);

            manipulator.sortEntities(byName, "");

            // Order unchanged
            assertEquals("bravo", manipulator.getManipulatedList().get(0).getName());
        }

        @Test
        @DisplayName("empty list — no-op")
        void emptyList_noop() {
            var list = new ArrayList<Item>();
            var manipulator = new ResultManipulator<>(list, Item.class);
            Comparator<Item> byName = Comparator.comparing(Item::getName);

            assertDoesNotThrow(() -> manipulator.sortEntities(byName, ResultManipulator.ASCENDING));
            assertTrue(manipulator.getManipulatedList().isEmpty());
        }

        @Test
        @DisplayName("null comparator — throws")
        void nullComparator_throws() {
            var list = new ArrayList<>(List.of(new Item("a", "b", 1)));
            var manipulator = new ResultManipulator<>(list, Item.class);

            assertThrows(IllegalArgumentException.class,
                    () -> manipulator.sortEntities(null, ResultManipulator.ASCENDING));
        }

        @Test
        @DisplayName("null order — throws")
        void nullOrder_throws() {
            var list = new ArrayList<>(List.of(new Item("a", "b", 1)));
            var manipulator = new ResultManipulator<>(list, Item.class);
            Comparator<Item> byName = Comparator.comparing(Item::getName);

            assertThrows(IllegalArgumentException.class,
                    () -> manipulator.sortEntities(byName, null));
        }
    }

    // ─── limitEntities ────────────────────────────────────

    @Nested
    @DisplayName("limitEntities")
    class LimitTests {

        @Test
        @DisplayName("limit=2, index=0 — returns first 2")
        void firstPage() {
            var list = new ArrayList<>(List.of(
                    new Item("a", "1", 1),
                    new Item("b", "2", 2),
                    new Item("c", "3", 3),
                    new Item("d", "4", 4),
                    new Item("e", "5", 5)));
            var manipulator = new ResultManipulator<>(list, Item.class);

            manipulator.limitEntities(0, 2);

            assertEquals(2, manipulator.getManipulatedList().size());
            assertEquals("a", manipulator.getManipulatedList().get(0).getName());
            assertEquals("b", manipulator.getManipulatedList().get(1).getName());
        }

        @Test
        @DisplayName("limit=2, index=1 — returns second page")
        void secondPage() {
            var list = new ArrayList<>(List.of(
                    new Item("a", "1", 1),
                    new Item("b", "2", 2),
                    new Item("c", "3", 3),
                    new Item("d", "4", 4),
                    new Item("e", "5", 5)));
            var manipulator = new ResultManipulator<>(list, Item.class);

            manipulator.limitEntities(1, 2);

            assertEquals(2, manipulator.getManipulatedList().size());
            assertEquals("c", manipulator.getManipulatedList().get(0).getName());
            assertEquals("d", manipulator.getManipulatedList().get(1).getName());
        }

        @Test
        @DisplayName("limit=0 — returns all items (no limit)")
        void zeroLimit_returnsAll() {
            var list = new ArrayList<>(List.of(
                    new Item("a", "1", 1),
                    new Item("b", "2", 2),
                    new Item("c", "3", 3)));
            var manipulator = new ResultManipulator<>(list, Item.class);

            manipulator.limitEntities(0, 0);

            assertEquals(3, manipulator.getManipulatedList().size());
        }

        @Test
        @DisplayName("index beyond list size — returns empty")
        void indexBeyondSize() {
            var list = new ArrayList<>(List.of(
                    new Item("a", "1", 1),
                    new Item("b", "2", 2)));
            var manipulator = new ResultManipulator<>(list, Item.class);

            manipulator.limitEntities(5, 2);

            assertTrue(manipulator.getManipulatedList().isEmpty());
        }

        @Test
        @DisplayName("partial last page")
        void partialLastPage() {
            var list = new ArrayList<>(List.of(
                    new Item("a", "1", 1),
                    new Item("b", "2", 2),
                    new Item("c", "3", 3)));
            var manipulator = new ResultManipulator<>(list, Item.class);

            manipulator.limitEntities(1, 2);

            assertEquals(1, manipulator.getManipulatedList().size());
            assertEquals("c", manipulator.getManipulatedList().getFirst().getName());
        }

        @Test
        @DisplayName("negative index — throws")
        void negativeIndex_throws() {
            var list = new ArrayList<>(List.of(new Item("a", "1", 1)));
            var manipulator = new ResultManipulator<>(list, Item.class);

            assertThrows(IllegalArgumentException.class,
                    () -> manipulator.limitEntities(-1, 1));
        }

        @Test
        @DisplayName("negative limit — throws")
        void negativeLimit_throws() {
            var list = new ArrayList<>(List.of(new Item("a", "1", 1)));
            var manipulator = new ResultManipulator<>(list, Item.class);

            assertThrows(IllegalArgumentException.class,
                    () -> manipulator.limitEntities(0, -1));
        }
    }
}
