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
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class PrePostUtilsTest {

    /** Mirrors the template data keys used by {@link PrePostUtils}. */
    private static final String KEY_FIELD_DELIMITER = "eddiFieldDelimiter";
    private static final String KEY_ROW_DELIMITER = "eddiRowDelimiter";

    /** A nonce baked into the template text — exactly what must not happen. */
    private static final Pattern NONCE_IN_TEMPLATE = Pattern.compile("eddi(Field|Row)[0-9a-f]{32}");

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
        private ConversationProperties conversationProperties;
        private Map<String, Object> templateData;

        @BeforeEach
        void setupMemory() throws Exception {
            memory = mock(IConversationMemory.class);
            conversationProperties = mock(ConversationProperties.class);
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

            when(jsonSerialization.deserialize("{invalid}")).thenThrow(new IOException("parse error"));

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

    // ==================== buildIterationValues ====================

    @Nested
    @DisplayName("buildIterationValues Tests")
    class BuildIterationValuesTests {

        @Test
        @DisplayName("builds list with filter expression")
        void withFilter() throws Exception {
            stubIterationRender(List.of(List.of("item1"), List.of("item2")));

            List<Object> result = prePostUtils.buildIterationValues("item", "items", "item.active", new HashMap<>());

            assertEquals(List.of("item1", "item2"), result);
        }

        @Test
        @DisplayName("builds list without filter expression")
        void withoutFilter() throws Exception {
            stubIterationRender(List.of(List.of("val")));

            List<Object> result = prePostUtils.buildIterationValues("item", "items", null, new HashMap<>());

            assertEquals(List.of("val"), result);
        }

        @Test
        @DisplayName("nothing rendered — empty list")
        void nothingRendered() throws Exception {
            stubIterationRender(List.of());

            List<Object> result = prePostUtils.buildIterationValues("item", "items", null, new HashMap<>());

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("values containing quotes and newlines survive verbatim")
        void unsafeValuesSurviveVerbatim() throws Exception {
            var unsafe = "he said \"hi\"\nback\\slash";
            stubIterationRender(List.of(List.of(unsafe)));

            List<Object> result = prePostUtils.buildIterationValues("item", "items", null, new HashMap<>());

            assertEquals(List.of(unsafe), result);
        }

        @Test
        @DisplayName("the row delimiter is re-randomised for every invocation")
        void rowDelimiterIsRandomisedPerInvocation() throws Exception {
            stubIterationRender(List.of(List.of("value")));

            prePostUtils.buildIterationValues("item", "items", null, new HashMap<>());
            prePostUtils.buildIterationValues("item", "items", null, new HashMap<>());

            @SuppressWarnings("rawtypes")
            ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
            verify(templatingEngine, times(2)).processTemplate(anyString(), captor.capture());

            var first = String.valueOf(captor.getAllValues().get(0).get(KEY_ROW_DELIMITER));
            var second = String.valueOf(captor.getAllValues().get(1).get(KEY_ROW_DELIMITER));

            assertTrue(first.matches("eddiRow[0-9a-f]{32}"), "delimiter must carry a 128-bit random nonce, was: " + first);
            assertNotEquals(first, second, "a constant nonce makes the delimiter forgeable by upstream content");
        }

        @Test
        @DisplayName("the template text is nonce-free, so it stays a stable compiled-template cache key")
        void templateTextStaysStableAcrossInvocations() throws Exception {
            stubIterationRender(List.of(List.of("value")));

            prePostUtils.buildIterationValues("item", "items", null, new HashMap<>());
            prePostUtils.buildIterationValues("item", "items", null, new HashMap<>());

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(templatingEngine, times(2)).processTemplate(captor.capture(), any());

            assertEquals(captor.getAllValues().get(0), captor.getAllValues().get(1),
                    "a nonce inside the template text makes every execution a compiled-template cache miss");
            assertFalse(NONCE_IN_TEMPLATE.matcher(captor.getAllValues().getFirst()).find(),
                    "no nonce may leak into the template text: " + captor.getAllValues().getFirst());
        }
    }

    /**
     * Stand in for the templating engine: read the per-invocation delimiters back
     * out of the template DATA (the template text is deliberately nonce-free) and
     * emit the given rows with them.
     */
    private void stubIterationRender(List<List<String>> renderedRows) throws Exception {
        when(templatingEngine.processTemplate(anyString(), any())).thenAnswer(invocation -> {
            Map<String, Object> renderData = invocation.getArgument(1);
            String rowDelimiter = String.valueOf(renderData.get(KEY_ROW_DELIMITER));
            String fieldDelimiter = String.valueOf(renderData.get(KEY_FIELD_DELIMITER));

            var rendered = new StringBuilder();
            for (var row : renderedRows) {
                rendered.append(String.join(fieldDelimiter, row)).append(rowDelimiter);
            }
            return rendered.toString();
        });
    }
}
