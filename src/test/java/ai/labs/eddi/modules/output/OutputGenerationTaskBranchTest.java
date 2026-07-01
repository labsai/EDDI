/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.output;

import ai.labs.eddi.configs.output.model.OutputConfigurationSet;
import ai.labs.eddi.engine.TestMemoryFactory;
import ai.labs.eddi.engine.TestMemoryFactory.MemoryContext;
import ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.modules.output.impl.OutputGenerationTask;
import ai.labs.eddi.modules.output.model.OutputEntry;
import ai.labs.eddi.modules.output.model.OutputValue;
import ai.labs.eddi.modules.output.model.QuickReply;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;

import java.net.URI;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Extended branch coverage tests for {@link OutputGenerationTask}. Covers:
 * language matching, action occurrence counting, quick reply output, context
 * output/quick replies, multiple output values, null component, configure
 * error, and getExtensionDescriptor.
 */
@DisplayName("OutputGenerationTask Extended Branch Tests")
class OutputGenerationTaskBranchTest {

    private OutputGenerationTask task;
    private IResourceClientLibrary resourceClientLibrary;
    private IDataFactory dataFactory;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        resourceClientLibrary = mock(IResourceClientLibrary.class);
        dataFactory = mock(IDataFactory.class);
        objectMapper = new ObjectMapper();
        task = new OutputGenerationTask(resourceClientLibrary, dataFactory, objectMapper);

