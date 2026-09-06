/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.migration;

import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The edges of the shared v5 → v6 transforms in
 * {@link LegacyDocumentMigrations} that neither the MongoDB sweep tests nor the
 * Postgres parity tests reach.
 * <p>
 * Two families of behaviour are pinned here, and both are load-bearing for the
 * callers rather than incidental:
 * <ul>
 * <li><strong>The "did anything change?" flag.</strong> Every transform returns
 * the document only when it rewrote something, and {@code null} otherwise —
 * {@code MigrationManager}'s sweep uses that to decide whether to back the
 * document up and write it back, and {@code RestImportService} to decide
 * whether to re-serialize an uploaded body. Each accumulator is written
 * {@code flag = convert(x) || flag}, so a document holding one convertible and
 * one already-migrated entry must still come back dirty. Written the other way
 * round — {@code flag = convert(x)} — the last entry would decide for the whole
 * document and a real rewrite would be dropped on the floor, silently, with the
 * legacy shape left in storage.</li>
 * <li><strong>Shapes a 5.x document is allowed to have.</strong> An output
 * entry with no {@code outputs}, an output with no {@code valueAlternatives},
 * an alternative that is neither a string nor an object, a conversation memory
 * with no properties at all. None may throw: the sweep runs over a whole
 * collection, and the transforms' own catch would turn one malformed document
 * into a silent {@code null} for the rest of that document's content.</li>
 * </ul>
 */
@DisplayName("LegacyDocumentMigrations — transform edges")
class LegacyDocumentMigrationsBranchTest {

    /** A propertysetter instruction still carrying the untyped v5 {@code value}. */
    private static Document legacyProperty(String name, Object value) {
        return new Document("name", name).append("value", value);
    }

    /** A propertysetter instruction already on the v6 typed field. */
    private static Document migratedProperty(String name, String value) {
        return new Document("name", name).append("valueString", value);
    }

