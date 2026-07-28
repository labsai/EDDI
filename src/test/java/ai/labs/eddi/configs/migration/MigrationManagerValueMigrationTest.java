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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.configs.migration.MigrationManager.BACKUP_COLLECTION_SUFFIX;
import static ai.labs.eddi.configs.migration.MigrationManager.COLLECTION_PROPERTYSETTER;
import static ai.labs.eddi.configs.migration.MigrationManager.MIGRATION_CONFIRMATION;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * B12 — the legacy untyped {@code value} field must never be deleted without a
 * typed replacement. Before the fix only String/Map/Integer/Float were
 * rewritten while the {@code value} key was removed unconditionally, so
 * doubles, longs, booleans and arrays were erased from propertysetter, apicalls
 * and conversationmemory documents with no error.
 */
@DisplayName("MigrationManager — legacy 'value' field type handling")
class MigrationManagerValueMigrationTest {

    @Mock
    private MongoDatabase database;
    @Mock
    private MigrationLogStore migrationLogStore;

    private MigrationManager migrationManager;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        openMocks(this);
        MongoCollection<Document> mockCollection = mock(MongoCollection.class);
        when(database.getCollection(anyString())).thenReturn(mockCollection);

        migrationManager = new MigrationManager(database, migrationLogStore, true);
    }

    // ─── every BSON type the property model can hold ──────────────

    @Nested
    @DisplayName("supported BSON types round-trip onto their typed field")
    class SupportedTypes {

        @Test
        @DisplayName("String → valueString")
        void stringValue() {
            assertMigratedTo("valueString", "hello", "hello");
        }

        @Test
        @DisplayName("Map → valueObject")
        void mapValue() {
            var mapValue = Map.<String, Object>of("nested", "data");
            assertMigratedTo("valueObject", mapValue, mapValue);
        }

        @Test
        @DisplayName("List (BSON array) → valueList")
        void listValue() {
            var listValue = List.<Object>of("a", "b", 3);
            assertMigratedTo("valueList", listValue, listValue);
        }

        @Test
        @DisplayName("Boolean → valueBoolean")
        void booleanValue() {
            assertMigratedTo("valueBoolean", true, true);
        }

        @Test
        @DisplayName("Integer → valueInt")
        void integerValue() {
            assertMigratedTo("valueInt", 42, 42);
        }

        @Test
        @DisplayName("Long within int range → valueInt")
        void longValue() {
            assertMigratedTo("valueInt", 42L, 42);
        }

        @Test
        @DisplayName("Float → valueFloat")
        void floatValue() {
            assertMigratedTo("valueFloat", 3.14f, 3.14f);
        }

        @Test
        @DisplayName("Double (BSON double) → valueFloat, value preserved")
        void doubleValue() {
            assertMigratedTo("valueFloat", 2.71828d, 2.71828d);
        }

        @Test
        @DisplayName("null → valueString null")
        void nullValue() {
            var property = new HashMap<String, Object>();
            property.put("value", null);

            assertNotNull(migrationManager.migratePropertySetter().migrate(propertySetterDoc(property)));
            assertTrue(property.containsKey("valueString"));
            assertNull(property.get("valueString"));
            assertFalse(property.containsKey("value"));
        }

        private void assertMigratedTo(String expectedField, Object legacyValue, Object expectedValue) {
            var property = new HashMap<String, Object>();
            property.put("value", legacyValue);

            Document result = migrationManager.migratePropertySetter().migrate(propertySetterDoc(property));

            assertNotNull(result, "document should have been migrated");
            assertEquals(expectedValue, property.get(expectedField),
                    legacyValue.getClass().getSimpleName() + " must be carried over to " + expectedField);
            assertFalse(property.containsKey("value"), "legacy 'value' is replaced, not kept alongside");
        }
    }

    // ─── types the property model cannot hold ─────────────────────

    @Nested
    @DisplayName("unmappable BSON types are kept instead of erased")
    class UnsupportedTypes {

        @Test
        @DisplayName("Date is left under 'value' and the document is not rewritten")
        void dateValue() {
            assertPreserved(new Date(1700000000000L));
        }

        @Test
        @DisplayName("ObjectId is left under 'value'")
        void objectIdValue() {
            assertPreserved(new ObjectId("5262b802dc6c4008b54c7c0b"));
        }

        @Test
        @DisplayName("Long beyond int range is left under 'value' rather than truncated")
        void oversizedLongValue() {
            assertPreserved(7_000_000_000L);
        }

        private void assertPreserved(Object legacyValue) {
            var property = new HashMap<String, Object>();
            property.put("value", legacyValue);

            Document result = migrationManager.migratePropertySetter().migrate(propertySetterDoc(property));

            assertNull(result, "an unmappable value is not a migration — the document must not be rewritten");
            assertEquals(legacyValue, property.get("value"), "the original value must still be there");
            assertEquals(1, property.size(), "no typed field may be invented for an unmappable value");
        }
    }

    // ─── conversation memory / apicalls share the same conversion ──

    @Test
    @DisplayName("apicalls postResponse instructions keep a boolean value")
    void apiCallsPostResponseBoolean() {
        var propertyInstruction = new HashMap<String, Object>();
        propertyInstruction.put("value", false);

        var postResponse = new HashMap<String, List<Map<String, Object>>>();
        postResponse.put("propertyInstructions", List.of(propertyInstruction));

        var httpCall = new HashMap<String, Object>();
        httpCall.put("postResponse", postResponse);

        Document result = migrationManager.migrateApiCalls().migrate(new Document("httpCalls", List.of(httpCall)));

        assertNotNull(result);
        assertEquals(false, propertyInstruction.get("valueBoolean"));
        assertFalse(propertyInstruction.containsKey("value"));
    }

    // ─── pre-migration backup ─────────────────────────────────────

    @Nested
    @DisplayName("pre-migration backup")
    class Backup {

        @Test
        @DisplayName("original document is copied to the backup collection before it is rewritten")
        @SuppressWarnings("unchecked")
        void backsUpBeforeWriting() {
            var property = new HashMap<String, Object>();
            property.put("value", "hello");
            Document document = new Document("_id", new ObjectId()).append("setOnActions", List.of(actionContainer(property)));

            MongoCollection<Document> propertySetter = collectionReturning(document);
            MongoCollection<Document> backupCollection = emptyCollection();
            MongoCollection<Document> otherCollections = emptyCollection();

            when(database.getCollection(anyString())).thenAnswer(invocation -> {
                String name = invocation.getArgument(0);
                if (COLLECTION_PROPERTYSETTER.equals(name)) {
                    return propertySetter;
                }
                if ((COLLECTION_PROPERTYSETTER + BACKUP_COLLECTION_SUFFIX).equals(name)) {
                    return backupCollection;
                }
                return otherCollections;
            });
            when(migrationLogStore.readMigrationLog(MIGRATION_CONFIRMATION)).thenReturn(null);

            var manager = new MigrationManager(database, migrationLogStore, true);
            manager.startMigrationIfFirstTimeRun(() -> {
            });

            ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
            verify(backupCollection).insertOne(captor.capture());
            verify(propertySetter).replaceOne(any(), eq(document));

            Map<String, Object> backedUpProperty = firstSetProperty(captor.getValue());
            assertEquals("hello", backedUpProperty.get("value"), "the backup must hold the pre-migration state");
            assertFalse(backedUpProperty.containsKey("valueString"), "the backup must not share the migrated nested maps");
            assertEquals("hello", property.get("valueString"), "the live document is still migrated");
        }

        @Test
        @DisplayName("backup can be switched off")
        @SuppressWarnings("unchecked")
        void backupDisabled() {
            var property = new HashMap<String, Object>();
            property.put("value", "hello");
            Document document = new Document("_id", new ObjectId()).append("setOnActions", List.of(actionContainer(property)));

            MongoCollection<Document> propertySetter = collectionReturning(document);
            MongoCollection<Document> backupCollection = emptyCollection();
            MongoCollection<Document> otherCollections = emptyCollection();

            when(database.getCollection(anyString())).thenAnswer(invocation -> {
                String name = invocation.getArgument(0);
                if (COLLECTION_PROPERTYSETTER.equals(name)) {
                    return propertySetter;
                }
                if ((COLLECTION_PROPERTYSETTER + BACKUP_COLLECTION_SUFFIX).equals(name)) {
                    return backupCollection;
                }
                return otherCollections;
            });
            when(migrationLogStore.readMigrationLog(MIGRATION_CONFIRMATION)).thenReturn(null);

            var manager = new MigrationManager(database, migrationLogStore, true, false);
            manager.startMigrationIfFirstTimeRun(() -> {
            });

            verify(backupCollection, never()).insertOne(any(Document.class));
            verify(propertySetter).replaceOne(any(), eq(document));
        }

        @SuppressWarnings("unchecked")
        private MongoCollection<Document> collectionReturning(Document document) {
            MongoCollection<Document> collection = mock(MongoCollection.class);
            FindIterable<Document> iterable = mock(FindIterable.class);
            MongoCursor<Document> cursor = mock(MongoCursor.class);
            when(cursor.hasNext()).thenReturn(true, false);
            when(cursor.next()).thenReturn(document);
            doReturn(cursor).when(iterable).iterator();
            when(collection.find()).thenReturn(iterable);
            return collection;
        }

        @SuppressWarnings("unchecked")
        private MongoCollection<Document> emptyCollection() {
            MongoCollection<Document> collection = mock(MongoCollection.class);
            FindIterable<Document> iterable = mock(FindIterable.class);
            MongoCursor<Document> cursor = mock(MongoCursor.class);
            when(cursor.hasNext()).thenReturn(false);
            doReturn(cursor).when(iterable).iterator();
            when(collection.find()).thenReturn(iterable);
            return collection;
        }
    }

    // ─── deepCopy ─────────────────────────────────────────────────

    @Test
    @DisplayName("deepCopy detaches nested maps and lists")
    void deepCopyDetachesNestedStructures() {
        var nestedList = new ArrayList<Object>(List.of("a"));
        var nested = new Document("inner", "original").append("list", nestedList);
        var original = new Document("nested", nested);

        Document copy = MigrationManager.deepCopy(original);
        nested.put("inner", "mutated");
        nestedList.add("b");

        Document copiedNested = (Document) copy.get("nested");
        assertEquals("original", copiedNested.get("inner"));
        assertEquals(List.of("a"), copiedNested.get("list"));
    }

    // ─── helpers ──────────────────────────────────────────────────

    private static Map<String, Object> actionContainer(Map<String, Object> property) {
        var setProperties = new ArrayList<Map<String, Object>>();
        setProperties.add(property);

        var actionContainer = new HashMap<String, Object>();
        actionContainer.put("setProperties", setProperties);
        return actionContainer;
    }

    private static Document propertySetterDoc(Map<String, Object> property) {
        return new Document("setOnActions", List.of(actionContainer(property)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstSetProperty(Document document) {
        var setOnActions = (List<Map<String, Object>>) document.get("setOnActions");
        var setProperties = (List<Map<String, Object>>) setOnActions.get(0).get("setProperties");
        return setProperties.get(0);
    }
}
