/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.migration;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a 5.x LLM task document keeps from 5.x when it is carried to v6:
 * {@code responseFormat: json} becomes {@code convertToObject}, and the two
 * tool auto-discovery flags it never had are written as {@code false}. The same
 * transform runs in the collection sweep and in the ZIP import, and is
 * idempotent.
 */
@DisplayName("LegacyDocumentMigrations.llm — 5.x LLM task defaults")
class LegacyDocumentMigrationsLlmTest {

    private static Document v5Task(Map<String, Object> parameters) {
        var task = new Document("id", "chat").append("type", "gemini").append("actions", List.of("send_message"));
        if (parameters != null) {
            task.append("parameters", new HashMap<>(parameters));
        }
        return new Document("tasks", new ArrayList<>(List.of(task)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstTask(Document doc) {
        return ((List<Map<String, Object>>) doc.get("tasks")).get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> params(Document doc) {
        return (Map<String, Object>) firstTask(doc).get("parameters");
    }

    @Nested
    @DisplayName("responseFormat: json")
    class JsonMode {

        @Test
        @DisplayName("maps to convertToObject=true and keeps responseFormat")
        void mapsToConvertToObject() {
            var doc = v5Task(Map.of("responseFormat", "json", "systemMessage", "x"));

            assertNotNull(LegacyDocumentMigrations.llm().migrate(doc));

            assertEquals("true", params(doc).get("convertToObject"));
            assertEquals("json", params(doc).get("responseFormat"));
            assertEquals("x", params(doc).get("systemMessage"));
        }

        @Test
        @DisplayName("is case-insensitive and tolerates whitespace")
        void caseInsensitive() {
            var doc = v5Task(Map.of("responseFormat", " JSON "));

            LegacyDocumentMigrations.llm().migrate(doc);

            assertEquals("true", params(doc).get("convertToObject"));
        }

        @Test
        @DisplayName("never overrides an explicit convertToObject")
        void explicitConvertToObjectWins() {
            var doc = v5Task(Map.of("responseFormat", "json", "convertToObject", "false"));

            LegacyDocumentMigrations.llm().migrate(doc);

            assertEquals("false", params(doc).get("convertToObject"));
        }

        @Test
        @DisplayName("another responseFormat value is left alone")
        void otherFormatIsNotJson() {
            var doc = v5Task(Map.of("responseFormat", "text"));

            LegacyDocumentMigrations.llm().migrate(doc);

            assertFalse(params(doc).containsKey("convertToObject"));
        }
    }

    @Nested
    @DisplayName("tool auto-discovery flags")
    class Tools {

        @Test
        @DisplayName("absent flags are written as false (the 5.x behaviour)")
        void absentFlagsBecomeFalse() {
            var doc = v5Task(Map.of("systemMessage", "x"));

            assertNotNull(LegacyDocumentMigrations.llm().migrate(doc));

            assertEquals(Boolean.FALSE, firstTask(doc).get("enableHttpCallTools"));
            assertEquals(Boolean.FALSE, firstTask(doc).get("enableMcpCallTools"));
        }

        @Test
        @DisplayName("a flag the document already carries is kept")
        void presentFlagIsKept() {
            var doc = v5Task(Map.of("systemMessage", "x"));
            firstTask(doc).put("enableHttpCallTools", Boolean.TRUE);

            LegacyDocumentMigrations.llm().migrate(doc);

            assertEquals(Boolean.TRUE, firstTask(doc).get("enableHttpCallTools"));
            assertEquals(Boolean.FALSE, firstTask(doc).get("enableMcpCallTools"));
        }

        @Test
        @DisplayName("a task without parameters is migrated too")
        void taskWithoutParameters() {
            var doc = v5Task(null);

            assertNotNull(LegacyDocumentMigrations.llm().migrate(doc));

            assertEquals(Boolean.FALSE, firstTask(doc).get("enableHttpCallTools"));
        }
    }

    @Nested
    @DisplayName("idempotence and shape")
    class Shape {

        @Test
        @DisplayName("a second run changes nothing and answers null")
        void idempotent() {
            var doc = v5Task(Map.of("responseFormat", "json"));
            assertNotNull(LegacyDocumentMigrations.llm().migrate(doc));
            var afterFirst = new Document(doc);

            assertNull(LegacyDocumentMigrations.llm().migrate(doc));

            assertEquals(afterFirst, doc);
        }

        @Test
        @DisplayName("a document without tasks is left alone")
        void noTasks() {
            assertNull(LegacyDocumentMigrations.llm().migrate(new Document("other", "x")));
        }

        @Test
        @DisplayName("every task of the document is migrated")
        void everyTask() {
            var doc = v5Task(Map.of("responseFormat", "json"));
            @SuppressWarnings("unchecked")
            var tasks = (List<Object>) doc.get("tasks");
            tasks.add(new Document("id", "second").append("parameters", new HashMap<>(Map.of("responseFormat", "json"))));

            LegacyDocumentMigrations.llm().migrate(doc);

            @SuppressWarnings("unchecked")
            var second = (Map<String, Object>) tasks.get(1);
            assertEquals(Boolean.FALSE, second.get("enableMcpCallTools"));
            @SuppressWarnings("unchecked")
            var secondParams = (Map<String, Object>) second.get("parameters");
            assertEquals("true", secondParams.get("convertToObject"));
        }
    }

    /**
     * The collection sweep applies it to the LLM collection and its history, and to
     * nothing else.
     */
    @Nested
    @DisplayName("V6RenameMigration sweep")
    class Sweep {

        @SuppressWarnings("unchecked")
        private MongoCollection<Document> collectionHolding(Document... docs) {
            MongoCollection<Document> col = mock(MongoCollection.class);
            when(col.estimatedDocumentCount()).thenReturn((long) docs.length);
            FindIterable<Document> iterable = mock(FindIterable.class);
            MongoCursor<Document> cursor = mock(MongoCursor.class);
            Boolean[] has = new Boolean[docs.length + 1];
            for (int i = 0; i < docs.length; i++) {
                has[i] = true;
            }
            has[docs.length] = false;
            when(cursor.hasNext()).thenReturn(has[0], Arrays.copyOfRange(has, 1, has.length));
            when(cursor.next()).thenReturn(docs[0], Arrays.copyOfRange(docs, 1, docs.length));
            when(iterable.iterator()).thenReturn(cursor);
            when(col.find()).thenReturn(iterable);
            return col;
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("the llms collection is carried to the 5.x defaults and saved; other collections are not touched")
        void llmsCollectionIsMigrated() {
            var database = mock(MongoDatabase.class);
            var logStore = mock(IMigrationLogStore.class);
            when(logStore.readMigrationLog(anyString())).thenReturn(null);

            var llmDoc = v5Task(Map.of("responseFormat", "json")).append("_id", new ObjectId());
            // an ordinary document in another collection that happens to look alike
            var otherDoc = v5Task(Map.of("responseFormat", "json")).append("_id", new ObjectId());

            MongoCollection<Document> llms = collectionHolding(llmDoc);
            MongoCollection<Document> outputs = collectionHolding(otherDoc);
            MongoCollection<Document> empty = mock(MongoCollection.class);
            when(empty.estimatedDocumentCount()).thenReturn(0L);
            when(database.getCollection(anyString())).thenAnswer(inv -> switch ((String) inv.getArgument(0)) {
                case "llms" -> llms;
                case "outputs" -> outputs;
                default -> empty;
            });
            when(database.getName()).thenReturn("eddi");

            new V6RenameMigration(database, logStore, true).runIfNeeded();

            assertEquals("true", params(llmDoc).get("convertToObject"));
            assertEquals(Boolean.FALSE, firstTask(llmDoc).get("enableHttpCallTools"));
            verify(llms).replaceOne(any(), eq(llmDoc));
            assertFalse(params(otherDoc).containsKey("convertToObject"), "only the LLM collection is migrated");
        }
    }
}
