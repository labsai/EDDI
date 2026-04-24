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
}
