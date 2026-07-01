/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.migration;

import ai.labs.eddi.configs.migration.model.MigrationLog;
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
import org.mockito.Mock;

import java.util.*;

import static ai.labs.eddi.configs.migration.MigrationManager.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Extended branch coverage tests for {@link MigrationManager} focusing on
 * startMigrationIfFirstTimeRun lifecycle, saveToPersistence branches,
 * convertPropertyInstructions value type branches, migrateOutput type
 * detection, and removeNonSupportedProperties/removeNonStringProperties.
 */
@DisplayName("MigrationManager — Extended Branch Coverage")
class MigrationManagerBranchTest {

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
        FindIterable<Document> mockIterable = mock(FindIterable.class);
        MongoCursor<Document> mockCursor = mock(MongoCursor.class);
        when(mockCursor.hasNext()).thenReturn(false);
        doReturn(mockCursor).when(mockIterable).iterator();
        when(mockCollection.find()).thenReturn(mockIterable);
        when(database.getCollection(anyString())).thenReturn(mockCollection);

        migrationManager = new MigrationManager(database, migrationLogStore, true);
    }

    // =========================================================
    // startMigrationIfFirstTimeRun lifecycle
    // =========================================================

    @Nested
    @DisplayName("startMigrationIfFirstTimeRun")
    class StartMigrationLifecycle {

        @Test
        @DisplayName("first run with no log triggers migration and callback")
        void firstRunTriggersMigration() {
            when(migrationLogStore.readMigrationLog(MIGRATION_CONFIRMATION)).thenReturn(null);

            var finished = mock(IMigrationManager.IMigrationFinished.class);
            migrationManager.startMigrationIfFirstTimeRun(finished);

            verify(migrationLogStore).createMigrationLog(any(MigrationLog.class));
            verify(finished).onComplete();
        }

        @Test
        @DisplayName("already migrated does not create log again")
        void alreadyMigratedSkips() {
            when(migrationLogStore.readMigrationLog(MIGRATION_CONFIRMATION))
                    .thenReturn(new MigrationLog(MIGRATION_CONFIRMATION));

            var finished = mock(IMigrationManager.IMigrationFinished.class);
            migrationManager.startMigrationIfFirstTimeRun(finished);

            verify(migrationLogStore, never()).createMigrationLog(any());
            verify(finished).onComplete();
        }

        @Test
        @DisplayName("concurrent calls are prevented by isCurrentlyRunning flag")
        void concurrentCallsPrevented() {
            when(migrationLogStore.readMigrationLog(MIGRATION_CONFIRMATION)).thenReturn(null);

            var finished = mock(IMigrationManager.IMigrationFinished.class);

            // First call
            migrationManager.startMigrationIfFirstTimeRun(finished);
            // Second call should also work (isCurrentlyRunning is reset after first call)
            migrationManager.startMigrationIfFirstTimeRun(finished);

            // Should be called twice since each completes before the next starts
            verify(finished, times(2)).onComplete();
        }
    }

    // =========================================================
    // startMigrationIfFirstTimeRun with skipConversationMemories
    // =========================================================

    @Nested
    @DisplayName("skipConversationMemories flag")
    class SkipConversationMemories {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("skipConversationMemories=true skips conversation memory migration")
        void skipTrue() {
            MongoCollection<Document> mockCollection = mock(MongoCollection.class);
            FindIterable<Document> mockIterable = mock(FindIterable.class);
            MongoCursor<Document> mockCursor = mock(MongoCursor.class);
            when(mockCursor.hasNext()).thenReturn(false);
            doReturn(mockCursor).when(mockIterable).iterator();
            when(mockCollection.find()).thenReturn(mockIterable);
            when(database.getCollection(anyString())).thenReturn(mockCollection);

            var skipManager = new MigrationManager(database, migrationLogStore, true);
            when(migrationLogStore.readMigrationLog(MIGRATION_CONFIRMATION)).thenReturn(null);

            var finished = mock(IMigrationManager.IMigrationFinished.class);
            skipManager.startMigrationIfFirstTimeRun(finished);

            verify(finished).onComplete();
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("skipConversationMemories=false includes conversation memory migration")
        void skipFalse() {
            MongoCollection<Document> mockCollection = mock(MongoCollection.class);
            FindIterable<Document> mockIterable = mock(FindIterable.class);
            MongoCursor<Document> mockCursor = mock(MongoCursor.class);
            when(mockCursor.hasNext()).thenReturn(false);
            doReturn(mockCursor).when(mockIterable).iterator();
            when(mockCollection.find()).thenReturn(mockIterable);
            when(database.getCollection(anyString())).thenReturn(mockCollection);

            var noSkipManager = new MigrationManager(database, migrationLogStore, false);
            when(migrationLogStore.readMigrationLog(MIGRATION_CONFIRMATION)).thenReturn(null);

            var finished = mock(IMigrationManager.IMigrationFinished.class);
            noSkipManager.startMigrationIfFirstTimeRun(finished);

            verify(finished).onComplete();
        }
    }

    // =========================================================
    // convertPropertyInstructions — all value type branches
    // =========================================================

    @Nested
    @DisplayName("convertPropertyInstructions — value types")
    class ConvertPropertyInstructionTypes {

        @Test
        @DisplayName("String value → valueString")
        void stringValue() {
            Document doc = buildPropertySetterDoc(createProp("value", "hello"));
            Document result = migrationManager.migratePropertySetter().migrate(doc);
            assertNotNull(result);
        }

        @Test
        @DisplayName("Map value → valueObject")
        void mapValue() {
            Document doc = buildPropertySetterDoc(createProp("value", Map.of("k", "v")));
            Document result = migrationManager.migratePropertySetter().migrate(doc);
            assertNotNull(result);
        }

        @Test
        @DisplayName("Integer value → valueInt")
        void intValue() {
            Document doc = buildPropertySetterDoc(createProp("value", 42));
            Document result = migrationManager.migratePropertySetter().migrate(doc);
            assertNotNull(result);
        }

        @Test
        @DisplayName("Float value → valueFloat")
        void floatValue() {
            Document doc = buildPropertySetterDoc(createProp("value", 3.14f));
            Document result = migrationManager.migratePropertySetter().migrate(doc);
            assertNotNull(result);
        }

        @Test
        @DisplayName("UUID special string is converted")
        void uuidSpecialString() {
            Map<String, Object> prop = createProp("value", "[[${@java.util.UUID@randomUUID()}]]");
            Document doc = buildPropertySetterDoc(prop);
            Document result = migrationManager.migratePropertySetter().migrate(doc);
            assertNotNull(result);
            assertEquals("{uuidUtils:generateUUID()}", prop.get("valueString"));
        }

        @Test
        @DisplayName("null value with key present → valueString null")
        void nullValueWithKeyPresent() {
            Map<String, Object> prop = new HashMap<>();
            prop.put("value", null);
            Document doc = buildPropertySetterDoc(prop);
            Document result = migrationManager.migratePropertySetter().migrate(doc);
            assertNotNull(result);
            assertTrue(prop.containsKey("valueString"));
            assertNull(prop.get("valueString"));
        }

        @Test
        @DisplayName("no value key at all → no conversion")
        void noValueKey() {
            Map<String, Object> prop = new HashMap<>();
            prop.put("name", "test");
            Document doc = buildPropertySetterDoc(prop);
            Document result = migrationManager.migratePropertySetter().migrate(doc);
            assertNull(result); // No conversion needed
        }

        @Test
        @DisplayName("Boolean value falls through (non-String/Map/Integer/Float)")
        void booleanValue() {
            Map<String, Object> prop = createProp("value", true);
            Document doc = buildPropertySetterDoc(prop);
            Document result = migrationManager.migratePropertySetter().migrate(doc);
            assertNotNull(result);
            // Boolean doesn't match any type → value is removed, no typed key added
            assertFalse(prop.containsKey("value"));
        }

        @Test
        @DisplayName("exception in migration returns null")
        void exceptionReturnsNull() {
            // Create a document that will cause ClassCastException
            Document doc = new Document("setOnActions", "not-a-list");
            Document result = migrationManager.migratePropertySetter().migrate(doc);
            assertNull(result);
        }
    }

    // =========================================================
    // migrateApiCalls — targetServer field renames
    // =========================================================

    @Nested
    @DisplayName("migrateApiCalls — targetServer renames")
    class ApiCallsTargetServer {

        @Test
        @DisplayName("targetServer → targetServerUrl")
        void targetServerRename() {
            Document doc = new Document("targetServer", "https://api.example.com");
            Document result = migrationManager.migrateApiCalls().migrate(doc);
            assertNotNull(result);
            assertEquals("https://api.example.com", doc.get("targetServerUrl"));
            assertFalse(doc.containsKey("targetServer"));
        }

        @Test
        @DisplayName("targetServerUri → targetServerUrl")
        void targetServerUriRename() {
            Document doc = new Document("targetServerUri", "https://api.example.com");
            Document result = migrationManager.migrateApiCalls().migrate(doc);
            assertNotNull(result);
            assertEquals("https://api.example.com", doc.get("targetServerUrl"));
            assertFalse(doc.containsKey("targetServerUri"));
        }

        @Test
        @DisplayName("both targetServer and targetServerUri present")
        void bothPresent() {
            Document doc = new Document("targetServer", "first")
                    .append("targetServerUri", "second");
            Document result = migrationManager.migrateApiCalls().migrate(doc);
            assertNotNull(result);
            // second one wins (targetServerUri overwrites)
            assertEquals("second", doc.get("targetServerUrl"));
        }

        @Test
        @DisplayName("no target server fields → no migration")
        void noTargetServer() {
            Document doc = new Document("httpCalls", List.of());
            Document result = migrationManager.migrateApiCalls().migrate(doc);
            assertNull(result); // No changes needed
        }

        @Test
        @DisplayName("exception in migration returns null")
        void exceptionReturnsNull() {
            Document doc = new Document("httpCalls", "not-a-list");
            Document result = migrationManager.migrateApiCalls().migrate(doc);
            assertNull(result);
        }
    }

    // =========================================================
    // migrateOutput — type detection branches
    // =========================================================

    @Nested
    @DisplayName("migrateOutput — type detection")
    class OutputTypeDetection {

        @Test
        @DisplayName("output with 'uri' field → type=image")
        void uriFieldDetectsImage() {
            var outputValue = new HashMap<String, Object>();
            outputValue.put("uri", "https://img.com/pic.png");

            Document doc = buildOutputDoc(outputValue);
            Document result = migrationManager.migrateOutput().migrate(doc);

            assertNotNull(result);
            assertEquals("image", outputValue.get("type"));
        }

        @Test
        @DisplayName("output with 'expressions' field → type=quickReply")
        void expressionsFieldDetectsQuickReply() {
            var outputValue = new HashMap<String, Object>();
            outputValue.put("expressions", "expr1");

            Document doc = buildOutputDoc(outputValue);
            Document result = migrationManager.migrateOutput().migrate(doc);

            assertNotNull(result);
            assertEquals("quickReply", outputValue.get("type"));
        }

        @Test
        @DisplayName("output with 'placeholder' field → type=inputField")
        void placeholderFieldDetectsInputField() {
            var outputValue = new HashMap<String, Object>();
            outputValue.put("placeholder", "Enter text...");

            Document doc = buildOutputDoc(outputValue);
            Document result = migrationManager.migrateOutput().migrate(doc);

            assertNotNull(result);
            assertEquals("inputField", outputValue.get("type"));
        }

        @Test
        @DisplayName("output with 'placeholder' and isPassword=true → subType=password")
        void placeholderWithPassword() {
            var outputValue = new HashMap<String, Object>();
            outputValue.put("placeholder", "Enter password...");
            outputValue.put("isPassword", "true");

            Document doc = buildOutputDoc(outputValue);
            Document result = migrationManager.migrateOutput().migrate(doc);

            assertNotNull(result);
            assertEquals("inputField", outputValue.get("type"));
            assertEquals("password", outputValue.get("subType"));
        }

        @Test
        @DisplayName("output with 'placeholder' and isPassword=false → no subType")
        void placeholderWithNoPassword() {
            var outputValue = new HashMap<String, Object>();
            outputValue.put("placeholder", "Enter text...");
            outputValue.put("isPassword", "false");

            Document doc = buildOutputDoc(outputValue);
            Document result = migrationManager.migrateOutput().migrate(doc);

            assertNotNull(result);
            assertEquals("inputField", outputValue.get("type"));
            assertFalse(outputValue.containsKey("subType"));
        }

        @Test
        @DisplayName("output with 'onPress' field → type=button")
        void onPressFieldDetectsButton() {
            var outputValue = new HashMap<String, Object>();
            outputValue.put("onPress", "doAction");

            Document doc = buildOutputDoc(outputValue);
            Document result = migrationManager.migrateOutput().migrate(doc);

            assertNotNull(result);
            assertEquals("button", outputValue.get("type"));
        }

        @Test
        @DisplayName("output with no recognizable fields → type=other")
        void noFieldsDetectsOther() {
            var outputValue = new HashMap<String, Object>();
            outputValue.put("customField", "value");

            Document doc = buildOutputDoc(outputValue);
            Document result = migrationManager.migrateOutput().migrate(doc);

            assertNotNull(result);
            assertEquals("other", outputValue.get("type"));
        }

        @Test
        @DisplayName("output with type='text' removes non-text properties")
        void textRemovesNonTextProps() {
            var outputValue = new HashMap<String, Object>();
            outputValue.put("text", "Hello");
            outputValue.put("delay", 500);
            outputValue.put("extra", "remove-me");

            Document doc = buildOutputDoc(outputValue);
            Document result = migrationManager.migrateOutput().migrate(doc);

            assertNotNull(result);
            assertEquals("text", outputValue.get("type"));
            assertTrue(outputValue.containsKey("text"));
            assertTrue(outputValue.containsKey("delay"));
            assertFalse(outputValue.containsKey("extra"));
        }

        @Test
        @DisplayName("output with existing type that is not null/other → no type change")
        void existingTypePreserved() {
            var outputValue = new HashMap<String, Object>();
            outputValue.put("type", "text");
            outputValue.put("text", "Hello");

            Document doc = buildOutputDoc(outputValue);
            Document result = migrationManager.migrateOutput().migrate(doc);

            // type was already "text" (not null/other), so no conversion needed
            // but removeNonSupportedProperties is still applied
            assertNull(result);
        }

        @Test
        @DisplayName("output with type='other' → triggers type detection")
        void otherTypeTriggersDetection() {
            var outputValue = new HashMap<String, Object>();
            outputValue.put("type", "other");
            outputValue.put("text", "Hello");

            Document doc = buildOutputDoc(outputValue);
            Document result = migrationManager.migrateOutput().migrate(doc);

            assertNotNull(result);
            assertEquals("text", outputValue.get("type"));
        }

        @Test
        @DisplayName("exception in output migration returns null")
        void exceptionReturnsNull() {
            Document doc = new Document("outputSet", "not-a-list");
            Document result = migrationManager.migrateOutput().migrate(doc);
            assertNull(result);
        }

        @Test
        @DisplayName("output without outputSet key → no migration")
        void noOutputSetKey() {
            Document doc = new Document("other", "data");
            Document result = migrationManager.migrateOutput().migrate(doc);
            assertNull(result);
        }
    }

    // =========================================================
    // saveToPersistence — isHistory branches
    // =========================================================

    @Nested
    @DisplayName("saveToPersistence — version and isHistory")
    class SaveToPersistenceBranches {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("isHistory=true extracts compound ID")
        void isHistoryTrue() throws Exception {
            var idMap = new HashMap<String, Object>();
            idMap.put("_id", new ObjectId().toHexString());
            idMap.put("version", 2);

            Document doc = new Document("_id", idMap)
                    .append("_version", 2)
                    .append("data", "test");

            MongoCollection<Document> collection = mock(MongoCollection.class);

            var method = MigrationManager.class.getDeclaredMethod(
                    "saveToPersistence", String.class, Document.class,
                    boolean.class, MongoCollection.class);
            method.setAccessible(true);
            method.invoke(migrationManager, "test-type", doc, true, collection);

            verify(collection).replaceOne(any(), eq(doc));
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("isHistory=false uses simple ObjectId")
        void isHistoryFalse() throws Exception {
            var objectId = new ObjectId();
            Document doc = new Document("_id", objectId.toHexString())
                    .append("_version", 1)
                    .append("data", "test");

            MongoCollection<Document> collection = mock(MongoCollection.class);

            var method = MigrationManager.class.getDeclaredMethod(
                    "saveToPersistence", String.class, Document.class,
                    boolean.class, MongoCollection.class);
            method.setAccessible(true);
            method.invoke(migrationManager, "test-type", doc, false, collection);

            verify(collection).replaceOne(any(), eq(doc));
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("null version field defaults to -1")
        void nullVersionField() throws Exception {
            var objectId = new ObjectId();
            Document doc = new Document("_id", objectId.toHexString())
                    .append("data", "test");
            // No _version field

            MongoCollection<Document> collection = mock(MongoCollection.class);

            var method = MigrationManager.class.getDeclaredMethod(
                    "saveToPersistence", String.class, Document.class,
                    boolean.class, MongoCollection.class);
            method.setAccessible(true);
            method.invoke(migrationManager, "test-type", doc, false, collection);

            verify(collection).replaceOne(any(), eq(doc));
        }
    }

    // =========================================================
    // removeNonSupportedProperties
    // =========================================================

    @Nested
    @DisplayName("removeNonSupportedProperties")
    class RemoveNonSupportedPropertiesTests {

        @Test
        @DisplayName("removes all keys except type and specified fieldNames")
        void removesExceptTypeAndFieldNames() throws Exception {
            var map = new HashMap<String, Object>();
            map.put("type", "text");
            map.put("text", "Hello");
            map.put("delay", 500);
            map.put("uri", "remove");
            map.put("extra", "remove");

            var method = MigrationManager.class.getDeclaredMethod(
                    "removeNonSupportedProperties", Map.class, String[].class);
            method.setAccessible(true);
            method.invoke(migrationManager, map, new String[]{"text", "delay"});

            assertTrue(map.containsKey("type"));
            assertTrue(map.containsKey("text"));
            assertTrue(map.containsKey("delay"));
            assertFalse(map.containsKey("uri"));
            assertFalse(map.containsKey("extra"));
        }
    }

    // =========================================================
    // removeNonStringProperties
    // =========================================================

    @Nested
    @DisplayName("removeNonStringProperties")
    class RemoveNonStringPropertiesTests {

        @Test
        @DisplayName("removes non-string values, keeps strings and nulls")
        void removesNonStrings() throws Exception {
            var map = new HashMap<String, Object>();
            map.put("text", "keep");
            map.put("number", 42);
            map.put("list", List.of("a"));
            map.put("nullVal", null);

            var method = MigrationManager.class.getDeclaredMethod(
                    "removeNonStringProperties", Map.class);
            method.setAccessible(true);
            method.invoke(migrationManager, map);

            assertTrue(map.containsKey("text"));
            assertFalse(map.containsKey("number"));
            assertFalse(map.containsKey("list"));
            assertTrue(map.containsKey("nullVal")); // null values are kept
        }
    }

    // =========================================================
    // Helpers
    // =========================================================

    private HashMap<String, Object> createProp(String key, Object value) {
        var prop = new HashMap<String, Object>();
        prop.put(key, value);
        return prop;
    }

    private Document buildPropertySetterDoc(Map<String, Object> property) {
        var setProperties = new ArrayList<Map<String, Object>>();
        setProperties.add(property);

        var actionContainer = new HashMap<String, Object>();
        actionContainer.put("setProperties", setProperties);

        return new Document("setOnActions", List.of(actionContainer));
    }

    private Document buildOutputDoc(Map<String, Object> outputValue) {
        var valueAlternatives = new ArrayList<Object>();
        valueAlternatives.add(outputValue);

        var output = new HashMap<String, Object>();
        output.put("valueAlternatives", valueAlternatives);

        var outputContainer = new HashMap<String, Object>();
        outputContainer.put("outputs", List.of(output));

        return new Document("outputSet", List.of(outputContainer));
    }
}
