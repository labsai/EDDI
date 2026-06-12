/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import ai.labs.eddi.configs.apicalls.model.HttpCodeValidator;
import ai.labs.eddi.configs.apicalls.model.PostResponse;
import ai.labs.eddi.configs.apicalls.model.PreRequest;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.PropertyInstruction;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.model.ConversationProperties;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class PrePostUtilsTest {

    private PrePostUtils prePostUtils;
    private IJsonSerialization jsonSerialization;
    private IMemoryItemConverter memoryItemConverter;
    private ITemplatingEngine templatingEngine;
    private IDataFactory dataFactory;

    @BeforeEach
    void setUp() {
        jsonSerialization = mock(IJsonSerialization.class);
        memoryItemConverter = mock(IMemoryItemConverter.class);
        templatingEngine = mock(ITemplatingEngine.class);
        dataFactory = mock(IDataFactory.class);
        prePostUtils = new PrePostUtils(jsonSerialization, memoryItemConverter, templatingEngine, dataFactory);
    }

    // ==================== verifyHttpCode ====================

    @Nested
    @DisplayName("verifyHttpCode Tests")
    class VerifyHttpCodeTests {

        @Test
        void verifyHttpCode_nullValidator_defaultAllows200() {
            assertTrue(prePostUtils.verifyHttpCode(null, 200));
        }

        @Test
        void verifyHttpCode_nullValidator_defaultAllows201() {
            assertTrue(prePostUtils.verifyHttpCode(null, 201));
        }

        @Test
        void verifyHttpCode_nullValidator_defaultRejects204() {
            assertFalse(prePostUtils.verifyHttpCode(null, 204));
        }

        @Test
        void verifyHttpCode_nullValidator_defaultRejects500() {
            assertFalse(prePostUtils.verifyHttpCode(null, 500));
        }

        @Test
        void verifyHttpCode_customRunOnCodes() {
            var validator = new HttpCodeValidator(List.of(200, 404), List.of());
            assertTrue(prePostUtils.verifyHttpCode(validator, 200));
            assertTrue(prePostUtils.verifyHttpCode(validator, 404));
            assertFalse(prePostUtils.verifyHttpCode(validator, 500));
        }

        @Test
        void verifyHttpCode_skipOverridesRun() {
            var validator = new HttpCodeValidator(List.of(200, 201, 202), List.of(201));
            assertTrue(prePostUtils.verifyHttpCode(validator, 200));
            assertFalse(prePostUtils.verifyHttpCode(validator, 201));
            assertTrue(prePostUtils.verifyHttpCode(validator, 202));
        }

        @Test
        void verifyHttpCode_nullRunOnCodes_usesDefault() {
            var validator = new HttpCodeValidator();
            validator.setRunOnHttpCode(null);
            validator.setSkipOnHttpCode(List.of());
            assertTrue(prePostUtils.verifyHttpCode(validator, 200));
        }

        @Test
        void verifyHttpCode_nullSkipOnCodes_usesDefault() {
            var validator = new HttpCodeValidator();
            validator.setRunOnHttpCode(List.of(200));
            validator.setSkipOnHttpCode(null);
            assertTrue(prePostUtils.verifyHttpCode(validator, 200));
        }

        @Test
        void verifyHttpCode_codeNotInRunList_returnsFalse() {
            var validator = new HttpCodeValidator(List.of(200), List.of());
            assertFalse(prePostUtils.verifyHttpCode(validator, 500));
            assertFalse(prePostUtils.verifyHttpCode(validator, 404));
        }
    }

    // ==================== executePreRequestPropertyInstructions
    // ====================

    @Nested
    @DisplayName("executePreRequestPropertyInstructions Tests")
    class PreRequestTests {

        @Test
        @DisplayName("Null preRequest returns templateDataObjects unchanged")
        void nullPreRequest_returnsUnchanged() throws Exception {
            var templateData = new HashMap<String, Object>();
            templateData.put("key", "value");

            var result = prePostUtils.executePreRequestPropertyInstructions(
                    mock(IConversationMemory.class), templateData, null);

            assertSame(templateData, result);
        }

        @Test
        @DisplayName("PreRequest with null instructions returns templateDataObjects unchanged")
        void preRequestWithNullInstructions_returnsUnchanged() throws Exception {
            var preRequest = new PreRequest();
            preRequest.setPropertyInstructions(null);

            var templateData = new HashMap<String, Object>();
            var result = prePostUtils.executePreRequestPropertyInstructions(
                    mock(IConversationMemory.class), templateData, preRequest);

            assertSame(templateData, result);
        }

        @Test
        @DisplayName("PreRequest with instructions executes and refreshes template data")
        void preRequestWithInstructions_executesAndRefreshes() throws Exception {
            var memory = mock(IConversationMemory.class);
            var properties = mock(ConversationProperties.class);
            when(memory.getConversationProperties()).thenReturn(properties);

            var instruction = new PropertyInstruction();
            instruction.setName("testProp");
            instruction.setFromObjectPath("");
            instruction.setValueString("testValue");
            instruction.setScope(Property.Scope.conversation);

            var preRequest = new PreRequest();
            preRequest.setPropertyInstructions(List.of(instruction));

            var refreshedData = new HashMap<String, Object>();
            refreshedData.put("refreshed", true);
            when(memoryItemConverter.convert(memory)).thenReturn(refreshedData);
            when(templatingEngine.processTemplate(anyString(), any())).thenAnswer(i -> i.getArgument(0));

            var result = prePostUtils.executePreRequestPropertyInstructions(
                    memory, new HashMap<>(), preRequest);

            assertSame(refreshedData, result);
            verify(memoryItemConverter).convert(memory);
        }
    }

    // ==================== createMemoryEntry ====================

    @Nested
    @DisplayName("createMemoryEntry Tests")
    class CreateMemoryEntryTests {

        @Test
        @DisplayName("Creates data and stores it in current step")
        void createsDataAndStores() {
            var currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            var mockData = mock(IData.class);
            when(dataFactory.createData(anyString(), any())).thenReturn(mockData);

            prePostUtils.createMemoryEntry(currentStep, "responseBody", "weather", "httpCalls");

            verify(dataFactory).createData("httpCalls:weather", "responseBody");
            verify(currentStep).storeData(mockData);
            verify(currentStep).addConversationOutputMap(eq("httpCalls"), any(Map.class));
        }

        @Test
        @DisplayName("Output map contains responseObjectName as key")
        void outputMapContainsCorrectKey() {
            var currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(dataFactory.createData(anyString(), any())).thenReturn(mock(IData.class));

            var responseObj = Map.of("temp", 25);
            prePostUtils.createMemoryEntry(currentStep, responseObj, "weatherData", "api");

            verify(currentStep).addConversationOutputMap(eq("api"), argThat(map -> {
                Map<String, Object> m = (Map<String, Object>) map;
                return m.containsKey("weatherData") && m.get("weatherData").equals(responseObj);
            }));
        }
    }

    // ==================== runPostResponse ====================

    @Nested
    @DisplayName("runPostResponse Tests")
    class RunPostResponseTests {

        @Test
        @DisplayName("Null postResponse does nothing")
        void nullPostResponse_noOp() throws Exception {
            var memory = mock(IConversationMemory.class);
            assertDoesNotThrow(() -> prePostUtils.runPostResponse(
                    memory, null, new HashMap<>(), 200, false));
            verifyNoInteractions(memory);
        }

        @Test
        @DisplayName("PostResponse with empty instructions does nothing")
        void emptyPostResponse_noOp() throws Exception {
            var memory = mock(IConversationMemory.class);
            var postResponse = new PostResponse();

            assertDoesNotThrow(() -> prePostUtils.runPostResponse(
                    memory, postResponse, new HashMap<>(), 200, false));
        }
    }

    // ==================== templateValues ====================

    @Nested
    @DisplayName("templateValues Tests")
    class TemplateValuesTests {

        @Test
        @DisplayName("Delegates to templating engine")
        void delegatesToEngine() throws Exception {
            when(templatingEngine.processTemplate("Hello {{name}}", Map.of("name", "World")))
                    .thenReturn("Hello World");

            String result = prePostUtils.templateValues("Hello {{name}}", Map.of("name", "World"));

            assertEquals("Hello World", result);
            verify(templatingEngine).processTemplate("Hello {{name}}", Map.of("name", "World"));
        }
    }

    // ==================== executePropertyInstructions — property types
    // ====================

    @Nested
    @DisplayName("executePropertyInstructions — various property value types")
    class PropertyTypeTests {

        private IConversationMemory memory;
        private ai.labs.eddi.engine.memory.model.ConversationProperties conversationProperties;
        private Map<String, Object> templateData;

        @BeforeEach
        void setupMemory() throws Exception {
            memory = mock(IConversationMemory.class);
            conversationProperties = mock(ai.labs.eddi.engine.memory.model.ConversationProperties.class);
            when(memory.getConversationProperties()).thenReturn(conversationProperties);
            when(conversationProperties.toMap()).thenReturn(new HashMap<>());
            templateData = new HashMap<>();
            when(templatingEngine.processTemplate(anyString(), any())).thenAnswer(i -> i.getArgument(0));
        }

        @Test
        @DisplayName("null propertyInstructions does nothing")
        void nullInstructions() throws Exception {
            prePostUtils.executePropertyInstructions(null, 200, false, memory, templateData);
            verifyNoInteractions(memory);
        }

        @Test
        @DisplayName("String property value stores as String")
        void stringProperty() throws Exception {
            var instruction = new PropertyInstruction();
            instruction.setName("myProp");
            instruction.setFromObjectPath("");
            instruction.setValueString("hello");
            instruction.setScope(Property.Scope.conversation);

            prePostUtils.executePropertyInstructions(List.of(instruction), 0, false, memory, templateData);

            verify(conversationProperties).put(eq("myProp"), any(Property.class));
        }

        @Test
        @DisplayName("validationError=true with runOnValidationError=true executes instruction")
        void validationError_runsWhenFlagged() throws Exception {
            var instruction = new PropertyInstruction();
            instruction.setName("errorProp");
            instruction.setFromObjectPath("");
            instruction.setValueString("error_value");
            instruction.setScope(Property.Scope.conversation);
            instruction.setRunOnValidationError(true);

            prePostUtils.executePropertyInstructions(List.of(instruction), 404, true, memory, templateData);

            verify(conversationProperties).put(eq("errorProp"), any(Property.class));
        }

        @Test
        @DisplayName("convertToObject converts JSON string to Map")
        void convertToObject() throws Exception {
            var instruction = new PropertyInstruction();
            instruction.setName("jsonProp");
            instruction.setFromObjectPath("");
            instruction.setValueString("{\"key\":\"val\"}");
            instruction.setScope(Property.Scope.conversation);
            instruction.setConvertToObject(true);

            Map<String, Object> deserialized = Map.of("key", "val");
            when(jsonSerialization.deserialize("{\"key\":\"val\"}")).thenReturn(deserialized);

            prePostUtils.executePropertyInstructions(List.of(instruction), 0, false, memory, templateData);

            verify(jsonSerialization).deserialize("{\"key\":\"val\"}");
            verify(conversationProperties).put(eq("jsonProp"), any(Property.class));
        }

        @Test
        @DisplayName("convertToObject with invalid JSON falls back to string")
        void convertToObject_invalidJson() throws Exception {
            var instruction = new PropertyInstruction();
            instruction.setName("jsonProp");
            instruction.setFromObjectPath("");
            instruction.setValueString("{invalid}");
            instruction.setScope(Property.Scope.conversation);
            instruction.setConvertToObject(true);

            when(jsonSerialization.deserialize("{invalid}")).thenThrow(new java.io.IOException("parse error"));

            prePostUtils.executePropertyInstructions(List.of(instruction), 0, false, memory, templateData);

            verify(conversationProperties).put(eq("jsonProp"), any(Property.class));
        }

        @Test
        @DisplayName("property value from fromObjectPath using PathNavigator")
        void fromObjectPath() throws Exception {
            var instruction = new PropertyInstruction();
            instruction.setName("pathProp");
            instruction.setFromObjectPath("context.result");
            instruction.setScope(Property.Scope.conversation);

            templateData.put("context", Map.of("result", "pathValue"));

            prePostUtils.executePropertyInstructions(List.of(instruction), 0, false, memory, templateData);

            verify(conversationProperties).put(eq("pathProp"), any(Property.class));
        }

        @Test
        @DisplayName("empty propertyValue (not String) stores empty string")
        void emptyPropertyValue() throws Exception {
            var instruction = new PropertyInstruction();
            instruction.setName("emptyProp");
            instruction.setFromObjectPath("nonexistent.path");
            instruction.setScope(Property.Scope.conversation);

            prePostUtils.executePropertyInstructions(List.of(instruction), 0, false, memory, templateData);

            verify(conversationProperties).put(eq("emptyProp"), any(Property.class));
        }
    }

    // ==================== buildListFromJson ====================

    @Nested
    @DisplayName("buildListFromJson Tests")
    class BuildListFromJsonTests {

        @Test
        @DisplayName("builds list with filter expression")
        void withFilter() throws Exception {
            when(templatingEngine.processTemplate(anyString(), anyMap()))
                    .thenReturn("[\"item1\",\"item2\"]");
            when(jsonSerialization.deserialize(anyString(), eq(List.class)))
                    .thenReturn(List.of("item1", "item2"));

            List<Object> result = prePostUtils.buildListFromJson(
                    "item", "items", "item.active", null, new HashMap<>());

            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("builds list without filter expression")
        void withoutFilter() throws Exception {
            when(templatingEngine.processTemplate(anyString(), anyMap()))
                    .thenReturn("[\"val\"]");
            when(jsonSerialization.deserialize(anyString(), eq(List.class)))
                    .thenReturn(List.of("val"));

            List<Object> result = prePostUtils.buildListFromJson(
                    "item", "items", null, null, new HashMap<>());

            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("builds list with custom iteration value")
        void withIterationValue() throws Exception {
            when(templatingEngine.processTemplate(anyString(), anyMap()))
                    .thenReturn("[{\"name\":\"test\"}]");
            when(jsonSerialization.deserialize(anyString(), eq(List.class)))
                    .thenReturn(List.of(Map.of("name", "test")));

            List<Object> result = prePostUtils.buildListFromJson(
                    "item", "items", null, "{\"name\":\"{item.name}\"}", new HashMap<>());

            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("builds list with null iteration value uses default template")
        void withNullIterationValue() throws Exception {
            when(templatingEngine.processTemplate(anyString(), anyMap()))
                    .thenReturn("[\"defaultVal\"]");
            when(jsonSerialization.deserialize(anyString(), eq(List.class)))
                    .thenReturn(List.of("defaultVal"));

            List<Object> result = prePostUtils.buildListFromJson(
                    "obj", "objects", null, "", new HashMap<>());

            assertEquals(1, result.size());
        }
    }
}