    private static Document propertySetterDoc(Document... properties) {
        return new Document("setOnActions", new ArrayList<>(List.of(new Document("actions", List.of("*"))
                .append("setProperties", new ArrayList<>(List.of(properties))))));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> setPropertiesOf(Document migrated) {
        return (List<Map<String, Object>>) ((List<Map<String, Object>>) migrated.get("setOnActions")).getFirst()
                .get("setProperties");
    }

    @Nested
    @DisplayName("propertySetter — the document-changed flag")
    class PropertySetterFlag {

        @Test
        @DisplayName("an already-migrated property after a legacy one does not clear the flag")
        void aLaterCleanPropertyKeepsTheDocumentDirty() {
            var document = propertySetterDoc(legacyProperty("city", "Vienna"), migratedProperty("country", "Austria"));

            Document migrated = LegacyDocumentMigrations.propertySetter().migrate(document);

            assertNotNull(migrated, "the document was rewritten, so it must be reported as changed — reported clean, "
                    + "the sweep would leave the legacy 'value' key in storage");
            var properties = setPropertiesOf(migrated);
            assertEquals("Vienna", properties.getFirst().get("valueString"));
            assertFalse(properties.getFirst().containsKey("value"), "the legacy key must be gone");
            assertEquals("Austria", properties.get(1).get("valueString"), "the clean property must be left exactly as it was");
        }

        @Test
        @DisplayName("a document with nothing to migrate reports unchanged")
        void allCleanPropertiesReportUnchanged() {
            var document = propertySetterDoc(migratedProperty("country", "Austria"));

            assertNull(LegacyDocumentMigrations.propertySetter().migrate(document),
                    "returning the document would make the sweep back up and rewrite a document it never touched");
        }
    }

    @Nested
    @DisplayName("apiCalls — the document-changed flag across pre/post processing")
    class ApiCallsFlag {

        private static Document httpCallsDoc(Document httpCall) {
            return new Document("httpCalls", new ArrayList<>(List.of(httpCall)));
        }

        private static Document instructions(String block, Document... propertyInstructions) {
            return new Document(block,
                    new Document("propertyInstructions", new ArrayList<>(List.of(propertyInstructions))));
        }

        @Test
        @DisplayName("clean pre/post blocks do not clear a flag the targetServer rename already set")
        void cleanBlocksKeepTheTargetServerRewrite() {
            var httpCall = instructions("preRequest", migratedProperty("a", "1"))
                    .append("postResponse", new Document("propertyInstructions",
                            new ArrayList<>(List.of(migratedProperty("b", "2")))));
            var document = httpCallsDoc(httpCall).append("targetServer", "https://api.example.invalid");

            Document migrated = LegacyDocumentMigrations.apiCalls().migrate(document);

            assertNotNull(migrated, "the targetServer rename happened, so the document must still be reported as changed");
            assertEquals("https://api.example.invalid", migrated.get("targetServerUrl"));
            assertFalse(migrated.containsKey("targetServer"), "the legacy key must be removed, not merely duplicated");
        }

        @Test
        @DisplayName("a postResponse instruction is migrated even when nothing else in the document is legacy")
        @SuppressWarnings("unchecked")
        void postResponseAloneMarksTheDocumentDirty() {
            var document = httpCallsDoc(instructions("postResponse", legacyProperty("total", 3), migratedProperty("b", "2")));

            Document migrated = LegacyDocumentMigrations.apiCalls().migrate(document);

            assertNotNull(migrated, "a legacy postResponse instruction alone must mark the document as changed");
            var httpCalls = (List<Map<String, Object>>) migrated.get("httpCalls");
            var postResponse = (Map<String, List<Map<String, Object>>>) httpCalls.getFirst().get("postResponse");
            var migratedInstructions = postResponse.get("propertyInstructions");
            assertEquals(3, migratedInstructions.getFirst().get("valueInt"));
            assertFalse(migratedInstructions.getFirst().containsKey("value"));
            assertEquals("2", migratedInstructions.get(1).get("valueString"),
                    "the already-migrated instruction after it must be untouched");
        }

        @Test
        @DisplayName("a fully clean httpcalls document reports unchanged")
        void cleanHttpCallsReportUnchanged() {
            var document = httpCallsDoc(instructions("postResponse", migratedProperty("b", "2")))
                    .append("targetServerUrl", "https://api.example.invalid");

            assertNull(LegacyDocumentMigrations.apiCalls().migrate(document));
        }
    }

    @Nested
    @DisplayName("convertPropertyInstructions — the remaining numeric BSON types")
    class NumericValues {

        @Test
        @DisplayName("Short becomes an int-typed value, not a Short left on the typed field")
        void shortValue() {
            var document = propertySetterDoc(legacyProperty("count", (short) 42));

            Document migrated = LegacyDocumentMigrations.propertySetter().migrate(document);

            assertNotNull(migrated);
            Object value = setPropertiesOf(migrated).getFirst().get("valueInt");
            assertEquals(42, value);
            assertInstanceOf(Integer.class, value, "the v6 property model declares an Integer — a Short would fail to bind");
        }

        @Test
        @DisplayName("Byte becomes an int-typed value")
        void byteValue() {
            var document = propertySetterDoc(legacyProperty("count", (byte) 7));

            Document migrated = LegacyDocumentMigrations.propertySetter().migrate(document);

            assertNotNull(migrated);
            Object value = setPropertiesOf(migrated).getFirst().get("valueInt");
            assertEquals(7, value);
            assertInstanceOf(Integer.class, value);
        }

        @Test
        @DisplayName("a Long below Integer.MIN_VALUE is preserved, not wrapped around")
        void longBelowIntRangeIsPreserved() {
            long tooSmall = Integer.MIN_VALUE - 1L;
            var document = propertySetterDoc(legacyProperty("offset", tooSmall));

            assertNull(LegacyDocumentMigrations.propertySetter().migrate(document),
                    "no field can hold it, so the document must be reported unchanged rather than rewritten");
            var property = setPropertiesOf(document).getFirst();
            assertEquals(tooSmall, property.get("value"), "the value must survive under its original key");
            assertFalse(property.containsKey("valueInt"), "narrowing it to an int would silently corrupt the value");
        }
    }

    @Nested
    @DisplayName("output — shapes a stored v5 document is allowed to have")
    class OutputShapes {

        private static Document outputSetDoc(Document... entries) {
            return new Document("outputSet", new ArrayList<>(List.of(entries)));
        }

        private static Document outputs(Document... outputs) {
            return new Document("action", "greet").append("outputs", new ArrayList<>(List.of(outputs)));
        }

        /** An output whose single alternative is the bare v5 string form. */
        private static Document legacyOutput() {
            return new Document("valueAlternatives", new ArrayList<Object>(List.of("Hello!")));
        }

        @SuppressWarnings("unchecked")
        private static List<Object> alternativesOf(Document migrated, int entry, int output) {
            var entries = (List<Map<String, Object>>) migrated.get("outputSet");
            var outputs = (List<Map<String, Object>>) entries.get(entry).get("outputs");
            return (List<Object>) outputs.get(output).get("valueAlternatives");
        }

        @Test
        @DisplayName("an outputSet entry carrying no 'outputs' is skipped, and the entries after it still migrate")
        void entryWithoutOutputsIsSkipped() {
            // The skip must be a guarded skip, not a swallowed NullPointerException:
            // the transform's own catch returns null for the WHOLE document, so a
            // throw here would silently discard the migration of every later entry —
            // and the sweep, seeing null, would report the document as needing no
            // change and leave the v5 shape in storage.
            var document = outputSetDoc(new Document("action", "greet").append("timesOccurred", 0),
                    outputs(legacyOutput()));

            Document migrated = LegacyDocumentMigrations.output().migrate(document);

            assertNotNull(migrated, "the second entry was legacy, so the document must come back changed");
            assertInstanceOf(TextOutputItem.class, alternativesOf(migrated, 1, 0).getFirst(),
                    "the entry after the one without 'outputs' must still have been migrated");
        }

        @Test
        @DisplayName("an output carrying no 'valueAlternatives' is skipped, and the outputs after it still migrate")
        void outputWithoutAlternativesIsSkipped() {
            var document = outputSetDoc(outputs(new Document("type", "text"), legacyOutput()));

            Document migrated = LegacyDocumentMigrations.output().migrate(document);

            assertNotNull(migrated, "the second output was legacy, so the document must come back changed");
            assertInstanceOf(TextOutputItem.class, alternativesOf(migrated, 0, 1).getFirst(),
                    "the output after the one without 'valueAlternatives' must still have been migrated");
        }

        @Test
        @DisplayName("an alternative that is neither string nor object is left exactly as it is")
        @SuppressWarnings("unchecked")
        void unknownAlternativeShapeIsLeftAlone() {
            var document = outputSetDoc(outputs(new Document("valueAlternatives",
                    new ArrayList<Object>(List.of("Hello!", 42)))));

            Document migrated = LegacyDocumentMigrations.output().migrate(document);

            assertNotNull(migrated, "the string alternative was upgraded, so the document changed");
            var outputs = (List<Map<String, Object>>) ((List<Map<String, Object>>) migrated.get("outputSet")).getFirst()
                    .get("outputs");
            var alternatives = (List<Object>) outputs.getFirst().get("valueAlternatives");
            assertInstanceOf(TextOutputItem.class, alternatives.getFirst());
            assertEquals(42, alternatives.get(1), "an alternative the transform does not understand must be preserved "
                    + "verbatim — dropping or rewriting it would lose output the agent still emits");
        }
    }

    @Nested
    @DisplayName("conversationMemory")
    class ConversationMemory {

        /**
         * Insertion-ordered on purpose: the transform folds the per-property result
         * into one flag while iterating {@code keySet()}, so which property comes first
         * is the whole point of {@link #aLaterCleanPropertyKeepsTheMemoryDirty}.
         */
        private static Document memoryDoc(Document... namedProperties) {
            var properties = new LinkedHashMap<String, Object>();
            for (Document property : namedProperties) {
                properties.put(property.getString("name"), property);
            }
            return new Document("_id", new ObjectId()).append("conversationProperties", properties);
        }

        @Test
        @DisplayName("a memory with no conversationProperties at all reports unchanged")
        void noPropertiesReportsUnchanged() {
            var document = new Document("_id", new ObjectId()).append("conversationId", "abc");

            assertNull(LegacyDocumentMigrations.conversationMemory().migrate(document));
        }

        @Test
        @DisplayName("an already-migrated property after a legacy one does not clear the flag")
        @SuppressWarnings("unchecked")
        void aLaterCleanPropertyKeepsTheMemoryDirty() {
            var document = memoryDoc(legacyProperty("city", "Vienna"), migratedProperty("country", "Austria"));

            Document migrated = LegacyDocumentMigrations.conversationMemory().migrate(document);

            assertNotNull(migrated, "the memory was rewritten, so it must be reported as changed");
            var properties = (Map<String, Map<String, Object>>) migrated.get("conversationProperties");
            assertEquals("Vienna", properties.get("city").get("valueString"));
            assertFalse(properties.get("city").containsKey("value"));
            assertEquals("Austria", properties.get("country").get("valueString"));
        }

        @Test
        @DisplayName("a memory whose properties are all on typed fields reports unchanged")
        void allCleanPropertiesReportUnchanged() {
            var document = memoryDoc(migratedProperty("country", "Austria"));

            assertNull(LegacyDocumentMigrations.conversationMemory().migrate(document),
                    "rewriting an untouched conversation memory would copy a whole transcript into the backup "
                            + "collection for nothing");
        }

        @Test
        @DisplayName("a malformed conversationProperties is reported unchanged instead of aborting the sweep")
        void malformedPropertiesAreSwallowed() {
            // The sweep walks a whole collection; letting one hand-edited document
            // throw would stop every later document from being migrated.
            var document = new Document("_id", new ObjectId()).append("conversationProperties", "not-a-map");

            assertNull(LegacyDocumentMigrations.conversationMemory().migrate(document));
            assertEquals("not-a-map", document.get("conversationProperties"),
                    "the unreadable document must be left untouched rather than half-rewritten");
        }
    }

    @Nested
    @DisplayName("the transforms are backend-neutral — no storage behind them")
    class Purity {

        @Test
        @DisplayName("each call returns an independent transform over the caller's own document")
        void transformsAreReusable() {
            var transform = LegacyDocumentMigrations.propertySetter();

            assertNotNull(transform.migrate(propertySetterDoc(legacyProperty("a", "1"))));
            assertNotNull(transform.migrate(propertySetterDoc(legacyProperty("b", "2"))),
                    "the same transform instance must migrate a second document — RestImportService reuses it "
                            + "across every entry in one uploaded ZIP");
            assertNull(transform.migrate(propertySetterDoc(migratedProperty("c", "3"))),
                    "and must not carry a 'changed' flag over from the previous document");
        }
    }
}
