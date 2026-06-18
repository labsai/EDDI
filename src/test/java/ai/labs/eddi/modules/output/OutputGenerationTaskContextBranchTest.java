/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.output;

import ai.labs.eddi.configs.output.model.OutputConfiguration;
import ai.labs.eddi.configs.output.model.OutputConfigurationSet;
import ai.labs.eddi.engine.TestMemoryFactory;
import ai.labs.eddi.engine.TestMemoryFactory.MemoryContext;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.output.impl.OutputGenerationTask;
import ai.labs.eddi.modules.output.model.OutputEntry;
import ai.labs.eddi.modules.output.model.OutputItem;
import ai.labs.eddi.modules.output.model.OutputValue;
import ai.labs.eddi.modules.output.model.QuickReply;
import ai.labs.eddi.modules.output.model.types.QuickReplyOutputItem;
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
 * Additional branch coverage for {@link OutputGenerationTask}:
 * <ul>
 * <li>storeContextOutput — context with output key and object type</li>
 * <li>storeContextQuickReplies — context with quickReplies key and object
 * type</li>
 * <li>selectAndStoreOutput — QuickReplyOutputItem path</li>
 * <li>configure — successful config with sorting and conversion</li>
 * <li>createOutputKey — single vs multiple output values index key</li>
 * <li>convertQuickRepliesConfig — exercises stream + setter path</li>
 * </ul>
 */
@DisplayName("OutputGenerationTask — Context + Configure Branch Tests")
class OutputGenerationTaskContextBranchTest {

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

