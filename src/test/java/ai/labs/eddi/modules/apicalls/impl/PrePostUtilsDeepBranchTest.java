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

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("PrePostUtils — Deep Branch Coverage")
@SuppressWarnings("unchecked")
class PrePostUtilsDeepBranchTest {

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

    // ─── executePreRequestPropertyInstructions ──────────────────────────

    @Nested
    @DisplayName("executePreRequestPropertyInstructions")
    class PreRequestTests {

        @Test
        @DisplayName("null preRequest — returns templateData unchanged")
        void nullPreRequest() throws Exception {
            Map<String, Object> templateData = Map.of("key", "val");
            Map<String, Object> result = prePostUtils.executePreRequestPropertyInstructions(
                    mock(IConversationMemory.class), templateData, null);
            assertSame(templateData, result);
        }

        @Test
        @DisplayName("preRequest with null propertyInstructions — returns templateData unchanged")
        void nullInstructions() throws Exception {
            PreRequest preRequest = new PreRequest();
            preRequest.setPropertyInstructions(null);
            Map<String, Object> templateData = Map.of("key", "val");
            Map<String, Object> result = prePostUtils.executePreRequestPropertyInstructions(
                    mock(IConversationMemory.class), templateData, preRequest);
            assertSame(templateData, result);
        }

        @Test
        @DisplayName("preRequest with instructions — calls memoryItemConverter.convert")
        void withInstructions() throws Exception {
            IConversationMemory memory = mock(IConversationMemory.class);
            ConversationProperties props = mock(ConversationProperties.class);
            doReturn(props).when(memory).getConversationProperties();
            doReturn(new HashMap<>()).when(props).toMap();
            doReturn("name1").when(templatingEngine).processTemplate(anyString(), any());

            PreRequest preRequest = new PreRequest();
            var instruction = new PropertyInstruction();
            instruction.setName("name1");
            instruction.setFromObjectPath("");
            instruction.setValueString("val1");
            instruction.setScope(Property.Scope.conversation);
            preRequest.setPropertyInstructions(List.of(instruction));

            Map<String, Object> newData = new HashMap<>();
            doReturn(newData).when(memoryItemConverter).convert(memory);

            Map<String, Object> result = prePostUtils.executePreRequestPropertyInstructions(
                    memory, new HashMap<>(), preRequest);
            assertSame(newData, result);
            verify(memoryItemConverter).convert(memory);
        }
    }

    // ─── executePropertyInstructions ─────────────────────────────────────

    @Nested
    @DisplayName("executePropertyInstructions")
    class PropertyInstructionTests {

        private IConversationMemory memory;
        private ConversationProperties conversationProperties;
        private Map<String, Object> templateData;

        @BeforeEach
        void setupMemory() throws Exception {
            memory = mock(IConversationMemory.class);
            conversationProperties = mock(ConversationProperties.class);
            doReturn(conversationProperties).when(memory).getConversationProperties();
            doReturn(new HashMap<>()).when(conversationProperties).toMap();
            templateData = new HashMap<>();
            doReturn("val").when(templatingEngine).processTemplate(anyString(), any());
        }

        @Test
        @DisplayName("null propertyInstructions — does nothing")
        void nullInstructions() {
            assertDoesNotThrow(() -> prePostUtils.executePropertyInstructions(
                    null, 0, false, memory, templateData));
        }

        @Test
        @DisplayName("validationError=true, runOnValidationError=true — runs instruction")
        void validationErrorRuns() throws Exception {
            var instruction = new PropertyInstruction();
            instruction.setName("prop");
            instruction.setFromObjectPath("");
            instruction.setValueString("val");
            instruction.setScope(Property.Scope.conversation);
            instruction.setRunOnValidationError(true);

            prePostUtils.executePropertyInstructions(
                    List.of(instruction), 500, true, memory, templateData);
            verify(conversationProperties).put(eq("val"), any(Property.class));
        }

        @Test
        @DisplayName("validationError=true, runOnValidationError=false, httpCode not matching — skips")
        void validationErrorSkips() throws Exception {
            var instruction = new PropertyInstruction();
            instruction.setName("prop");
            instruction.setFromObjectPath("");
            instruction.setValueString("val");
            instruction.setScope(Property.Scope.conversation);
            instruction.setRunOnValidationError(false);
            instruction.setHttpCodeValidator(new HttpCodeValidator(List.of(200), List.of()));

            prePostUtils.executePropertyInstructions(
                    List.of(instruction), 500, true, memory, templateData);
            verify(conversationProperties, never()).put(anyString(), any(Property.class));
        }