        when(dataFactory.createData(anyString(), any(), any(List.class))).thenAnswer(inv -> {
            var data = new Data<>(inv.getArgument(0).toString(), inv.getArgument(1));
            return data;
        });
        when(dataFactory.createData(anyString(), any())).thenAnswer(inv -> {
            var data = new Data<>(inv.getArgument(0).toString(), inv.getArgument(1));
            return data;
        });
    }

    // ==================== getId / getType / getExtensionDescriptor
    // ====================

    @Nested
    @DisplayName("Identity methods")
    class IdentityTests {

        @Test
        @DisplayName("getId returns correct TaskId")
        void getId() {
            assertEquals("ai.labs.output", task.getId().name());
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
    }

    // ==================== execute with null component ====================

    @Nested
    @DisplayName("execute edge cases")
    class ExecuteEdgeCases {

        @Test
        @DisplayName("null component skips output generation")
        void nullComponent() {
            MemoryContext ctx = TestMemoryFactory.create();
            assertDoesNotThrow(() -> task.execute(ctx.memory(), null));
        }

        @Test
        @DisplayName("no actions returns early")
        void noActionsReturnsEarly() {
            MemoryContext ctx = TestMemoryFactory.create();
            IOutputGeneration outputGen = mock(IOutputGeneration.class);
            when(outputGen.getLanguage()).thenReturn(null);

            // No actions in current step — getLatestData("actions") returns null
            task.execute(ctx.memory(), outputGen);

            verify(outputGen, never()).getOutputs(anyList());
        }
    }

    // ==================== Language matching ====================

    @Nested
    @DisplayName("Language matching")
    class LanguageMatchingTests {

        @Test
        @DisplayName("null output language matches any conversation language")
        void nullLanguageMatchesAll() {
            MemoryContext ctx = TestMemoryFactory.createWithActions(List.of("greet"));
            IOutputGeneration outputGen = mock(IOutputGeneration.class);
            when(outputGen.getLanguage()).thenReturn(null);

            OutputEntry entry = new OutputEntry("greet", 0,
                    List.of(createTextOutputValue("Hi!")),
                    Collections.emptyList());
            when(outputGen.getOutputs(anyList())).thenReturn(Map.of("greet", List.of(entry)));
            when(ctx.previousSteps().size()).thenReturn(0);

            task.execute(ctx.memory(), outputGen);

            verify(outputGen).getOutputs(anyList());
        }

        @Test
        @DisplayName("matching language processes output")
        void matchingLanguageProcesses() {
            MemoryContext ctx = TestMemoryFactory.createWithActions(List.of("greet"));
            ctx.conversationProperties().put("lang",
                    new ai.labs.eddi.configs.properties.model.Property("lang", "en",
                            ai.labs.eddi.configs.properties.model.Property.Scope.longTerm));

            IOutputGeneration outputGen = mock(IOutputGeneration.class);
            when(outputGen.getLanguage()).thenReturn("en");

            OutputEntry entry = new OutputEntry("greet", 0,
                    List.of(createTextOutputValue("Hello!")),
                    Collections.emptyList());
            when(outputGen.getOutputs(anyList())).thenReturn(Map.of("greet", List.of(entry)));
            when(ctx.previousSteps().size()).thenReturn(0);

            task.execute(ctx.memory(), outputGen);

            verify(outputGen).getOutputs(anyList());
        }

        @Test
        @DisplayName("mismatching language skips output generation")
        void mismatchingLanguageSkips() {
            MemoryContext ctx = TestMemoryFactory.createWithActions(List.of("greet"));
            ctx.conversationProperties().put("lang",
                    new ai.labs.eddi.configs.properties.model.Property("lang", "de",
                            ai.labs.eddi.configs.properties.model.Property.Scope.longTerm));

            IOutputGeneration outputGen = mock(IOutputGeneration.class);
            when(outputGen.getLanguage()).thenReturn("en");

            task.execute(ctx.memory(), outputGen);

            verify(outputGen, never()).getOutputs(anyList());
        }
    }

    // ==================== Action occurrence counting ====================

    @Nested
    @DisplayName("Action occurrence counting")
    class ActionOccurrenceTests {

        @Test
        @DisplayName("counts actions in previous steps")
        void countsActionsInPreviousSteps() {
            MemoryContext ctx = TestMemoryFactory.createWithActions(List.of("greet"));
            TestMemoryFactory.addPreviousStepWithActions(ctx, List.of("greet"));

            IOutputGeneration outputGen = mock(IOutputGeneration.class);
            when(outputGen.getLanguage()).thenReturn(null);

            OutputEntry entry = new OutputEntry("greet", 1,
                    List.of(createTextOutputValue("Hi again!")),
                    Collections.emptyList());
            when(outputGen.getOutputs(anyList())).thenReturn(Map.of("greet", List.of(entry)));

            task.execute(ctx.memory(), outputGen);

            verify(outputGen).getOutputs(anyList());
        }

        @Test
        @DisplayName("null latestData in previous step does not count")
        void nullLatestDataInPreviousStep() {
            MemoryContext ctx = TestMemoryFactory.createWithActions(List.of("greet"));
            // Add previous step but with null actions data
            var prevStep = mock(ai.labs.eddi.engine.memory.IConversationMemory.IConversationStep.class);
            when(prevStep.getLatestData(eq("actions"))).thenReturn(null);
            when(ctx.previousSteps().size()).thenReturn(1);
            when(ctx.previousSteps().get(eq(0))).thenReturn(prevStep);

            IOutputGeneration outputGen = mock(IOutputGeneration.class);
            when(outputGen.getLanguage()).thenReturn(null);
            when(outputGen.getOutputs(anyList())).thenReturn(Map.of());

            task.execute(ctx.memory(), outputGen);

            verify(outputGen).getOutputs(anyList());
        }
    }

    // ==================== Quick replies ====================

    @Nested
    @DisplayName("Quick replies")
    class QuickReplyTests {

        @Test
        @DisplayName("stores quick replies from output entry")
        void storesQuickReplies() {
            MemoryContext ctx = TestMemoryFactory.createWithActions(List.of("greet"));
            IOutputGeneration outputGen = mock(IOutputGeneration.class);
            when(outputGen.getLanguage()).thenReturn(null);

            var quickReply = new QuickReply("Yes", "yes()", false);
            OutputEntry entry = new OutputEntry("greet", 0,
                    List.of(createTextOutputValue("Hello!")),
                    List.of(quickReply));
            when(outputGen.getOutputs(anyList())).thenReturn(Map.of("greet", List.of(entry)));
            when(ctx.previousSteps().size()).thenReturn(0);

            task.execute(ctx.memory(), outputGen);

            verify(ctx.currentStep(), atLeastOnce()).addConversationOutputList(
                    eq("quickReplies"), anyList());
        }
    }

    // ==================== Multiple output values ====================

    @Nested
    @DisplayName("Multiple output values")
    class MultipleOutputValueTests {

        @Test
        @DisplayName("multiple output values create indexed keys")
        void multipleOutputValuesCreateIndexedKeys() {
            MemoryContext ctx = TestMemoryFactory.createWithActions(List.of("greet"));
            IOutputGeneration outputGen = mock(IOutputGeneration.class);
            when(outputGen.getLanguage()).thenReturn(null);

            var ov1 = createTextOutputValue("Hello!");
            var ov2 = createTextOutputValue("How are you?");
            OutputEntry entry = new OutputEntry("greet", 0,
                    List.of(ov1, ov2),
                    Collections.emptyList());
            when(outputGen.getOutputs(anyList())).thenReturn(Map.of("greet", List.of(entry)));
            when(ctx.previousSteps().size()).thenReturn(0);

            task.execute(ctx.memory(), outputGen);

            verify(ctx.currentStep(), atLeast(2)).storeData(any());
        }

        @Test
        @DisplayName("empty output alternatives are skipped")
        void emptyAlternativesSkipped() {
            MemoryContext ctx = TestMemoryFactory.createWithActions(List.of("greet"));
            IOutputGeneration outputGen = mock(IOutputGeneration.class);
            when(outputGen.getLanguage()).thenReturn(null);

            var emptyOv = new OutputValue();
            emptyOv.setValueAlternatives(Collections.emptyList());
            OutputEntry entry = new OutputEntry("greet", 0,
                    List.of(emptyOv),
                    Collections.emptyList());
            when(outputGen.getOutputs(anyList())).thenReturn(Map.of("greet", List.of(entry)));
            when(ctx.previousSteps().size()).thenReturn(0);

            task.execute(ctx.memory(), outputGen);

            // No data stored (empty alternatives => no storeData calls from output)
            verify(ctx.currentStep(), never()).addConversationOutputList(eq("output"), anyList());
        }
    }

    // ==================== Configure ====================

    @Nested
    @DisplayName("configure")
    class ConfigureTests {

        @Test
        @DisplayName("null URI returns null component")
        void nullUriReturnsNull() throws Exception {
            Map<String, Object> config = new HashMap<>();
            config.put("uri", null);
            var result = task.configure(config, null);
            assertNull(result);
        }

        @Test
        @DisplayName("missing URI key returns null component")
        void missingUriReturnsNull() throws Exception {
            var result = task.configure(Map.of(), null);
            assertNull(result);
        }

        @Test
        @DisplayName("ServiceException wraps in WorkflowConfigurationException")
        void serviceExceptionWraps() throws Exception {
            when(resourceClientLibrary.getResource(any(URI.class), eq(OutputConfigurationSet.class)))
                    .thenThrow(new ServiceException("DB error"));

            assertThrows(WorkflowConfigurationException.class,
                    () -> task.configure(Map.of("uri", "eddi://ai.labs.output/outputstore/outputsets/abc123"), null));
        }
    }

    // ==================== Helpers ====================

    private OutputValue createTextOutputValue(String text) {
        var outputValue = new OutputValue();
        outputValue.setValueAlternatives(List.of(new TextOutputItem(text, 0)));
        return outputValue;
    }
}
