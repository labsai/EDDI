/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import ai.labs.eddi.modules.properties.impl.SecretPropertyVault;

import ai.labs.eddi.configs.apicalls.model.HttpCodeValidator;
import ai.labs.eddi.configs.apicalls.model.PostResponse;
import ai.labs.eddi.configs.apicalls.model.OutputBuildingInstruction;
import ai.labs.eddi.configs.apicalls.model.QuickRepliesBuildingInstruction;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.PropertyInstruction;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.model.ConversationProperties;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.*;

import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("PrePostUtils Extended Branch Coverage Tests")
@SuppressWarnings("unchecked")
class PrePostUtilsExtendedTest {

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
        prePostUtils = new PrePostUtils(jsonSerialization, memoryItemConverter, templatingEngine, dataFactory, mock(SecretPropertyVault.class));
    }

    @Nested
    @DisplayName("executePropertyInstructions — additional branch coverage")
    class PropertyInstructionBranches {

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
        @DisplayName("httpCode=0 always runs (preRequest scenario)")
        void httpCodeZero_alwaysRuns() throws Exception {
            var instruction = new PropertyInstruction();
            instruction.setName("prop");
            instruction.setFromObjectPath("");
            instruction.setValueString("val");
            instruction.setScope(Property.Scope.conversation);

            prePostUtils.executePropertyInstructions(List.of(instruction), 0, false, memory, templateData);
            assertEquals("val", stored("prop").getValueString());
        }

        @Test
        @DisplayName("httpCode not matching and no validationError — skips instruction")
        void httpCodeNotMatching_skips() throws Exception {
            var instruction = new PropertyInstruction();
            instruction.setName("prop");
            instruction.setFromObjectPath("");
            instruction.setValueString("val");
            instruction.setScope(Property.Scope.conversation);
            // Default HttpCodeValidator only allows 200, 201
            instruction.setHttpCodeValidator(new HttpCodeValidator(List.of(200), List.of()));

            prePostUtils.executePropertyInstructions(List.of(instruction), 500, false, memory, templateData);
            verify(conversationProperties, never()).put(anyString(), any(Property.class));
        }

        @Test
        @DisplayName("Map property value from PathNavigator")
        void mapPropertyValue() throws Exception {
            var instruction = new PropertyInstruction();
            instruction.setName("mapProp");
            instruction.setFromObjectPath("context.obj");
            instruction.setScope(Property.Scope.conversation);

            Map<String, Object> innerMap = new LinkedHashMap<>();
            innerMap.put("key", "value");
            templateData.put("context", Map.of("obj", innerMap));

            prePostUtils.executePropertyInstructions(List.of(instruction), 0, false, memory, templateData);
            assertEquals(Map.of("key", "value"), stored("mapProp").getValueObject());
            assertNull(stored("mapProp").getValueString(), "an object must not be flattened to a string");
        }

        @Test
        @DisplayName("List property value from PathNavigator")
        void listPropertyValue() throws Exception {
            var instruction = new PropertyInstruction();
            instruction.setName("listProp");
            instruction.setFromObjectPath("context.arr");
            instruction.setScope(Property.Scope.conversation);

            List<Object> innerList = List.of("a", "b");
            templateData.put("context", Map.of("arr", innerList));

            prePostUtils.executePropertyInstructions(List.of(instruction), 0, false, memory, templateData);
            assertEquals(List.of("a", "b"), stored("listProp").getValueList());
        }

        @Test
        @DisplayName("Integer property value from PathNavigator")
        void integerPropertyValue() throws Exception {
            var instruction = new PropertyInstruction();
            instruction.setName("intProp");
            instruction.setFromObjectPath("context.num");
            instruction.setScope(Property.Scope.conversation);

            templateData.put("context", Map.of("num", 42));

            prePostUtils.executePropertyInstructions(List.of(instruction), 0, false, memory, templateData);
            assertEquals(42, stored("intProp").getValueInt());
            assertNull(stored("intProp").getValueString(), "a number used to be stored as an empty string");
        }

        @Test
        @DisplayName("Float property value from PathNavigator")
        void floatPropertyValue() throws Exception {
            var instruction = new PropertyInstruction();
            instruction.setName("floatProp");
            instruction.setFromObjectPath("context.fval");
            instruction.setScope(Property.Scope.conversation);

            templateData.put("context", Map.of("fval", 3.14f));

            prePostUtils.executePropertyInstructions(List.of(instruction), 0, false, memory, templateData);
            assertEquals(3.14f, stored("floatProp").getValueFloat());
        }

        @Test
        @DisplayName("Boolean property value from PathNavigator")
        void booleanPropertyValue() throws Exception {
            var instruction = new PropertyInstruction();
            instruction.setName("boolProp");
            instruction.setFromObjectPath("context.flag");
            instruction.setScope(Property.Scope.conversation);

            templateData.put("context", Map.of("flag", true));

            prePostUtils.executePropertyInstructions(List.of(instruction), 0, false, memory, templateData);
            assertEquals(Boolean.TRUE, stored("boolProp").getValueBoolean());
        }

        @Test
        @DisplayName("Double (a JSON decimal) keeps full precision")
        void doublePropertyValue() throws Exception {
            var instruction = new PropertyInstruction();
            instruction.setName("price");
            instruction.setFromObjectPath("context.price");
            instruction.setScope(Property.Scope.conversation);

            templateData.put("context", Map.of("price", 48.2081743d));

            prePostUtils.executePropertyInstructions(List.of(instruction), 0, false, memory, templateData);
            assertEquals(48.2081743d, stored("price").getValueDouble());
        }

        @Test
        @DisplayName("Long beyond the int range is stored as valueLong, a small Long as valueInt")
        void longPropertyValue() throws Exception {
            var big = new PropertyInstruction();
            big.setName("epochMillis");
            big.setFromObjectPath("context.ts");
            big.setScope(Property.Scope.conversation);
            var small = new PropertyInstruction();
            small.setName("count");
            small.setFromObjectPath("context.count");
            small.setScope(Property.Scope.conversation);

            templateData.put("context", Map.of("ts", 1_759_400_000_000L, "count", 7L));

            prePostUtils.executePropertyInstructions(List.of(big, small), 0, false, memory, templateData);
            assertEquals(1_759_400_000_000L, stored("epochMillis").getValueLong());
            assertEquals(7, stored("count").getValueInt());
        }

        @Test
        @DisplayName("a path that resolves to nothing writes nothing (it used to overwrite with an empty string)")
        void missingPathWritesNothing() throws Exception {
            var instruction = new PropertyInstruction();
            instruction.setName("absent");
            instruction.setFromObjectPath("context.nope");
            instruction.setScope(Property.Scope.conversation);
            templateData.put("context", Map.of());

            prePostUtils.executePropertyInstructions(List.of(instruction), 0, false, memory, templateData);
            verify(conversationProperties, never()).put(eq("absent"), any(Property.class));
        }

        @Test
        @DisplayName("a failing instruction is recorded in the step and the next one still runs")
        void failureIsRecordedAndDoesNotStopTheBatch() throws Exception {
            var step = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(step);
            when(memory.getConversationId()).thenReturn("conv-1");
            IData<Object> recorded = mock(IData.class);
            when(dataFactory.createData(eq(PrePostUtils.KEY_PROPERTY_INSTRUCTION_ERRORS), any())).thenReturn(recorded);
            when(templatingEngine.processTemplate(eq("{broken"), any()))
                    .thenThrow(new ITemplatingEngine.TemplateEngineException("unterminated", null));

            var broken = new PropertyInstruction();
            broken.setName("broken");
            broken.setValueString("{broken");
            broken.setScope(Property.Scope.conversation);
            var fine = new PropertyInstruction();
            fine.setName("fine");
            fine.setValueString("ok");
            fine.setScope(Property.Scope.conversation);

            prePostUtils.executePropertyInstructions(List.of(broken, fine), 0, false, memory, templateData);

            ArgumentCaptor<Object> errors = ArgumentCaptor.forClass(Object.class);
            verify(dataFactory).createData(eq(PrePostUtils.KEY_PROPERTY_INSTRUCTION_ERRORS), errors.capture());
            assertTrue(errors.getValue().toString().contains("broken"), errors.getValue().toString());
            verify(step).storeData(recorded);
            assertEquals("ok", stored("fine").getValueString());
        }

        private Property stored(String name) {
            ArgumentCaptor<Property> captor = ArgumentCaptor.forClass(Property.class);
            verify(conversationProperties, atLeastOnce()).put(eq(name), captor.capture());
            return captor.getValue();
        }

        @Test
        @DisplayName("convertToObject=false — doesn't try JSON parsing")
        void convertToObjectFalse() throws Exception {
            var instruction = new PropertyInstruction();
            instruction.setName("noParse");
            instruction.setFromObjectPath("");
            instruction.setValueString("{\"key\":\"val\"}");
            instruction.setScope(Property.Scope.conversation);
            instruction.setConvertToObject(false);

            prePostUtils.executePropertyInstructions(List.of(instruction), 0, false, memory, templateData);
            verify(jsonSerialization, never()).deserialize(anyString());
            assertEquals("{\"key\":\"val\"}", stored("noParse").getValueString());
        }

        @Test
        @DisplayName("convertToObject=true but value doesn't start with { — skips parsing")
        void convertToObjectNotJsonLike() throws Exception {
            var instruction = new PropertyInstruction();
            instruction.setName("noJsonParse");
            instruction.setFromObjectPath("");
            instruction.setValueString("plain text");
            instruction.setScope(Property.Scope.conversation);
            instruction.setConvertToObject(true);

            prePostUtils.executePropertyInstructions(List.of(instruction), 0, false, memory, templateData);
            verify(jsonSerialization, never()).deserialize(anyString());
        }

        @Test
        @DisplayName("Exception during PathNavigator.getValue — catches and logs")
        void pathNavigatorException() throws Exception {
            var instruction = new PropertyInstruction();
            instruction.setName("errProp");
            instruction.setFromObjectPath("nonexistent.deeply.nested.path");
            instruction.setScope(Property.Scope.conversation);

            // Should not throw, just log
            assertDoesNotThrow(() -> prePostUtils.executePropertyInstructions(List.of(instruction), 0, false, memory, templateData));
        }
    }

    @Nested
    @DisplayName("runPostResponse — output and quickReply branches")
    class RunPostResponseBranches {

        @Test
        @DisplayName("postResponse with outputBuildInstructions — builds output")
        void outputBuildInstructions() throws Exception {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            var conversationProperties = mock(ConversationProperties.class);
            when(memory.getConversationProperties()).thenReturn(conversationProperties);
            when(conversationProperties.toMap()).thenReturn(new HashMap<>());
            when(templatingEngine.processTemplate(anyString(), any())).thenAnswer(i -> "rendered output");

            var postResponse = new PostResponse();
            var outputInstruction = new OutputBuildingInstruction();
            outputInstruction.setHttpCodeValidator(new HttpCodeValidator(List.of(200), List.of()));
            outputInstruction.setOutputType("text");
            outputInstruction.setOutputValue("Hello");
            postResponse.setOutputBuildInstructions(List.of(outputInstruction));

            prePostUtils.runPostResponse(memory, postResponse, new HashMap<>(), 200, false);
            verify(currentStep).storeData(any());
        }

        @Test
        @DisplayName("postResponse with qrBuildInstructions — builds quick replies")
        void qrBuildInstructions() throws Exception {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            var conversationProperties = mock(ConversationProperties.class);
            when(memory.getConversationProperties()).thenReturn(conversationProperties);
            when(conversationProperties.toMap()).thenReturn(new HashMap<>());
            when(templatingEngine.processTemplate(anyString(), any())).thenAnswer(i -> "[{\"value\":\"yes\",\"expressions\":\"yes()\"}]");
            when(jsonSerialization.deserialize(anyString(), eq(List.class))).thenReturn(List.of(Map.of("value", "yes")));
            when(dataFactory.createData(anyString(), any())).thenReturn(mock(IData.class));

            var postResponse = new PostResponse();
            var qrInstruction = new QuickRepliesBuildingInstruction();
            qrInstruction.setHttpCodeValidator(new HttpCodeValidator(List.of(200), List.of()));
            qrInstruction.setQuickReplyValue("yes");
            qrInstruction.setQuickReplyExpressions("yes()");
            qrInstruction.setIterationObjectName("item");
            qrInstruction.setPathToTargetArray("items");
            postResponse.setQrBuildInstructions(List.of(qrInstruction));

            prePostUtils.runPostResponse(memory, postResponse, new HashMap<>(), 200, false);
            verify(currentStep, atLeastOnce()).storeData(any());
        }

        @Test
        @DisplayName("output http code not matching — skips output building")
        void httpCodeNotMatchingOutput() throws Exception {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            var conversationProperties = mock(ConversationProperties.class);
            when(memory.getConversationProperties()).thenReturn(conversationProperties);
            when(conversationProperties.toMap()).thenReturn(new HashMap<>());
            when(dataFactory.createData(anyString(), any())).thenReturn(mock(IData.class));

            var postResponse = new PostResponse();
            var outputInstruction = new OutputBuildingInstruction();
            outputInstruction.setHttpCodeValidator(new HttpCodeValidator(List.of(200), List.of()));
            outputInstruction.setOutputType("text");
            outputInstruction.setOutputValue("Hello");
            postResponse.setOutputBuildInstructions(List.of(outputInstruction));

            // httpCode=500, doesn't match 200
            prePostUtils.runPostResponse(memory, postResponse, new HashMap<>(), 500, false);
            // Context:output data should still be stored (but empty list)
            verify(currentStep).storeData(any());
        }
    }

    @Nested
    @DisplayName("verifyHttpCode — additional branches")
    class VerifyHttpCodeExtended {

        @Test
        @DisplayName("both runOnHttpCode and skipOnHttpCode null — uses defaults")
        void bothNull() {
            var validator = new HttpCodeValidator();
            validator.setRunOnHttpCode(null);
            validator.setSkipOnHttpCode(null);
            assertTrue(prePostUtils.verifyHttpCode(validator, 200));
        }

        @Test
        @DisplayName("code in both run and skip — skip wins")
        void codeInBothRunAndSkip() {
            var validator = new HttpCodeValidator(List.of(200, 201), List.of(200));
            assertFalse(prePostUtils.verifyHttpCode(validator, 200));
            assertTrue(prePostUtils.verifyHttpCode(validator, 201));
        }
    }
}