        @Test
        @DisplayName("convertToObject=true with valid JSON — parses to Map")
        void convertToObjectSuccess() throws Exception {
            var instruction = new PropertyInstruction();
            instruction.setName("jsonProp");
            instruction.setFromObjectPath("");
            instruction.setValueString("{\"key\":\"val\"}");
            instruction.setScope(Property.Scope.conversation);
            instruction.setConvertToObject(true);

            doReturn("{\"key\":\"val\"}").when(templatingEngine).processTemplate(anyString(), any());
            doReturn(Map.of("key", "val")).when(jsonSerialization).deserialize("{\"key\":\"val\"}");

            prePostUtils.executePropertyInstructions(
                    List.of(instruction), 0, false, memory, templateData);
            verify(jsonSerialization).deserialize("{\"key\":\"val\"}");
        }

        @Test
        @DisplayName("convertToObject=true but deserialize throws IOException — falls back to string")
        void convertToObjectIOException() throws Exception {
            var instruction = new PropertyInstruction();
            instruction.setName("jsonProp");
            instruction.setFromObjectPath("");
            instruction.setValueString("{bad json}");
            instruction.setScope(Property.Scope.conversation);
            instruction.setConvertToObject(true);

            doReturn("{bad json}").when(templatingEngine).processTemplate(anyString(), any());
            doThrow(new IOException("parse error")).when(jsonSerialization).deserialize("{bad json}");

            prePostUtils.executePropertyInstructions(
                    List.of(instruction), 0, false, memory, templateData);
            // Should still store as string, not throw
            verify(conversationProperties).put(anyString(), any(Property.class));
        }
    }

    // ─── createMemoryEntry ──────────────────────────────────────────────

    @Nested
    @DisplayName("createMemoryEntry")
    class CreateMemoryEntryTests {

        @Test
        @DisplayName("creates data and stores in current step")
        void createsAndStores() {
            var currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            var mockData = mock(IData.class);
            doReturn(mockData).when(dataFactory).createData(anyString(), any());

            prePostUtils.createMemoryEntry(currentStep, "responseObj", "respName", "outputKey");

            verify(dataFactory).createData("outputKey:respName", "responseObj");
            verify(currentStep).storeData(mockData);
            verify(currentStep).addConversationOutputMap(eq("outputKey"), any(Map.class));
        }
    }

    // ─── runPostResponse ────────────────────────────────────────────────

    @Nested
    @DisplayName("runPostResponse")
    class RunPostResponseTests {

        @Test
        @DisplayName("null postResponse — does nothing")
        void nullPostResponse() {
            assertDoesNotThrow(() -> prePostUtils.runPostResponse(
                    mock(IConversationMemory.class), null, new HashMap<>(), 200, false));
        }

        @Test
        @DisplayName("postResponse with no build instructions — runs only property instructions")
        void noBuildInstructions() throws Exception {
            var memory = mock(IConversationMemory.class);
            var conversationProperties = mock(ConversationProperties.class);
            doReturn(conversationProperties).when(memory).getConversationProperties();
            doReturn(new HashMap<>()).when(conversationProperties).toMap();

            var postResponse = new PostResponse();
            postResponse.setOutputBuildInstructions(null);
            postResponse.setQrBuildInstructions(null);

            assertDoesNotThrow(() -> prePostUtils.runPostResponse(
                    memory, postResponse, new HashMap<>(), 200, false));
        }
    }

    // ─── verifyHttpCode ─────────────────────────────────────────────────

    @Nested
    @DisplayName("verifyHttpCode — edge cases")
    class VerifyHttpCodeTests {

        @Test
        @DisplayName("null validator — uses default (200 allowed)")
        void nullValidator() {
            assertTrue(prePostUtils.verifyHttpCode(null, 200));
        }

        @Test
        @DisplayName("null validator — 500 not in default run list")
        void nullValidator500() {
            assertFalse(prePostUtils.verifyHttpCode(null, 500));
        }

        @Test
        @DisplayName("runOnHttpCode null — replaces with default")
        void runOnNull() {
            var validator = new HttpCodeValidator();
            validator.setRunOnHttpCode(null);
            validator.setSkipOnHttpCode(List.of());
            assertTrue(prePostUtils.verifyHttpCode(validator, 200));
        }

        @Test
        @DisplayName("skipOnHttpCode null — replaces with default")
        void skipOnNull() {
            var validator = new HttpCodeValidator();
            validator.setRunOnHttpCode(List.of(200));
            validator.setSkipOnHttpCode(null);
            assertTrue(prePostUtils.verifyHttpCode(validator, 200));
        }
    }
}
