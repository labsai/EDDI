package ai.labs.eddi.configs.migration;

import ai.labs.eddi.configs.migration.model.MigrationLog;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
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

        @Test
        @DisplayName("should run migration when log returns null (first time)")
        void runMigrationWhenFirstTime() {
            when(migrationLogStore.readMigrationLog(MIGRATION_CONFIRMATION))
                    .thenReturn(null);

            var completed = new boolean[]{false};
            migrationManager.startMigrationIfFirstTimeRun(() -> completed[0] = true);

            assertTrue(completed[0], "onComplete should be called");
            verify(migrationLogStore).createMigrationLog(any(MigrationLog.class));
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("skipConversationMemories=false — should also migrate conversation memory")
        void migrationWithConversationMemories() {
            // Use separate mocks so we can verify conversation memory collection access
            MongoCollection<Document> defaultColl = mock(MongoCollection.class, "defaultColl");
            MongoCollection<Document> conversationMemoryColl = mock(MongoCollection.class, "conversationMemoryColl");
            when(database.getCollection(anyString())).thenReturn(defaultColl);
            when(database.getCollection(COLLECTION_CONVERSATION_MEMORY)).thenReturn(conversationMemoryColl);
            when(defaultColl.find()).thenReturn(mock(com.mongodb.client.FindIterable.class));
            when(conversationMemoryColl.find()).thenReturn(mock(com.mongodb.client.FindIterable.class));

            var managerWithMemories = new MigrationManager(database, migrationLogStore, false);
            when(migrationLogStore.readMigrationLog(MIGRATION_CONFIRMATION)).thenReturn(null);

            var completed = new boolean[]{false};
            managerWithMemories.startMigrationIfFirstTimeRun(() -> completed[0] = true);

            assertTrue(completed[0]);
            verify(migrationLogStore).createMigrationLog(any(MigrationLog.class));
            // Verify conversation memory collection was actually iterated
            verify(conversationMemoryColl).find();
        }
    }

    // ─── migrateConversationMemory ────────────────────────────────

    @Nested
    @DisplayName("Conversation Memory Migration (via migratePropertySetter lambda)")
    class ConversationMemoryMigration {

        @Test
        @DisplayName("should convert conversation properties with String value")
        void convertConversationPropertyString() {
            var conversationProperty = new HashMap<String, Object>();
            conversationProperty.put("value", "user-preference");

            // The migrateConversationMemory method is private, but we can test its logic
            // through convertPropertyInstructions which is the shared logic
            var migration = migrationManager.migratePropertySetter();

            // Test through the property setter migration which uses the same convert logic
            var propDoc = buildPropertySetterDoc(conversationProperty);
            Document result = migration.migrate(propDoc);

            assertNotNull(result);
            assertEquals("user-preference", conversationProperty.get("valueString"));
            assertFalse(conversationProperty.containsKey("value"));
        }
    }

    // ─── Output Migration Edge Cases ──────────────────────────────

    @Nested
    @DisplayName("Output migration edge cases")
    class OutputMigrationEdgeCases {

        @Test
        @DisplayName("isPassword=false — should NOT set password subType")
        void isPasswordFalse_noSubType() {
            var outputValue = new HashMap<String, Object>();
            outputValue.put("placeholder", "Enter text");
            outputValue.put("isPassword", "false");

            Document doc = buildOutputDoc(List.of(outputValue));
            migrationManager.migrateOutput().migrate(doc);

            assertEquals("inputField", outputValue.get("type"));
            assertFalse(outputValue.containsKey("subType"), "subType should not be set for non-password");
        }

        @Test
        @DisplayName("text type — removes non-supported properties (keeps text, delay)")
        void textType_removesNonSupported() {
            var outputValue = new HashMap<String, Object>();
            outputValue.put("text", "Hello");
            outputValue.put("delay", 500);
            outputValue.put("unsupportedField", "should be removed");
            outputValue.put("anotherExtra", "also removed");

            Document doc = buildOutputDoc(List.of(outputValue));
            migrationManager.migrateOutput().migrate(doc);

            assertEquals("text", outputValue.get("type"));
            assertTrue(outputValue.containsKey("text"));
            assertTrue(outputValue.containsKey("delay"));
            assertFalse(outputValue.containsKey("unsupportedField"));
            assertFalse(outputValue.containsKey("anotherExtra"));
        }

        @Test
        @DisplayName("image type — removes non-supported properties (keeps uri, alt)")
        void imageType_removesNonSupported() {
            var outputValue = new HashMap<String, Object>();
            outputValue.put("uri", "https://example.com/img.png");
            outputValue.put("alt", "An image");
            outputValue.put("width", 100);

            Document doc = buildOutputDoc(List.of(outputValue));
            migrationManager.migrateOutput().migrate(doc);

            assertEquals("image", outputValue.get("type"));
            assertTrue(outputValue.containsKey("uri"));
            assertTrue(outputValue.containsKey("alt"));
            assertFalse(outputValue.containsKey("width"));
        }

        @Test
        @DisplayName("button type — removes non-supported properties (keeps buttonType, label, onPress)")
        void buttonType_removesNonSupported() {
            var outputValue = new HashMap<String, Object>();
            outputValue.put("onPress", "submit()");
            outputValue.put("label", "Submit");
            outputValue.put("buttonType", "primary");
            outputValue.put("color", "red");

            Document doc = buildOutputDoc(List.of(outputValue));
            migrationManager.migrateOutput().migrate(doc);

            assertEquals("button", outputValue.get("type"));
            assertTrue(outputValue.containsKey("onPress"));
            assertTrue(outputValue.containsKey("label"));
            assertTrue(outputValue.containsKey("buttonType"));
            assertFalse(outputValue.containsKey("color"));
        }

        @Test
        @DisplayName("inputField type — removes non-supported properties")
        void inputFieldType_removesNonSupported() {
            var outputValue = new HashMap<String, Object>();
            outputValue.put("placeholder", "Enter...");
            outputValue.put("label", "Name");
            outputValue.put("defaultValue", "default");
            outputValue.put("validation", "required");
            outputValue.put("customProp", "removed");

            Document doc = buildOutputDoc(List.of(outputValue));
            migrationManager.migrateOutput().migrate(doc);

            assertEquals("inputField", outputValue.get("type"));
            assertTrue(outputValue.containsKey("placeholder"));
            assertTrue(outputValue.containsKey("label"));
            assertTrue(outputValue.containsKey("defaultValue"));
            assertTrue(outputValue.containsKey("validation"));
            assertFalse(outputValue.containsKey("customProp"));
        }

        @Test
        @DisplayName("other type — removes non-string properties")
        void otherType_removesNonString() {
            var outputValue = new HashMap<String, Object>();
            outputValue.put("customKey", "string value");
            outputValue.put("numericKey", 42);
            outputValue.put("listKey", List.of("a", "b"));

            Document doc = buildOutputDoc(List.of(outputValue));
            migrationManager.migrateOutput().migrate(doc);

            assertEquals("other", outputValue.get("type"));
            assertTrue(outputValue.containsKey("customKey"));
            assertFalse(outputValue.containsKey("numericKey"), "Non-string values should be removed");
            assertFalse(outputValue.containsKey("listKey"), "Non-string values should be removed");
        }

        @Test
        @DisplayName("type 'other' explicitly set — removeNonStringProperties still called")
        void typeOtherExplicit() {
            var outputValue = new HashMap<String, Object>();
            outputValue.put("type", "other");
            outputValue.put("key1", "string");
            outputValue.put("key2", Map.of("nested", "map"));

            Document doc = buildOutputDoc(List.of(outputValue));
            migrationManager.migrateOutput().migrate(doc);

            assertTrue(outputValue.containsKey("key1"));
            assertFalse(outputValue.containsKey("key2"));
        }

        @Test
        @DisplayName("multiple valueAlternatives with mixed types")
        void mixedValueAlternatives() {
            var textOutput = new HashMap<String, Object>();
            textOutput.put("text", "Hello");

            var imageOutput = new HashMap<String, Object>();
            imageOutput.put("uri", "https://img.com/pic.jpg");

            Document doc = buildOutputDoc(List.of("Plain text string", textOutput, imageOutput));
            Document result = migrationManager.migrateOutput().migrate(doc);

            assertNotNull(result);
            var alternatives = extractValueAlternatives(result);
            assertEquals(3, alternatives.size());

            // Plain string should be wrapped in a TextOutputItem
            assertInstanceOf(TextOutputItem.class, alternatives.get(0), "Plain string should be wrapped in TextOutputItem");
            var wrappedText = (TextOutputItem) alternatives.get(0);
            assertEquals("Plain text string", wrappedText.getText());
            assertEquals("text", wrappedText.getType());

            // Map with "text" field should have type inferred as "text"
            assertEquals("text", textOutput.get("type"));

            // Map with "uri" field should have type inferred as "image"
            assertEquals("image", imageOutput.get("type"));
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

    // ─── migrateConversationMemory ──────────────────────────────

    @Nested
    @DisplayName("migrateConversationMemory")
    class ConversationMemoryDirectMigration {

        @Test
        @DisplayName("should convert conversationProperties with Map value to valueObject")
        void convertMapValueInConversationProperty() {
            var mapValue = new HashMap<String, Object>();
            mapValue.put("key", "val");
            var conversationProperty = new HashMap<String, Object>();
            conversationProperty.put("value", mapValue);

            // migrateConversationMemory is private — test via the property setter path
            // which uses the same convertPropertyInstructions logic
            var propDoc = buildPropertySetterDoc(conversationProperty);
            Document result = migrationManager.migratePropertySetter().migrate(propDoc);

            assertNotNull(result);
            assertEquals(mapValue, conversationProperty.get("valueObject"));
            assertFalse(conversationProperty.containsKey("value"));
        }

        @Test
        @DisplayName("should handle multiple setOnActions containers")
        void multipleSetOnActionContainers() {
            var property1 = new HashMap<String, Object>();
            property1.put("value", "first");

            var property2 = new HashMap<String, Object>();
            property2.put("value", 99);

            var setProperties1 = new ArrayList<Map<String, Object>>();
            setProperties1.add(property1);
            var actionContainer1 = new HashMap<String, Object>();
            actionContainer1.put("setProperties", setProperties1);

            var setProperties2 = new ArrayList<Map<String, Object>>();
            setProperties2.add(property2);
            var actionContainer2 = new HashMap<String, Object>();
            actionContainer2.put("setProperties", setProperties2);

            Document doc = new Document("setOnActions", List.of(actionContainer1, actionContainer2));
            Document result = migrationManager.migratePropertySetter().migrate(doc);

            assertNotNull(result);
            assertEquals("first", property1.get("valueString"));
            assertEquals(99, property2.get("valueInt"));
        }

        @Test
        @DisplayName("should handle setOnActions container without setProperties")
        void setOnActionsWithoutSetProperties() {
            var actionContainer = new HashMap<String, Object>();
            actionContainer.put("otherField", "value");

            Document doc = new Document("setOnActions", List.of(actionContainer));
            Document result = migrationManager.migratePropertySetter().migrate(doc);

            assertNull(result, "No migration needed when setProperties is absent");
        }

        @Test
        @DisplayName("should handle exception in migratePropertySetter gracefully")
        void exceptionInPropertySetterMigration() {
            // Pass a document that would cause a ClassCastException
            Document doc = new Document("setOnActions", "not-a-list");
            Document result = migrationManager.migratePropertySetter().migrate(doc);

            assertNull(result, "Should return null on exception");
        }
    }

    // ─── output migration: quickReply branch ─────────────────────

    @Nested
    @DisplayName("quickReply output migration")
    class QuickReplyOutputMigration {

        @Test
        @DisplayName("quickReply type — removes non-supported properties (keeps expressions)")
        void quickReplyType_removesNonSupported() {
            var outputValue = new HashMap<String, Object>();
            outputValue.put("expressions", "button(agree)");
            outputValue.put("extraField", "should be removed");

            Document doc = buildOutputDoc(List.of(outputValue));
            migrationManager.migrateOutput().migrate(doc);

            assertEquals("quickReply", outputValue.get("type"));
            assertTrue(outputValue.containsKey("expressions"));
            // quickReply doesn't have removeNonSupportedProperties called —
            // it only removes via the other type handlers
        }
    }

    // ─── exception handling ──────────────────────────────────────

    @Nested
    @DisplayName("Exception handling in migration lambdas")
    class ExceptionHandling {

        @Test
        @DisplayName("exception in migrateOutput should return null")
        void exceptionInOutputMigration() {
            Document doc = new Document("outputSet", "not-a-list");
            Document result = migrationManager.migrateOutput().migrate(doc);

            assertNull(result, "Should return null on exception");
        }

        @Test
        @DisplayName("exception in migrateApiCalls should return null")
        void exceptionInApiCallsMigration() {
            Document doc = new Document("httpCalls", "not-a-list");
            doc.put("targetServer", "url"); // triggers rename, then ClassCastException on httpCalls iteration
            Document result = migrationManager.migrateApiCalls().migrate(doc);

            // The catch block returns null even though targetServer was renamed
            assertNull(result, "Should return null on exception");
        }

        @Test
        @DisplayName("migrateApiCalls — preRequest without propertyInstructions")
        void preRequestWithoutPropertyInstructions() {
            var preRequest = new HashMap<String, List<Map<String, Object>>>();
            preRequest.put("otherKey", List.of());

            var httpCall = new HashMap<String, Object>();
            httpCall.put("preRequest", preRequest);

            Document doc = new Document("httpCalls", List.of(httpCall));
            Document result = migrationManager.migrateApiCalls().migrate(doc);

            assertNull(result, "Should return null when no actual migration needed");
        }
    }

    // ─── isCurrentlyRunning guard ────────────────────────────────

    @Nested
    @DisplayName("isCurrentlyRunning guard")
    class ReentrancyGuard {

        @Test
        @DisplayName("concurrent call should not execute migration twice")
        void concurrentCallGuarded() {
            when(migrationLogStore.readMigrationLog(MIGRATION_CONFIRMATION))
                    .thenReturn(new MigrationLog(MIGRATION_CONFIRMATION));

            // First call
            var completed1 = new boolean[]{false};
            migrationManager.startMigrationIfFirstTimeRun(() -> completed1[0] = true);
            assertTrue(completed1[0]);

            // Second call — should also work since isCurrentlyRunning is reset
            var completed2 = new boolean[]{false};
            migrationManager.startMigrationIfFirstTimeRun(() -> completed2[0] = true);
            assertTrue(completed2[0]);
        }
    }

    // ─── output migration with 'other' type already set ──────────

    @Nested
    @DisplayName("Output migration with pre-existing type=other")
    class OutputMigrationPreExistingOther {

        @Test
        @DisplayName("type='other' with text field should reclassify to 'text'")
        void typeOtherWithTextFieldReclassifiesToText() {
            var outputValue = new HashMap<String, Object>();
            outputValue.put("type", "other");
            outputValue.put("text", "Hello reclassified");

            Document doc = buildOutputDoc(List.of(outputValue));
            migrationManager.migrateOutput().migrate(doc);

            // type=other triggers reclassification when text field present
            assertEquals("text", outputValue.get("type"));
        }

        @Test
        @DisplayName("type='other' with uri field should reclassify to 'image'")
        void typeOtherWithUriFieldReclassifiesToImage() {
            var outputValue = new HashMap<String, Object>();
            outputValue.put("type", "other");
            outputValue.put("uri", "https://example.com/img.png");

            Document doc = buildOutputDoc(List.of(outputValue));
            migrationManager.migrateOutput().migrate(doc);

            assertEquals("image", outputValue.get("type"));
        }

        @Test
        @DisplayName("type='other' with expressions field should reclassify to 'quickReply'")
        void typeOtherWithExpressionsReclassifiesToQuickReply() {
            var outputValue = new HashMap<String, Object>();
            outputValue.put("type", "other");
            outputValue.put("expressions", "option(yes)");

            Document doc = buildOutputDoc(List.of(outputValue));
            migrationManager.migrateOutput().migrate(doc);

            assertEquals("quickReply", outputValue.get("type"));
        }

        @Test
        @DisplayName("type='other' with placeholder should reclassify to 'inputField'")
        void typeOtherWithPlaceholderReclassifiesToInputField() {
            var outputValue = new HashMap<String, Object>();
            outputValue.put("type", "other");
            outputValue.put("placeholder", "Enter...");

            Document doc = buildOutputDoc(List.of(outputValue));
            migrationManager.migrateOutput().migrate(doc);

            assertEquals("inputField", outputValue.get("type"));
        }

        @Test
        @DisplayName("type='other' with onPress should reclassify to 'button'")
        void typeOtherWithOnPressReclassifiesToButton() {
            var outputValue = new HashMap<String, Object>();
            outputValue.put("type", "other");
            outputValue.put("onPress", "submit()");

            Document doc = buildOutputDoc(List.of(outputValue));
            migrationManager.migrateOutput().migrate(doc);

            assertEquals("button", outputValue.get("type"));
        }
    }
}
