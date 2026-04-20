package ai.labs.eddi.modules.output.impl;

import ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.*;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.output.IOutputFilter;
import ai.labs.eddi.modules.output.IOutputGeneration;
import ai.labs.eddi.modules.output.model.OutputEntry;
import ai.labs.eddi.modules.output.model.OutputItem;
import ai.labs.eddi.modules.output.model.OutputValue;
import ai.labs.eddi.modules.output.model.QuickReply;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static ai.labs.eddi.engine.memory.MemoryKeys.ACTIONS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("OutputGenerationTask Tests")
class OutputGenerationTaskTest {

    private IResourceClientLibrary resourceClientLibrary;
    private IDataFactory dataFactory;
    private OutputGenerationTask task;

    @BeforeEach
    void setUp() {
        resourceClientLibrary = mock(IResourceClientLibrary.class);
        dataFactory = mock(IDataFactory.class);
        var objectMapper = new ObjectMapper();

        task = new OutputGenerationTask(resourceClientLibrary, dataFactory, objectMapper);
    }

    @Test
    @DisplayName("getId returns correct ID")
    void getId() {
        assertEquals("ai.labs.output", task.getId());
    }

    @Test
    @DisplayName("getType returns 'output'")
    void getType() {
        assertEquals("output", task.getType());
    }

    @Test
    @DisplayName("getExtensionDescriptor returns valid descriptor")
    void getExtensionDescriptor() {
        var descriptor = task.getExtensionDescriptor();
        assertNotNull(descriptor);
        assertEquals("Output Generation", descriptor.getDisplayName());
        assertTrue(descriptor.getConfigs().containsKey("uri"));
    }

    @Nested
    @DisplayName("execute Tests")
    class ExecuteTests {

        @Test
        @DisplayName("null component — should handle context output only")
        void nullComponent() {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(currentStep.getAllData("context")).thenReturn(List.of());

            assertDoesNotThrow(() -> task.execute(memory, null));
        }

        @Test
        @DisplayName("no actions — should return early after context processing")
        void noActions() {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(currentStep.getAllData("context")).thenReturn(List.of());

            var outputGen = mock(IOutputGeneration.class);
            when(outputGen.getLanguage()).thenReturn(null);

            var properties = mock(IConversationProperties.class);
            when(memory.getConversationProperties()).thenReturn(properties);

            when(currentStep.getLatestData(ACTIONS)).thenReturn(null);

            assertDoesNotThrow(() -> task.execute(memory, outputGen));
            verify(outputGen, never()).getOutputs(anyList());
        }

        @Test
        @DisplayName("actions with matching output — stores output and quick replies")
        void actionsWithOutput() {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(currentStep.getAllData("context")).thenReturn(List.of());

            var outputGen = mock(IOutputGeneration.class);
            when(outputGen.getLanguage()).thenReturn(null);

            var properties = mock(IConversationProperties.class);
            when(memory.getConversationProperties()).thenReturn(properties);

            var actionData = mock(IData.class);
            when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
            when(actionData.getResult()).thenReturn(List.of("greet"));

            var previousSteps = mock(IConversationStepStack.class);
            when(memory.getPreviousSteps()).thenReturn(previousSteps);
            when(previousSteps.size()).thenReturn(0);

            // Setup output items
            var textItem = new TextOutputItem("Hello!", 0);
            var outputValue = new OutputValue();
            outputValue.setValueAlternatives(List.of(textItem));
            var outputEntry = new OutputEntry("greet", 0, List.of(outputValue), List.of());

            Map<String, List<OutputEntry>> outputs = new HashMap<>();
            outputs.put("greet", List.of(outputEntry));
            when(outputGen.getOutputs(anyList())).thenReturn(outputs);

            var outputData = mock(IData.class);
            when(dataFactory.createData(anyString(), any(), anyList())).thenReturn(outputData);

            assertDoesNotThrow(() -> task.execute(memory, outputGen));

            verify(currentStep, atLeastOnce()).storeData(any());
            verify(currentStep, atLeastOnce()).addConversationOutputList(eq("output"), anyList());
        }

        @Test
        @DisplayName("language mismatch — should skip output generation")
        void languageMismatch() {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(currentStep.getAllData("context")).thenReturn(List.of());

            var outputGen = mock(IOutputGeneration.class);
            when(outputGen.getLanguage()).thenReturn("de");

            var properties = mock(IConversationProperties.class);
            when(memory.getConversationProperties()).thenReturn(properties);
            // No language property set → defaults to null → mismatch with "de"

            assertDoesNotThrow(() -> task.execute(memory, outputGen));
            verify(outputGen, never()).getOutputs(anyList());
        }

        @Test
        @DisplayName("empty output values — should not store anything")
        void emptyOutputValues() {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(currentStep.getAllData("context")).thenReturn(List.of());

            var outputGen = mock(IOutputGeneration.class);
            when(outputGen.getLanguage()).thenReturn(null);

            var properties = mock(IConversationProperties.class);
            when(memory.getConversationProperties()).thenReturn(properties);

            var actionData = mock(IData.class);
            when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
            when(actionData.getResult()).thenReturn(List.of("farewell"));

            var previousSteps = mock(IConversationStepStack.class);
            when(memory.getPreviousSteps()).thenReturn(previousSteps);
            when(previousSteps.size()).thenReturn(0);

            // Return empty outputs for the action
            when(outputGen.getOutputs(anyList())).thenReturn(Map.of());

            assertDoesNotThrow(() -> task.execute(memory, outputGen));
            verify(currentStep, never()).addConversationOutputList(eq("output"), anyList());
        }

