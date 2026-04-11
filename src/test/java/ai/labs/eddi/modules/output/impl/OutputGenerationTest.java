package ai.labs.eddi.modules.output.impl;

import ai.labs.eddi.modules.output.IOutputFilter;
import ai.labs.eddi.modules.output.model.OutputEntry;
import ai.labs.eddi.modules.output.model.OutputValue;
import ai.labs.eddi.modules.output.model.QuickReply;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link OutputGeneration} covering output lookup,
 * occurrence-based filtering, and entry deduplication. Zero CDI needed.
 */
class OutputGenerationTest {

    private OutputGeneration outputGeneration;

    @BeforeEach
    void setUp() {
        outputGeneration = new OutputGeneration("en");
    }

    @Nested
    @DisplayName("addOutputEntry")
    class AddEntry {

        @Test
        @DisplayName("should add entry for new action")
        void addNew() {
            var entry = createEntry("greet", 0);
            outputGeneration.addOutputEntry(entry);

            assertEquals(1, outputGeneration.getOutputMapper().size());
            assertTrue(outputGeneration.getOutputMapper().containsKey("greet"));
        }

        @Test
        @DisplayName("should not add duplicate entry")
        void noDuplicate() {
            var entry = createEntry("greet", 0);
            outputGeneration.addOutputEntry(entry);
            outputGeneration.addOutputEntry(entry);

            assertEquals(1, outputGeneration.getOutputMapper().get("greet").size());
        }

        @Test
        @DisplayName("should sort entries by occurrence")
        void sortByOccurrence() {
            outputGeneration.addOutputEntry(createEntry("greet", 2));
            outputGeneration.addOutputEntry(createEntry("greet", 0));
            outputGeneration.addOutputEntry(createEntry("greet", 1));

            var entries = outputGeneration.getOutputMapper().get("greet");
            assertEquals(0, entries.get(0).getOccurred());
            assertEquals(1, entries.get(1).getOccurred());
            assertEquals(2, entries.get(2).getOccurred());
        }
    }

    @Nested
    @DisplayName("getOutputs")
    class GetOutputs {

        @Test
        @DisplayName("should return outputs matching action filter")
        void actionMatch() {
            outputGeneration.addOutputEntry(createEntry("greet", 0));
            outputGeneration.addOutputEntry(createEntry("farewell", 0));

            var filters = List.<IOutputFilter>of(new OutputFilter("greet", 0));
            var outputs = outputGeneration.getOutputs(filters);

            assertEquals(1, outputs.size());
            assertTrue(outputs.containsKey("greet"));
        }

        @Test
        @DisplayName("should skip unknown action filters")
        void unknownAction() {
            outputGeneration.addOutputEntry(createEntry("greet", 0));

            var filters = List.<IOutputFilter>of(new OutputFilter("unknown", 0));
            var outputs = outputGeneration.getOutputs(filters);

            assertTrue(outputs.isEmpty());
        }

        @Test
        @DisplayName("should match exact occurrence count")
        void exactOccurrence() {
            outputGeneration.addOutputEntry(createEntry("greet", 0));
            outputGeneration.addOutputEntry(createEntryWithText("greet", 1, "second time"));

            var filters = List.<IOutputFilter>of(new OutputFilter("greet", 1));
            var outputs = outputGeneration.getOutputs(filters);

            assertEquals(1, outputs.size());
            var entries = outputs.get("greet");
            assertEquals(1, entries.size());
            assertEquals(1, entries.getFirst().getOccurred());
        }

        @Test
        @DisplayName("should fall back to highest occurrence when exact match missing")
        void fallbackToHighest() {
            outputGeneration.addOutputEntry(createEntry("greet", 0));
            outputGeneration.addOutputEntry(createEntryWithText("greet", 2, "highest"));

            // Ask for occurrence=5 — should fall back to highest (2)
            var filters = List.<IOutputFilter>of(new OutputFilter("greet", 5));
            var outputs = outputGeneration.getOutputs(filters);

            assertEquals(1, outputs.size());
            var entries = outputs.get("greet");
            assertEquals(2, entries.getFirst().getOccurred());
        }
    }

    @Nested
    @DisplayName("extractOutputEntryOfSameOccurrence")
    class ExtractByOccurrence {

        @Test
        @DisplayName("should return entries matching exact occurrence")
        void exactMatch() {
            var entries = new LinkedList<>(List.of(
                    createEntry("a", 0),
                    createEntryWithText("a", 1, "second"),
                    createEntry("a", 2)));

            var result = outputGeneration.extractOutputEntryOfSameOccurrence(entries, 1);
            assertEquals(1, result.size());
            assertEquals(1, result.getFirst().getOccurred());
        }

        @Test
        @DisplayName("should return highest occurrence when no exact match")
        void highestFallback() {
            var entries = new LinkedList<>(List.of(
                    createEntry("a", 0),
                    createEntry("a", 3)));

            var result = outputGeneration.extractOutputEntryOfSameOccurrence(entries, 5);
            assertEquals(1, result.size());
            assertEquals(3, result.getFirst().getOccurred());
        }
    }

    @Nested
    @DisplayName("language")
    class Language {

        @Test
        @DisplayName("should store language")
        void storesLanguage() {
            assertEquals("en", outputGeneration.getLanguage());
        }

        @Test
        @DisplayName("should accept null language")
        void nullLanguage() {
            var gen = new OutputGeneration(null);
            assertNull(gen.getLanguage());
        }
    }

    // ─── Helpers ─────────────────────────────────────────────

    private OutputEntry createEntry(String action, int occurred) {
        return createEntryWithText(action, occurred, "Hello!");
    }

    private OutputEntry createEntryWithText(String action, int occurred, String text) {
        var item = new TextOutputItem(text);
        var outputValue = new OutputValue(List.of(item));
        return new OutputEntry(action, occurred, List.of(outputValue), List.of());
    }
}
