package ai.labs.eddi.configs.migration;

import ai.labs.eddi.configs.migration.model.MigrationLog;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.*;

import static ai.labs.eddi.configs.migration.MigrationManager.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Unit tests for {@link MigrationManager} migration lambda factories. Tests the
 * pure document transformation logic without requiring MongoDB.
 */
class MigrationManagerTest {

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

    // ─── migratePropertySetter ───────────────────────────────────

    @Nested
    @DisplayName("migratePropertySetter")
    class PropertySetterMigration {

        @Test
        @DisplayName("should convert String 'value' to 'valueString'")
        void stringValueToValueString() {
            var property = new HashMap<String, Object>();
            property.put("value", "hello");

            Document doc = buildPropertySetterDoc(property);
            Document result = migrationManager.migratePropertySetter().migrate(doc);

            assertNotNull(result, "Document should be migrated");
            var props = extractFirstSetProperty(result);
            assertEquals("hello", props.get("valueString"));
            assertFalse(props.containsKey("value"), "Old 'value' key should be removed");
        }

        @Test
        @DisplayName("should convert Map 'value' to 'valueObject'")
        void mapValueToValueObject() {
            var mapVal = new HashMap<String, Object>();
            mapVal.put("nested", "data");
            var property = new HashMap<String, Object>();
            property.put("value", mapVal);

            Document doc = buildPropertySetterDoc(property);
            Document result = migrationManager.migratePropertySetter().migrate(doc);

            assertNotNull(result);
            var props = extractFirstSetProperty(result);
            assertEquals(mapVal, props.get("valueObject"));
            assertFalse(props.containsKey("value"));
        }

        @Test
        @DisplayName("should convert Integer 'value' to 'valueInt'")
        void intValueToValueInt() {
            var property = new HashMap<String, Object>();
            property.put("value", 42);

            Document doc = buildPropertySetterDoc(property);
            Document result = migrationManager.migratePropertySetter().migrate(doc);

            assertNotNull(result);
            assertEquals(42, extractFirstSetProperty(result).get("valueInt"));
        }

        @Test
        @DisplayName("should convert Float 'value' to 'valueFloat'")
        void floatValueToValueFloat() {
            var property = new HashMap<String, Object>();
            property.put("value", 3.14f);

            Document doc = buildPropertySetterDoc(property);
            Document result = migrationManager.migratePropertySetter().migrate(doc);

            assertNotNull(result);
            assertEquals(3.14f, extractFirstSetProperty(result).get("valueFloat"));
        }

        @Test
        @DisplayName("should convert null 'value' to null 'valueString'")
        void nullValueToNullValueString() {
            var property = new HashMap<String, Object>();
            property.put("value", null);

            Document doc = buildPropertySetterDoc(property);
            Document result = migrationManager.migratePropertySetter().migrate(doc);

            assertNotNull(result);
            var props = extractFirstSetProperty(result);
            assertTrue(props.containsKey("valueString"));
            assertNull(props.get("valueString"));
        }

        @Test
        @DisplayName("should replace UUID Thymeleaf expression with Qute")
        void uuidReplacement() {
            var property = new HashMap<String, Object>();
            property.put("value", "[[${@java.util.UUID@randomUUID()}]]");

            Document doc = buildPropertySetterDoc(property);
            Document result = migrationManager.migratePropertySetter().migrate(doc);

            assertNotNull(result);
            assertEquals("{uuidUtils:generateUUID()}", extractFirstSetProperty(result).get("valueString"));
        }

        @Test
        @DisplayName("should return null if no migration needed")
        void noMigrationNeeded() {
            var property = new HashMap<String, Object>();
            property.put("valueString", "already migrated");
            // No "value" key = nothing to migrate

            Document doc = buildPropertySetterDoc(property);
            Document result = migrationManager.migratePropertySetter().migrate(doc);

            assertNull(result, "Should return null when no migration needed");
        }

        @Test
        @DisplayName("should return null for document without setOnActions")
        void noSetOnActions() {
            Document doc = new Document();
            assertNull(migrationManager.migratePropertySetter().migrate(doc));
        }
    }

    // ─── migrateApiCalls ─────────────────────────────────────────

    @Nested
    @DisplayName("migrateApiCalls")
    class ApiCallsMigration {