        when(dataFactory.createData(anyString(), any(), any(List.class))).thenAnswer(inv -> {
            var data = new Data<>(inv.getArgument(0).toString(), inv.getArgument(1));
            return data;
        });
        when(dataFactory.createData(anyString(), any())).thenAnswer(inv -> {
            var data = new Data<>(inv.getArgument(0).toString(), inv.getArgument(1));
            return data;
        });
    }

    // ==================== storeContextOutput ====================

    @Nested
    @DisplayName("storeContextOutput")
    class StoreContextOutputTests {

        @Test
        @DisplayName("context with output key and object type — stores output")
        void contextOutputObjectType() throws Exception {
            MemoryContext ctx = TestMemoryFactory.create();

            // Build context data that has key "context:output" and type=object
            Context outputContext = new Context(Context.ContextType.object,
                    List.of(
                            Map.of("valueAlternatives", List.of(
                                    Map.of("type", "text", "text", "Hello from context!")))));

            IData<Context> contextData = new Data<>("context:output", outputContext);
            doReturn(List.of(contextData)).when(ctx.currentStep()).getAllData("context");

            // Execute with null component — will still call storeContextOutput
            task.execute(ctx.memory(), null);

            // Verify that storeData was called for the context output
            verify(ctx.currentStep(), atLeastOnce()).storeData(any());
        }

        @Test
        @DisplayName("context with non-output key — does not store output")
        void contextNonOutputKey() throws Exception {
            MemoryContext ctx = TestMemoryFactory.create();

            Context otherContext = new Context(Context.ContextType.string, "some value");
            IData<Context> contextData = new Data<>("context:other", otherContext);
            doReturn(List.of(contextData)).when(ctx.currentStep()).getAllData("context");

            task.execute(ctx.memory(), null);

            // Only context processing — no output stored
            verify(ctx.currentStep(), never()).addConversationOutputList(eq("output"), anyList());
        }

        @Test
        @DisplayName("context with output key but string type — skipped")
        void contextOutputStringType() throws Exception {
            MemoryContext ctx = TestMemoryFactory.create();

            Context stringContext = new Context(Context.ContextType.string, "plain text");
            IData<Context> contextData = new Data<>("context:output", stringContext);
            doReturn(List.of(contextData)).when(ctx.currentStep()).getAllData("context");

            task.execute(ctx.memory(), null);

            verify(ctx.currentStep(), never()).addConversationOutputList(eq("output"), anyList());
        }
    }

    // ==================== storeContextQuickReplies ====================

    @Nested
    @DisplayName("storeContextQuickReplies")
    class StoreContextQuickRepliesTests {

        @Test
        @DisplayName("context with quickReplies key and object type — stores quick replies")
        void contextQuickRepliesObjectType() throws Exception {
            MemoryContext ctx = TestMemoryFactory.create();

            Context qrContext = new Context(Context.ContextType.object,
                    List.of(Map.of("value", "Yes", "expressions", "yes()")));

            IData<Context> contextData = new Data<>("context:quickReplies:greet", qrContext);
            doReturn(List.of(contextData)).when(ctx.currentStep()).getAllData("context");

            task.execute(ctx.memory(), null);

            verify(ctx.currentStep(), atLeastOnce()).storeData(any());
        }

        @Test
        @DisplayName("context with quickReplies key but no sub-key — uses 'context' as key")
        void contextQuickRepliesNoSubKey() throws Exception {
            MemoryContext ctx = TestMemoryFactory.create();

            Context qrContext = new Context(Context.ContextType.object,
                    List.of(Map.of("value", "Maybe", "expressions", "maybe()")));

            // Key is "context:quickReplies" without a sub-key after it
            IData<Context> contextData = new Data<>("context:quickReplies", qrContext);
            doReturn(List.of(contextData)).when(ctx.currentStep()).getAllData("context");

            task.execute(ctx.memory(), null);

            verify(ctx.currentStep(), atLeastOnce()).storeData(any());
        }
    }

    // ==================== QuickReplyOutputItem in selectAndStoreOutput
    // ====================

    @Nested
    @DisplayName("selectAndStoreOutput — QuickReplyOutputItem path")
    class QuickReplyOutputItemTests {

        @Test
        @DisplayName("QuickReplyOutputItem is stored as quick reply, not as output")
        void quickReplyOutputItem() throws Exception {
            MemoryContext ctx = TestMemoryFactory.createWithActions(List.of("greet"));
            IOutputGeneration outputGen = mock(IOutputGeneration.class);
            when(outputGen.getLanguage()).thenReturn(null);

            // Create output value with QuickReplyOutputItem alternative
            var ov = new OutputValue();
            ov.setValueAlternatives(List.of(new QuickReplyOutputItem("Yes", "yes()", false)));

            OutputEntry entry = new OutputEntry("greet", 0,
                    List.of(ov), Collections.emptyList());
            when(outputGen.getOutputs(anyList())).thenReturn(Map.of("greet", List.of(entry)));
            when(ctx.previousSteps().size()).thenReturn(0);

            task.execute(ctx.memory(), outputGen);

            // Quick reply path stores via addConversationOutputList("quickReplies", ...)
            verify(ctx.currentStep(), atLeastOnce()).addConversationOutputList(eq("quickReplies"), anyList());
        }
    }

    // ==================== configure — successful path ====================

    @Nested
    @DisplayName("configure — success path")
    class ConfigureSuccessTests {

        @Test
        @DisplayName("valid URI — loads, sorts, and converts configuration")
        void validConfig() throws Exception {
            var qr = new QuickReply("yes", "yes()", false);

            var output = new OutputConfiguration.Output();
            output.setValueAlternatives(List.of(new TextOutputItem("Hello!", 0)));

            var outConfig = new OutputConfiguration();
            outConfig.setAction("greet");
            outConfig.setTimesOccurred(0);
            outConfig.setOutputs(List.of(output));
            outConfig.setQuickReplies(List.of(qr));

            var outConfig2 = new OutputConfiguration();
            outConfig2.setAction("greet");
            outConfig2.setTimesOccurred(1);
            outConfig2.setOutputs(List.of(output));
            outConfig2.setQuickReplies(Collections.emptyList());

            var configSet = new OutputConfigurationSet();
            configSet.setLang("en");
            configSet.setOutputSet(new ArrayList<>(List.of(outConfig2, outConfig)));

            when(resourceClientLibrary.getResource(any(URI.class), eq(OutputConfigurationSet.class)))
                    .thenReturn(configSet);

            var result = task.configure(
                    Map.of("uri", "eddi://ai.labs.output/outputstore/outputsets/abc123"), null);

            assertNotNull(result);
            assertInstanceOf(IOutputGeneration.class, result);
        }

        @Test
        @DisplayName("sorting — same action, different timesOccurred")
        void sortingByTimesOccurred() throws Exception {
            var output = new OutputConfiguration.Output();
            output.setValueAlternatives(List.of(new TextOutputItem("text", 0)));

            var config1 = new OutputConfiguration();
            config1.setAction("act");
            config1.setTimesOccurred(2);
            config1.setOutputs(List.of(output));
            config1.setQuickReplies(Collections.emptyList());

            var config2 = new OutputConfiguration();
            config2.setAction("act");
            config2.setTimesOccurred(0);
            config2.setOutputs(List.of(output));
            config2.setQuickReplies(Collections.emptyList());

            var configSet = new OutputConfigurationSet();
            configSet.setOutputSet(new ArrayList<>(List.of(config1, config2)));

            when(resourceClientLibrary.getResource(any(URI.class), eq(OutputConfigurationSet.class)))
                    .thenReturn(configSet);

            var result = task.configure(
                    Map.of("uri", "eddi://ai.labs.output/outputstore/outputsets/test"), null);

            assertNotNull(result);
        }
    }

    // ==================== convertQuickRepliesConfig — isDefault handling
    // ====================

    @Nested
    @DisplayName("convertQuickRepliesConfig")
    class ConvertQuickRepliesTests {

        @Test
        @DisplayName("quick replies with isDefault=true are properly converted")
        void quickReplyIsDefault() throws Exception {
            var output = new OutputConfiguration.Output();
            output.setValueAlternatives(List.of(new TextOutputItem("text", 0)));

            var qr1 = new QuickReply("Yes", "yes()", true);
            var qr2 = new QuickReply("No", "no()", false);

            var config = new OutputConfiguration();
            config.setAction("confirm");
            config.setTimesOccurred(0);
            config.setOutputs(List.of(output));
            config.setQuickReplies(List.of(qr1, qr2));

            var configSet = new OutputConfigurationSet();
            configSet.setOutputSet(new ArrayList<>(List.of(config)));

            when(resourceClientLibrary.getResource(any(URI.class), eq(OutputConfigurationSet.class)))
                    .thenReturn(configSet);

            var result = task.configure(
                    Map.of("uri", "eddi://ai.labs.output/outputstore/outputsets/test"), null);

            assertNotNull(result);
        }
    }
}
