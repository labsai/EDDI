/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp;

import ai.labs.eddi.configs.workflows.model.ExtensionDescriptor;
import ai.labs.eddi.engine.TestMemoryFactory;
import ai.labs.eddi.engine.TestMemoryFactory.MemoryContext;
import ai.labs.eddi.engine.lifecycle.exceptions.UnrecognizedExtensionException;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import ai.labs.eddi.modules.nlp.extensions.corrections.ICorrection;
import ai.labs.eddi.modules.nlp.extensions.corrections.providers.ICorrectionProvider;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.providers.IDictionaryProvider;
import ai.labs.eddi.modules.nlp.extensions.normalizers.INormalizer;
import ai.labs.eddi.modules.nlp.extensions.normalizers.providers.INormalizerProvider;
import ai.labs.eddi.modules.nlp.internal.matches.RawSolution;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link InputParserTask} — the NLP parser lifecycle task.
 * <p>
 * Tests cover: execute() (early returns, normal parsing, expression storage),
 * configure() (config flags, provider wiring, error paths), and
 * getExtensionDescriptor().
 */
@DisplayName("InputParserTask")
class InputParserTaskTest {

    private InputParserTask task;
    private IExpressionProvider expressionProvider;
    private Map<String, Provider<INormalizerProvider>> normalizerProviders;
    private Map<String, Provider<IDictionaryProvider>> dictionaryProviders;
    private Map<String, Provider<ICorrectionProvider>> correctionProviders;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        expressionProvider = mock(IExpressionProvider.class);
        normalizerProviders = new HashMap<>();
        dictionaryProviders = new HashMap<>();
        correctionProviders = new HashMap<>();
        objectMapper = new ObjectMapper();
        task = new InputParserTask(expressionProvider, normalizerProviders, dictionaryProviders,
                correctionProviders, objectMapper);
    }

    // ==================== Identity ====================

    @Nested
    @DisplayName("Task Identity")
    class IdentityTests {

        @Test
        @DisplayName("getId returns correct identifier")
        void testGetId() {
            assertEquals("ai.labs.parser", task.getId());
        }

        @Test
        @DisplayName("getType returns 'expressions'")
        void testGetType() {
            assertEquals("expressions", task.getType());
        }
    }

    // ==================== execute() ====================

    @Nested
    @DisplayName("execute()")
    class ExecuteTests {

        @Test
        @DisplayName("returns early when no input data is present")
        void execute_noInput_returnsEarly() {
            MemoryContext ctx = TestMemoryFactory.create();
            IInputParser parser = mock(IInputParser.class);

            // currentStep.getLatestData("input") returns null by default from
            // TestMemoryFactory
            task.execute(ctx.memory(), parser);

            // Should never attempt to parse
            verifyNoInteractions(parser);
        }

        @Test
        @DisplayName("normalizes input and stores in memory")
        void execute_withInput_normalizesAndParses() throws Exception {
            MemoryContext ctx = TestMemoryFactory.createWithInput("Hello World");
            IInputParser parser = mock(IInputParser.class);
            var config = new IInputParser.Config(true, true, true);
            when(parser.getConfig()).thenReturn(config);
            when(parser.normalize(eq("Hello World"), isNull())).thenReturn("hello world");
            when(parser.parse(eq("hello world"), isNull(), anyList()))
                    .thenReturn(Collections.emptyList());

            task.execute(ctx.memory(), parser);

            verify(parser).normalize(eq("Hello World"), isNull());
            verify(parser).parse(eq("hello world"), isNull(), anyList());
            // Verify normalized input was stored
            verify(ctx.currentStep()).storeData(argThat(data -> "input:normalized".equals(data.getKey()) && "hello world".equals(data.getResult())));
        }

        @Test
        @DisplayName("stores parsed expressions and intents when solutions found")
        void execute_withParsedSolutions_storesExpressionsAndIntents() throws Exception {
            MemoryContext ctx = TestMemoryFactory.createWithInput("hi there");
            IInputParser parser = mock(IInputParser.class);
            var config = new IInputParser.Config(true, true, true);
            when(parser.getConfig()).thenReturn(config);
            when(parser.normalize(eq("hi there"), isNull())).thenReturn("hi there");

            // Create a raw solution
            RawSolution rawSolution = mock(RawSolution.class);
            var foundWord = mock(IDictionary.IFoundWord.class);
            when(foundWord.getValue()).thenReturn("hi there");
            Expressions foundExpressions = new Expressions();
            foundExpressions.add(new Expression("greeting"));
            when(foundWord.getExpressions()).thenReturn(foundExpressions);
            when(foundWord.isWord()).thenReturn(true);
            when(rawSolution.getDictionaryEntries()).thenReturn(List.of(foundWord));
            when(parser.parse(eq("hi there"), isNull(), anyList()))
                    .thenReturn(List.of(rawSolution));

            // Also set up getLatestData for expressions:parsed to return null (no append)
            when(ctx.currentStep().getLatestData(eq("expressions:parsed"))).thenReturn(null);

            task.execute(ctx.memory(), parser);

            // Verify expressions were stored
            verify(ctx.currentStep(), atLeastOnce()).storeData(any());
        }

        @Test
        @DisplayName("handles InterruptedException during parse gracefully")
        void execute_parseInterrupted_returnsGracefully() throws Exception {
            MemoryContext ctx = TestMemoryFactory.createWithInput("test input");
            IInputParser parser = mock(IInputParser.class);
            when(parser.normalize(eq("test input"), isNull())).thenThrow(new InterruptedException("test interrupt"));

            // Should not throw
            task.execute(ctx.memory(), parser);

            // Should not attempt to store any results
            verify(ctx.currentStep(), never()).addConversationOutputString(eq("expressions"), anyString());
        }

        @Test
        @DisplayName("prepares temporary dictionaries from previous quickReplies")
        void execute_withPreviousQuickReplies_createsTemporaryDictionaries() throws Exception {
            MemoryContext ctx = TestMemoryFactory.createWithInput("option1");
            IInputParser parser = mock(IInputParser.class);
            var config = new IInputParser.Config(true, true, true);
            when(parser.getConfig()).thenReturn(config);
            when(parser.normalize(eq("option1"), isNull())).thenReturn("option1");
            when(parser.parse(eq("option1"), isNull(), anyList()))
                    .thenReturn(Collections.emptyList());

            // Set up conversation outputs with previous quickReplies
            // Code reads conversationOutputs.get(size - 2), so we need at least 2 entries
            // Index 0 = previous output (with quickReplies), Index 1 = current output
            List<ConversationOutput> outputs = ctx.conversationOutputs();
            ConversationOutput prevOutput = new ConversationOutput();
            List<Map<String, Object>> quickReplies = new ArrayList<>(List.of(
                    new HashMap<>(Map.of("value", "Option 1", "expressions", "option_1")),
                    new HashMap<>(Map.of("value", "Option 2", "expressions", "option_2"))));
            prevOutput.put("quickReplies", quickReplies);
            outputs.addFirst(prevOutput); // index 0 = prev, index 1 = current

            task.execute(ctx.memory(), parser);

            verify(parser).parse(eq("option1"), isNull(), anyList());
        }
    }

    // ==================== configure() ====================

    @Nested
    @DisplayName("configure()")
    class ConfigureTests {

        @Test
        @DisplayName("sets appendExpressions, includeUnused, includeUnknown from config")
        void configure_parsesConfigFlags() throws Exception {
            Map<String, Object> config = new HashMap<>();
            config.put("appendExpressions", "false");
            config.put("includeUnused", "false");
            config.put("includeUnknown", "false");

            var result = task.configure(config, Collections.emptyMap());

            assertNotNull(result);
            assertTrue(result instanceof IInputParser);
            IInputParser parser = (IInputParser) result;
            assertFalse(parser.getConfig().isAppendExpressions());
            assertFalse(parser.getConfig().isIncludeUnused());
            assertFalse(parser.getConfig().isIncludeUnknown());
        }

        @Test
        @DisplayName("uses default config when no flags specified")
        void configure_defaultFlags() throws Exception {
            var result = task.configure(Collections.emptyMap(), Collections.emptyMap());

            assertNotNull(result);
            IInputParser parser = (IInputParser) result;
            // Defaults are all true
            assertTrue(parser.getConfig().isAppendExpressions());
            assertTrue(parser.getConfig().isIncludeUnused());
            assertTrue(parser.getConfig().isIncludeUnknown());
        }

        @Test
        @DisplayName("throws UnrecognizedExtensionException for unknown normalizer type")
        void configure_unknownNormalizerType_throws() {
            Map<String, Object> extensions = new HashMap<>();
            List<Map<String, Object>> normalizers = List.of(
                    Map.of("type", "eddi://unknown.normalizer"));
            extensions.put("normalizer", normalizers);

            assertThrows(UnrecognizedExtensionException.class,
                    () -> task.configure(Collections.emptyMap(), extensions));
        }

        @Test
        @DisplayName("throws UnrecognizedExtensionException for unknown dictionary type")
        void configure_unknownDictionaryType_throws() {
            Map<String, Object> extensions = new HashMap<>();
            List<Map<String, Object>> dictionaries = List.of(
                    Map.of("type", "eddi://unknown.dictionary"));
            extensions.put("dictionaries", dictionaries);

            assertThrows(UnrecognizedExtensionException.class,
                    () -> task.configure(Collections.emptyMap(), extensions));
        }

        @Test
        @DisplayName("throws UnrecognizedExtensionException for unknown correction type")
        void configure_unknownCorrectionType_throws() {
            Map<String, Object> extensions = new HashMap<>();
            List<Map<String, Object>> corrections = List.of(
                    Map.of("type", "eddi://unknown.correction"));
            extensions.put("corrections", corrections);

            assertThrows(UnrecognizedExtensionException.class,
                    () -> task.configure(Collections.emptyMap(), extensions));
        }

        @Test
        @DisplayName("wires normalizer provider with config map")
        void configure_normalizerWithConfig() throws Exception {
            INormalizerProvider normalizerProvider = mock(INormalizerProvider.class);
            INormalizer normalizer = mock(INormalizer.class);
            when(normalizerProvider.provide(anyMap())).thenReturn(normalizer);

            @SuppressWarnings("unchecked")
            Provider<INormalizerProvider> provider = mock(Provider.class);
            when(provider.get()).thenReturn(normalizerProvider);
            normalizerProviders.put("test.normalizer", provider);

            Map<String, Object> extensions = new HashMap<>();
            Map<String, Object> normalizerEntry = new HashMap<>();
            normalizerEntry.put("type", "eddi://test.normalizer");
            normalizerEntry.put("config", Map.of("key", "value"));
            extensions.put("normalizer", List.of(normalizerEntry));

            var result = task.configure(Collections.emptyMap(), extensions);
            assertNotNull(result);
            verify(normalizerProvider).provide(argThat(map -> "value".equals(map.get("key"))));
        }

        @Test
        @DisplayName("wires dictionary provider without config (empty map fallback)")
        void configure_dictionaryWithoutConfig() throws Exception {
            IDictionaryProvider dictionaryProvider = mock(IDictionaryProvider.class);
            IDictionary dictionary = mock(IDictionary.class);
            when(dictionaryProvider.provide(anyMap())).thenReturn(dictionary);

            @SuppressWarnings("unchecked")
            Provider<IDictionaryProvider> provider = mock(Provider.class);
            when(provider.get()).thenReturn(dictionaryProvider);
            dictionaryProviders.put("test.dictionary", provider);

            Map<String, Object> extensions = new HashMap<>();
            Map<String, Object> dictionaryEntry = new HashMap<>();
            dictionaryEntry.put("type", "eddi://test.dictionary");
            // no "config" key
            extensions.put("dictionaries", List.of(dictionaryEntry));

            var result = task.configure(Collections.emptyMap(), extensions);
            assertNotNull(result);
            verify(dictionaryProvider).provide(eq(Collections.emptyMap()));
        }

        @Test
        @DisplayName("wires correction provider and calls init with dictionaries")
        void configure_correctionInitWithDictionaries() throws Exception {
            ICorrectionProvider correctionProvider = mock(ICorrectionProvider.class);
            ICorrection correction = mock(ICorrection.class);
            when(correctionProvider.provide(anyMap())).thenReturn(correction);

            @SuppressWarnings("unchecked")
            Provider<ICorrectionProvider> provider = mock(Provider.class);
            when(provider.get()).thenReturn(correctionProvider);
            correctionProviders.put("test.correction", provider);

            Map<String, Object> extensions = new HashMap<>();
            extensions.put("corrections", List.of(Map.of("type", "eddi://test.correction")));

            task.configure(Collections.emptyMap(), extensions);
            verify(correction).init(anyList());
        }

        @Test
        @DisplayName("configure with null extensions does not throw")
        void configure_nullExtensions_handledGracefully() throws Exception {
            var result = task.configure(Collections.emptyMap(), null);
            assertNotNull(result);
        }
    }

    // ==================== ExtensionDescriptor ====================

    @Nested
    @DisplayName("ExtensionDescriptor")
    class ExtensionDescriptorTests {

        @Test
        @DisplayName("returns descriptor with correct ID and display name")
        void testExtensionDescriptor() {
            var descriptor = task.getExtensionDescriptor();

            assertNotNull(descriptor);
            assertEquals("ai.labs.parser", descriptor.getType());
            assertEquals("Input Parser", descriptor.getDisplayName());
        }

        @Test
        @DisplayName("descriptor contains appendExpressions, includeUnused, includeUnknown configs")
        void testExtensionDescriptorConfigs() {
            var descriptor = task.getExtensionDescriptor();

            var configs = descriptor.getConfigs();
            assertTrue(configs.containsKey("appendExpressions"));
            assertTrue(configs.containsKey("includeUnused"));
            assertTrue(configs.containsKey("includeUnknown"));
        }

        @Test
        @DisplayName("descriptor includes sub-extensions from registered providers")
        void testExtensionDescriptorWithProviders() {
            // Register a normalizer provider
            INormalizerProvider normalizerProvider = mock(INormalizerProvider.class);
            when(normalizerProvider.getDisplayName()).thenReturn("Test Normalizer");
            when(normalizerProvider.getConfigs()).thenReturn(Map.of());
            @SuppressWarnings("unchecked")
            Provider<INormalizerProvider> provider = mock(Provider.class);
            when(provider.get()).thenReturn(normalizerProvider);
            normalizerProviders.put("test.normalizer", provider);

            var descriptor = task.getExtensionDescriptor();

            assertNotNull(descriptor);
            Map<String, List<ExtensionDescriptor>> extensions = descriptor.getExtensions();
            assertNotNull(extensions.get("normalizer"));
            assertFalse(extensions.get("normalizer").isEmpty());
        }
    }
}