        @Test
        @DisplayName("output with quick replies — stores quick replies separately")
        void outputWithQuickReplies() {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(currentStep.getAllData("context")).thenReturn(List.of());

            var outputGen = mock(IOutputGeneration.class);
            when(outputGen.getLanguage()).thenReturn(null);

            var properties = mock(IConversationProperties.class);
            when(memory.getConversationProperties()).thenReturn(properties);

            var actionData = mock(IData.class);
            when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
            when(actionData.getResult()).thenReturn(List.of("ask_preference"));

            var previousSteps = mock(IConversationStepStack.class);
            when(memory.getPreviousSteps()).thenReturn(previousSteps);
            when(previousSteps.size()).thenReturn(0);

            var quickReplies = List.of(
                    new QuickReply("Option A", "option_a()", false),
                    new QuickReply("Option B", "option_b()", false));

            var textItem = new TextOutputItem("Choose:", 0);
            var outputValue = new OutputValue();
            outputValue.setValueAlternatives(List.of(textItem));
            var outputEntry = new OutputEntry("ask_preference", 0, List.of(outputValue), quickReplies);

            when(outputGen.getOutputs(anyList())).thenReturn(Map.of("ask_preference", List.of(outputEntry)));

            var outputData = mock(IData.class);
            when(dataFactory.createData(anyString(), any(), anyList())).thenReturn(outputData);
            when(dataFactory.createData(anyString(), anyList())).thenReturn(outputData);

            assertDoesNotThrow(() -> task.execute(memory, outputGen));

            verify(currentStep, atLeastOnce()).addConversationOutputList(eq("quickReplies"), anyList());
        }

        @Test
        @DisplayName("multiple actions — creates filter for each")
        void multipleActions() {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(currentStep.getAllData("context")).thenReturn(List.of());

            var outputGen = mock(IOutputGeneration.class);
            when(outputGen.getLanguage()).thenReturn(null);

            var properties = mock(IConversationProperties.class);
            when(memory.getConversationProperties()).thenReturn(properties);

            var actionData = mock(IData.class);
            when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
            when(actionData.getResult()).thenReturn(List.of("greet", "ask_name"));

            var previousSteps = mock(IConversationStepStack.class);
            when(memory.getPreviousSteps()).thenReturn(previousSteps);
            when(previousSteps.size()).thenReturn(0);

            when(outputGen.getOutputs(anyList())).thenReturn(Map.of());

            assertDoesNotThrow(() -> task.execute(memory, outputGen));

            // Verify getOutputs was called with a list of 2 filters
            verify(outputGen).getOutputs(argThat(filters -> filters.size() == 2));
        }
    }

    @Nested
    @DisplayName("configure Tests")
    class ConfigureTests {

        @Test
        @DisplayName("null URI — returns null component")
        void nullUri() throws Exception {
            var config = new HashMap<String, Object>();
            // No "uri" key

            var result = task.configure(config, Map.of());

            assertNull(result);
        }

        @Test
        @DisplayName("valid URI — loads OutputConfigurationSet from resource library")
        void validUri() throws Exception {
            var config = new HashMap<String, Object>();
            config.put("uri", "eddi://ai.labs.output/outputsets/abc123?version=1");

            var outputConfigSet = new ai.labs.eddi.configs.output.model.OutputConfigurationSet();
            outputConfigSet.setLang(null);
            outputConfigSet.setOutputSet(new ArrayList<>());

            when(resourceClientLibrary.getResource(any(), eq(ai.labs.eddi.configs.output.model.OutputConfigurationSet.class)))
                    .thenReturn(outputConfigSet);

            var result = task.configure(config, Map.of());

            assertNotNull(result);
            assertInstanceOf(IOutputGeneration.class, result);
        }
    }

    @Nested
    @DisplayName("Context Output Tests")
    class ContextOutputTests {

        @Test
        @DisplayName("context with output type — stores context output")
        void contextOutput() {
            var memory = mock(IConversationMemory.class);
            var currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);

            var context = new Context();
            context.setType(Context.ContextType.object);
            var valueAlternatives = List.of(
                    Map.of("valueAlternatives", List.of(Map.of("type", "text", "text", "Context output"))));
            context.setValue(valueAlternatives);

            var contextData = mock(IData.class);
            when(contextData.getKey()).thenReturn("context:output");
            when(contextData.getResult()).thenReturn(context);

            when(currentStep.getAllData("context")).thenReturn(List.of(contextData));

            var outputData = mock(IData.class);
            when(dataFactory.createData(anyString(), any(), anyList())).thenReturn(outputData);

            assertDoesNotThrow(() -> task.execute(memory, null));
        }
    }
}