        @Test
        @DisplayName("should rename 'targetServer' to 'targetServerUrl'")
        void renameTargetServer() {
            Document doc = new Document("targetServer", "https://api.example.com");

            Document result = migrationManager.migrateApiCalls().migrate(doc);

            assertNotNull(result);
            assertEquals("https://api.example.com", result.get("targetServerUrl"));
            assertFalse(result.containsKey("targetServer"));
        }

        @Test
        @DisplayName("should rename 'targetServerUri' to 'targetServerUrl'")
        void renameTargetServerUri() {
            Document doc = new Document("targetServerUri", "https://api2.example.com");

            Document result = migrationManager.migrateApiCalls().migrate(doc);

            assertNotNull(result);
            assertEquals("https://api2.example.com", result.get("targetServerUrl"));
            assertFalse(result.containsKey("targetServerUri"));
        }

        @Test
        @DisplayName("should migrate preRequest property instructions")
        void migratePreRequestProperties() {
            var propInstruction = new HashMap<String, Object>();
            propInstruction.put("value", "some-value");

            var preRequest = new HashMap<String, List<Map<String, Object>>>();
            preRequest.put("propertyInstructions", List.of(propInstruction));

            var httpCall = new HashMap<String, Object>();
            httpCall.put("preRequest", preRequest);

            Document doc = new Document("httpCalls", List.of(httpCall));
            Document result = migrationManager.migrateApiCalls().migrate(doc);

            assertNotNull(result);
            assertEquals("some-value", propInstruction.get("valueString"));
        }

        @Test
        @DisplayName("should migrate postResponse property instructions")
        void migratePostResponseProperties() {
            var propInstruction = new HashMap<String, Object>();
            propInstruction.put("value", "response-val");

            var postResponse = new HashMap<String, List<Map<String, Object>>>();
            postResponse.put("propertyInstructions", List.of(propInstruction));

            var httpCall = new HashMap<String, Object>();
            httpCall.put("postResponse", postResponse);

            Document doc = new Document("httpCalls", List.of(httpCall));
            Document result = migrationManager.migrateApiCalls().migrate(doc);

            assertNotNull(result);
            assertEquals("response-val", propInstruction.get("valueString"));
        }

        @Test
        @DisplayName("should return null when document needs no migration")
        void noMigrationNeeded() {
            Document doc = new Document("targetServerUrl", "https://already.migrated.com");
            assertNull(migrationManager.migrateApiCalls().migrate(doc));
        }
    }

    // ─── migrateOutput ───────────────────────────────────────────

    @Nested
    @DisplayName("migrateOutput")
    class OutputMigration {

        @Test
        @DisplayName("should convert String valueAlternative to TextOutputItem")
        void stringToTextOutputItem() {
            Document doc = buildOutputDoc(List.of("Hello plain text"));

            Document result = migrationManager.migrateOutput().migrate(doc);

            assertNotNull(result, "Document should be migrated");
            var alternatives = extractValueAlternatives(result);
            assertFalse(alternatives.isEmpty());
            // Should be wrapped in a TextOutputItem (not a raw String)
            assertFalse(alternatives.get(0) instanceof String, "Should be wrapped in TextOutputItem");
        }

        @Test
        @DisplayName("should infer 'text' type when text field present and type is null")
        void inferTextType() {
            var outputValue = new HashMap<String, Object>();
            outputValue.put("text", "Hello structured");

            Document doc = buildOutputDoc(List.of(outputValue));
            migrationManager.migrateOutput().migrate(doc);

            assertEquals("text", outputValue.get("type"));
        }

        @Test
        @DisplayName("should infer 'image' type when uri field present")
        void inferImageType() {
            var outputValue = new HashMap<String, Object>();
            outputValue.put("uri", "https://example.com/image.png");

            Document doc = buildOutputDoc(List.of(outputValue));
            migrationManager.migrateOutput().migrate(doc);

            assertEquals("image", outputValue.get("type"));
        }

        @Test
        @DisplayName("should infer 'quickReply' type when expressions field present")
        void inferQuickReplyType() {
            var outputValue = new HashMap<String, Object>();
            outputValue.put("expressions", "button(agree)");

            Document doc = buildOutputDoc(List.of(outputValue));
            migrationManager.migrateOutput().migrate(doc);

            assertEquals("quickReply", outputValue.get("type"));
        }

