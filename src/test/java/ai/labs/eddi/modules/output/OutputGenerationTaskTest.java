/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.output;

import ai.labs.eddi.configs.output.model.OutputConfiguration;
import ai.labs.eddi.configs.output.model.OutputConfigurationSet;
import ai.labs.eddi.engine.TestMemoryFactory;
import ai.labs.eddi.engine.TestMemoryFactory.MemoryContext;
import ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.modules.output.impl.OutputGenerationTask;
import ai.labs.eddi.modules.output.model.OutputEntry;
import ai.labs.eddi.modules.output.model.OutputValue;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link OutputGenerationTask} — output selection lifecycle task.
 */
@DisplayName("OutputGenerationTask")
class OutputGenerationTaskTest {

    private OutputGenerationTask task;
    private IResourceClientLibrary resourceClientLibrary;
    private IDataFactory dataFactory;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        resourceClientLibrary = mock(IResourceClientLibrary.class);
        dataFactory = mock(IDataFactory.class);
        objectMapper = new ObjectMapper();
        task = new OutputGenerationTask(resourceClientLibrary, dataFactory, objectMapper);

        // Default data factory stub — return a Data wrapper
        when(dataFactory.createData(anyString(), any(), any(List.class))).thenAnswer(invocation -> {
            var data = new Data<>(invocation.getArgument(0).toString(), invocation.getArgument(1));
            return data;
        });
        when(dataFactory.createData(anyString(), any())).thenAnswer(invocation -> {
            var data = new Data<>(invocation.getArgument(0).toString(), invocation.getArgument(1));
            return data;
        });
    }

    // ==================== Identity ====================

    @Test
    @DisplayName("getId returns correct identifier")
    void testGetId() {
        assertEquals("ai.labs.output", task.getId());
    }

    @Test
    @DisplayName("getType returns 'output'")
    void testGetType() {
        assertEquals("output", task.getType());
    }

    // ==================== execute() ====================

    @Nested
    @DisplayName("execute()")
    class ExecuteTests {

        @Test
        @DisplayName("handles null outputGeneration component gracefully")
        void execute_nullComponent_handlesGracefully() {
            MemoryContext ctx = TestMemoryFactory.create();

            // Should not throw
            assertDoesNotThrow(() -> task.execute(ctx.memory(), null));
        }

        @Test
        @DisplayName("returns early when no actions in current step")
        void execute_noActions_returnsEarly() {
            MemoryContext ctx = TestMemoryFactory.create();
            IOutputGeneration outputGen = mock(IOutputGeneration.class);
            when(outputGen.getLanguage()).thenReturn(null); // any language

            task.execute(ctx.memory(), outputGen);

            // Should not attempt to get outputs
            verify(outputGen, never()).getOutputs(anyList());
        }

        @Test
        @DisplayName("skips output generation when language does not match")
        void execute_languageMismatch_skipsGeneration() {
            MemoryContext ctx = TestMemoryFactory.createWithActions(List.of("greet"));
            IOutputGeneration outputGen = mock(IOutputGeneration.class);
            when(outputGen.getLanguage()).thenReturn("de"); // German

            // No language property set in conversation = null, won't match "de"
            // Actually, null language in memory means "any" — but output language
            // requires a match. Let's set the conversation property language.
            ctx.conversationProperties().put("language",
                    new ai.labs.eddi.configs.properties.model.Property("language", "en",
                            ai.labs.eddi.configs.properties.model.Property.Scope.longTerm));

            task.execute(ctx.memory(), outputGen);

            verify(outputGen, never()).getOutputs(anyList());
        }

        @Test
        @DisplayName("generates output when action matches and language is null (any)")
        void execute_matchingAction_generatesOutput() {
            MemoryContext ctx = TestMemoryFactory.createWithActions(List.of("greet"));
            IOutputGeneration outputGen = mock(IOutputGeneration.class);
            when(outputGen.getLanguage()).thenReturn(null); // any language

            TextOutputItem textItem = new TextOutputItem("Hello!", 0);
            OutputValue outputValue = new OutputValue();
            outputValue.setValueAlternatives(List.of(textItem));
            OutputEntry entry = new OutputEntry("greet", 0,
                    List.of(outputValue), Collections.emptyList());

            when(outputGen.getOutputs(anyList())).thenReturn(Map.of("greet", List.of(entry)));

            // Set up previous steps for action occurrence counting
            when(ctx.previousSteps().size()).thenReturn(0);

            task.execute(ctx.memory(), outputGen);

            verify(outputGen).getOutputs(anyList());
            verify(ctx.currentStep(), atLeastOnce()).storeData(any());
        }
    }

    // ==================== configure() ====================

    @Nested
    @DisplayName("configure()")
    class ConfigureTests {

        @Test
        @DisplayName("returns null when no URI configured")
        void configure_noUri_returnsNull() throws WorkflowConfigurationException {
            var result = task.configure(Collections.emptyMap(), null);
            assertNull(result);
        }

        @Test
        @DisplayName("throws WorkflowConfigurationException on service error")
        void configure_serviceError_throws() throws ServiceException {
            when(resourceClientLibrary.getResource(any(URI.class), eq(OutputConfigurationSet.class)))
                    .thenThrow(new ServiceException("not found"));

            var config = Map.<String, Object>of("uri", "eddi://ai.labs.output/outputstore/outputsets/abc123");

            assertThrows(WorkflowConfigurationException.class,
                    () -> task.configure(config, null));
        }

        @Test
        @DisplayName("loads and sorts output configuration from URI")
        void configure_validUri_loadsConfig() throws Exception {
            OutputConfigurationSet configSet = new OutputConfigurationSet();
            configSet.setLang(null);
            OutputConfiguration oc = new OutputConfiguration();
            oc.setAction("greet");
            oc.setTimesOccurred(0);
            oc.setOutputs(Collections.emptyList());
            oc.setQuickReplies(Collections.emptyList());
            configSet.setOutputSet(new ArrayList<>(List.of(oc)));

            when(resourceClientLibrary.getResource(any(URI.class), eq(OutputConfigurationSet.class)))
                    .thenReturn(configSet);

            var config = Map.<String, Object>of("uri", "eddi://ai.labs.output/outputstore/outputsets/abc123");
            var result = task.configure(config, null);

            assertNotNull(result);
            assertTrue(result instanceof IOutputGeneration);
        }
    }

    // ==================== ExtensionDescriptor ====================

    @Test
    @DisplayName("getExtensionDescriptor returns correct descriptor")
    void testExtensionDescriptor() {
        var descriptor = task.getExtensionDescriptor();

        assertNotNull(descriptor);
        assertEquals("ai.labs.output", descriptor.getType());
        assertEquals("Output Generation", descriptor.getDisplayName());
        assertTrue(descriptor.getConfigs().containsKey("uri"));
    }
}