        @Test
        @DisplayName("should infer 'inputField' type when placeholder field present")
        void inferInputFieldType() {
            var outputValue = new HashMap<String, Object>();
            outputValue.put("placeholder", "Enter name...");

            Document doc = buildOutputDoc(List.of(outputValue));
            migrationManager.migrateOutput().migrate(doc);

            assertEquals("inputField", outputValue.get("type"));
        }

        @Test
        @DisplayName("should detect password subType from isPassword field")
        void passwordSubType() {
            var outputValue = new HashMap<String, Object>();
            outputValue.put("placeholder", "Enter password");
            outputValue.put("isPassword", "true");

            Document doc = buildOutputDoc(List.of(outputValue));
            migrationManager.migrateOutput().migrate(doc);

            assertEquals("inputField", outputValue.get("type"));
            assertEquals("password", outputValue.get("subType"));
        }

        @Test
        @DisplayName("should infer 'button' type when onPress field present")
        void inferButtonType() {
            var outputValue = new HashMap<String, Object>();
            outputValue.put("onPress", "submit()");

            Document doc = buildOutputDoc(List.of(outputValue));
            migrationManager.migrateOutput().migrate(doc);

            assertEquals("button", outputValue.get("type"));
        }

        @Test
        @DisplayName("should set 'other' type when no recognized field matches")
        void fallbackToOtherType() {
            var outputValue = new HashMap<String, Object>();
            outputValue.put("unknownField", "some data");

            Document doc = buildOutputDoc(List.of(outputValue));
            migrationManager.migrateOutput().migrate(doc);

            assertEquals("other", outputValue.get("type"));
        }

        @Test
        @DisplayName("should skip already-typed output values")
        void skipAlreadyTyped() {
            var outputValue = new HashMap<String, Object>();
            outputValue.put("type", "text");
            outputValue.put("text", "Already typed");

            Document doc = buildOutputDoc(List.of(outputValue));
            migrationManager.migrateOutput().migrate(doc);

            // removeNonSupportedProperties is still called but no type conversion
            // The document still gets processed (type is reset in the outer output)
            // so result might not be null, but the type should remain "text"
            assertEquals("text", outputValue.get("type"));
        }

        @Test
        @DisplayName("should return null for document without outputSet")
        void noOutputSet() {
            Document doc = new Document("someField", "someValue");
            assertNull(migrationManager.migrateOutput().migrate(doc));
        }
    }

    // ─── startMigrationIfFirstTimeRun lifecycle ─────────────────

    @Nested
    @DisplayName("startMigrationIfFirstTimeRun")
    class MigrationLifecycle {

        @Test
        @DisplayName("should skip migration when already completed")
        void skipWhenAlreadyMigrated() {
            when(migrationLogStore.readMigrationLog(MIGRATION_CONFIRMATION))
                    .thenReturn(new MigrationLog(MIGRATION_CONFIRMATION));

            var completed = new boolean[]{false};
            migrationManager.startMigrationIfFirstTimeRun(() -> completed[0] = true);

            assertTrue(completed[0], "onComplete should still be called");
            verify(migrationLogStore, never()).createMigrationLog(any());
        }
    }

    // ─── Test Helpers ────────────────────────────────────────────

    private Document buildPropertySetterDoc(Map<String, Object> property) {
        var setProperties = new ArrayList<Map<String, Object>>();
        setProperties.add(property);

        var actionContainer = new HashMap<String, Object>();
        actionContainer.put("setProperties", setProperties);

        return new Document("setOnActions", List.of(actionContainer));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractFirstSetProperty(Document doc) {
        var setOnActions = (List<Map<String, Object>>) doc.get("setOnActions");
        var setProperties = (List<Map<String, Object>>) setOnActions.get(0).get("setProperties");
        return setProperties.get(0);
    }

    private Document buildOutputDoc(List<Object> valueAlternatives) {
        var output = new HashMap<String, Object>();
        output.put("valueAlternatives", new ArrayList<>(valueAlternatives));

        var outputContainer = new HashMap<String, Object>();
        outputContainer.put("outputs", List.of(output));

        return new Document("outputSet", List.of(outputContainer));
    }

    @SuppressWarnings("unchecked")
    private List<Object> extractValueAlternatives(Document doc) {
        var outputSet = (List<Map<String, Object>>) doc.get("outputSet");
        var outputs = (List<Map<String, Object>>) outputSet.get(0).get("outputs");
        return (List<Object>) outputs.get(0).get("valueAlternatives");
    }
}
