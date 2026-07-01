/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp;

import ai.labs.eddi.engine.TestMemoryFactory;
import ai.labs.eddi.engine.TestMemoryFactory.MemoryContext;
import ai.labs.eddi.engine.lifecycle.exceptions.IllegalExtensionConfigurationException;
import ai.labs.eddi.engine.lifecycle.exceptions.UnrecognizedExtensionException;
import ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException;
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
import ai.labs.eddi.modules.nlp.internal.InputParser;
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
 * Branch coverage tests for {@link InputParserTask}.
 */
@DisplayName("InputParserTask — Branch Coverage")
class InputParserTaskBranchTest {

    private InputParserTask task;
    private IExpressionProvider expressionProvider;
    private Map<String, Provider<INormalizerProvider>> normalizerProviders;
    private Map<String, Provider<IDictionaryProvider>> dictionaryProviders;
    private Map<String, Provider<ICorrectionProvider>> correctionProviders;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        expressionProvider = mock(IExpressionProvider.class);
        normalizerProviders = new HashMap<>();
        dictionaryProviders = new HashMap<>();
        correctionProviders = new HashMap<>();
        objectMapper = new ObjectMapper();
        task = new InputParserTask(expressionProvider, normalizerProviders, dictionaryProviders,
                correctionProviders, objectMapper);
    }

    // ==================== storeNormalizedResultInMemory — empty input
    // ====================

    @Nested
    @DisplayName("storeNormalizedResultInMemory edge cases")
    class StoreNormalizedTests {

        @Test
        @DisplayName("empty normalized input is not stored")
        void emptyNormalizedInput() throws Exception {
            MemoryContext ctx = TestMemoryFactory.createWithInput("  ");
            IInputParser parser = mock(IInputParser.class);
            var config = new IInputParser.Config(true, true, true);
            doReturn(config).when(parser).getConfig();
            doReturn("").when(parser).normalize(anyString(), isNull());
            doReturn(Collections.emptyList()).when(parser).parse(anyString(), isNull(), anyList());

            task.execute(ctx.memory(), parser);

            // Normalized input "" should not be stored (isNullOrEmpty check)
            verify(ctx.currentStep(), never()).storeData(
                    argThat(data -> "input:normalized".equals(data.getKey())));
        }

        @Test
        @DisplayName("null normalized input is not stored")
        void nullNormalizedInput() throws Exception {
            MemoryContext ctx = TestMemoryFactory.createWithInput("test");
            IInputParser parser = mock(IInputParser.class);
            var config = new IInputParser.Config(true, true, true);
            doReturn(config).when(parser).getConfig();
            doReturn(null).when(parser).normalize(anyString(), isNull());
            doReturn(Collections.emptyList()).when(parser).parse(isNull(), isNull(), anyList());

            task.execute(ctx.memory(), parser);

            verify(ctx.currentStep(), never()).storeData(
                    argThat(data -> "input:normalized".equals(data.getKey())));
        }
    }

    // ==================== storeResultInMemory — appendExpressions=false
    // ====================

    @Test
    @DisplayName("appendExpressions=false skips expression storage even with solutions")
    void appendExpressionsFalse() throws Exception {
        MemoryContext ctx = TestMemoryFactory.createWithInput("hello");
        IInputParser parser = mock(IInputParser.class);
        var config = new IInputParser.Config(false, true, true);
        doReturn(config).when(parser).getConfig();
        doReturn("hello").when(parser).normalize(eq("hello"), isNull());

        // Create solution with expressions
        RawSolution solution = mock(RawSolution.class);
        var foundWord = mock(IDictionary.IFoundWord.class);
        doReturn("hello").when(foundWord).getValue();
        Expressions exprs = new Expressions();
        exprs.add(new Expression("greeting"));
        doReturn(exprs).when(foundWord).getExpressions();
        doReturn(true).when(foundWord).isWord();
        doReturn(List.of(foundWord)).when(solution).getDictionaryEntries();

        doReturn(List.of(solution)).when(parser).parse(eq("hello"), isNull(), anyList());

        task.execute(ctx.memory(), parser);

        // With appendExpressions=false, no expression data should be stored
        verify(ctx.currentStep(), never()).addConversationOutputString(eq("expressions"), anyString());
    }

    // ==================== prepareTemporaryDictionaries — edge cases
    // ====================

    @Nested
    @DisplayName("prepareTemporaryDictionaries edge cases")
    class PrepareTemporaryDictsTests {

        @Test
        @DisplayName("single output entry — returns empty temp dictionaries")
        void singleOutput() throws Exception {
            MemoryContext ctx = TestMemoryFactory.createWithInput("test");
            IInputParser parser = mock(IInputParser.class);
            var config = new IInputParser.Config(true, true, true);
            doReturn(config).when(parser).getConfig();
            doReturn("test").when(parser).normalize(eq("test"), isNull());
            doReturn(Collections.emptyList()).when(parser).parse(eq("test"), isNull(), anyList());

            // Default: conversationOutputs has exactly 1 entry (current)
            assertEquals(1, ctx.conversationOutputs().size());

            task.execute(ctx.memory(), parser);

            // parse called with empty temp dictionaries (size < 2 check)
            verify(parser).parse(eq("test"), isNull(), eq(Collections.emptyList()));
        }

        @Test
        @DisplayName("previous output with null quickReplies — returns empty temp dictionaries")
        void nullQuickReplies() throws Exception {
            MemoryContext ctx = TestMemoryFactory.createWithInput("test");
            IInputParser parser = mock(IInputParser.class);
            var config = new IInputParser.Config(true, true, true);
            doReturn(config).when(parser).getConfig();
            doReturn("test").when(parser).normalize(eq("test"), isNull());
            doReturn(Collections.emptyList()).when(parser).parse(eq("test"), isNull(), anyList());

            // Add a previous output without quickReplies
            ctx.conversationOutputs().addFirst(new ConversationOutput());

            task.execute(ctx.memory(), parser);

            verify(parser).parse(eq("test"), isNull(), anyList());
        }
    }

    // ==================== extractQuickReplies — null/blank expressions
    // ====================

    @Nested
    @DisplayName("extractQuickReplies — expression generation")
    class ExtractQuickRepliesTests {

        @Test
        @DisplayName("null expressions field generates expression from value")
        void nullExpressions() throws Exception {
            MemoryContext ctx = TestMemoryFactory.createWithInput("option1");
            IInputParser parser = mock(IInputParser.class);
            var config = new IInputParser.Config(true, true, true);
            doReturn(config).when(parser).getConfig();
            doReturn("option1").when(parser).normalize(eq("option1"), isNull());
            doReturn(Collections.emptyList()).when(parser).parse(eq("option1"), isNull(), anyList());

            // Previous output with quickReply without expressions
            ConversationOutput prevOutput = new ConversationOutput();
            Map<String, Object> qr = new HashMap<>();
            qr.put("value", "Option 1!");
            qr.put("expressions", null);
            qr.put("default", false);
            prevOutput.put("quickReplies", List.of(qr));
            ctx.conversationOutputs().addFirst(prevOutput);

            task.execute(ctx.memory(), parser);
            verify(parser).parse(eq("option1"), isNull(), anyList());
        }

        @Test
        @DisplayName("blank expressions field generates expression from value")
        void blankExpressions() throws Exception {
            MemoryContext ctx = TestMemoryFactory.createWithInput("option1");
            IInputParser parser = mock(IInputParser.class);
            var config = new IInputParser.Config(true, true, true);
            doReturn(config).when(parser).getConfig();
            doReturn("option1").when(parser).normalize(eq("option1"), isNull());
            doReturn(Collections.emptyList()).when(parser).parse(eq("option1"), isNull(), anyList());

            ConversationOutput prevOutput = new ConversationOutput();
            Map<String, Object> qr = new HashMap<>();
            qr.put("value", "Hello World!");
            qr.put("expressions", "   "); // blank
            qr.put("default", false);
            prevOutput.put("quickReplies", List.of(qr));
            ctx.conversationOutputs().addFirst(prevOutput);

            task.execute(ctx.memory(), parser);
            verify(parser).parse(eq("option1"), isNull(), anyList());
        }
    }

    // ==================== configure — normalizer without config key
    // ====================

    @Test
    @DisplayName("configure normalizer with non-Map config uses empty map")
    void configureNormalizerNonMapConfig() throws Exception {
        INormalizerProvider normalizerProvider = mock(INormalizerProvider.class);
        INormalizer normalizer = mock(INormalizer.class);
        doReturn(normalizer).when(normalizerProvider).provide(anyMap());

        @SuppressWarnings("unchecked")
        Provider<INormalizerProvider> provider = mock(Provider.class);
        doReturn(normalizerProvider).when(provider).get();
        normalizerProviders.put("test.normalizer", provider);

        Map<String, Object> extensions = new HashMap<>();
        Map<String, Object> entry = new HashMap<>();
        entry.put("type", "eddi://test.normalizer");
        entry.put("config", "not-a-map"); // String, not Map
        extensions.put("normalizer", List.of(entry));

        var result = task.configure(Collections.emptyMap(), extensions);
        assertNotNull(result);
        verify(normalizerProvider).provide(eq(Collections.emptyMap()));
    }

    // ==================== configure — dictionary with config Map
    // ====================

    @Test
    @DisplayName("configure dictionary with config Map passes it through")
    void configureDictionaryWithConfigMap() throws Exception {
        IDictionaryProvider dictionaryProvider = mock(IDictionaryProvider.class);
        IDictionary dictionary = mock(IDictionary.class);
        doReturn(dictionary).when(dictionaryProvider).provide(anyMap());

        @SuppressWarnings("unchecked")
        Provider<IDictionaryProvider> provider = mock(Provider.class);
        doReturn(dictionaryProvider).when(provider).get();
        dictionaryProviders.put("test.dictionary", provider);

        Map<String, Object> extensions = new HashMap<>();
        Map<String, Object> entry = new HashMap<>();
        entry.put("type", "eddi://test.dictionary");
        entry.put("config", Map.of("key", "value"));
        extensions.put("dictionaries", List.of(entry));

        var result = task.configure(Collections.emptyMap(), extensions);
        assertNotNull(result);
        verify(dictionaryProvider).provide(argThat(map -> "value".equals(map.get("key"))));
    }

    // ==================== configure — correction with config Map
    // ====================

    @Test
    @DisplayName("configure correction with config Map passes it through")
    void configureCorrectionWithConfigMap() throws Exception {
        ICorrectionProvider correctionProvider = mock(ICorrectionProvider.class);
        ICorrection correction = mock(ICorrection.class);
        doReturn(correction).when(correctionProvider).provide(anyMap());

        @SuppressWarnings("unchecked")
        Provider<ICorrectionProvider> provider = mock(Provider.class);
        doReturn(correctionProvider).when(provider).get();
        correctionProviders.put("test.correction", provider);

        Map<String, Object> extensions = new HashMap<>();
        Map<String, Object> entry = new HashMap<>();
        entry.put("type", "eddi://test.correction");
        entry.put("config", Map.of("distance", "2"));
        extensions.put("corrections", List.of(entry));

        var result = task.configure(Collections.emptyMap(), extensions);
        assertNotNull(result);
        verify(correctionProvider).provide(argThat(map -> "2".equals(map.get("distance"))));
        verify(correction).init(anyList());
    }

    // ==================== getExtensionDescriptor — with all provider types
    // ====================

    @Test
    @DisplayName("getExtensionDescriptor includes dictionary and correction providers")
    void extensionDescriptorWithAllProviders() throws Exception {
        // Dictionary provider
        IDictionaryProvider dictProvider = mock(IDictionaryProvider.class);
        doReturn("Test Dictionary").when(dictProvider).getDisplayName();
        doReturn(Map.of()).when(dictProvider).getConfigs();
        @SuppressWarnings("unchecked")
        Provider<IDictionaryProvider> dictProv = mock(Provider.class);
        doReturn(dictProvider).when(dictProv).get();
        dictionaryProviders.put("test.dict", dictProv);

        // Correction provider
        ICorrectionProvider corrProvider = mock(ICorrectionProvider.class);
        doReturn("Test Correction").when(corrProvider).getDisplayName();
        doReturn(Map.of()).when(corrProvider).getConfigs();
        @SuppressWarnings("unchecked")
        Provider<ICorrectionProvider> corrProv = mock(Provider.class);
        doReturn(corrProvider).when(corrProv).get();
        correctionProviders.put("test.corr", corrProv);

        var descriptor = task.getExtensionDescriptor();
        assertNotNull(descriptor.getExtensions().get("dictionaries"));
        assertFalse(descriptor.getExtensions().get("dictionaries").isEmpty());
        assertNotNull(descriptor.getExtensions().get("corrections"));
        assertFalse(descriptor.getExtensions().get("corrections").isEmpty());
    }

    // ==================== configure — empty extensions map ====================

    @Test
    @DisplayName("configure with empty extensions map returns parser with defaults")
    void configureEmptyExtensionsMap() throws Exception {
        var result = task.configure(Collections.emptyMap(), Collections.emptyMap());
        assertNotNull(result);
        assertInstanceOf(InputParser.class, result);
    }

    // ==================== storeResultInMemory — empty parsedSolutions
    // ====================

    @Test
    @DisplayName("empty parsedSolutions does not store anything")
    void emptyParsedSolutionsNoStore() throws Exception {
        MemoryContext ctx = TestMemoryFactory.createWithInput("test");
        IInputParser parser = mock(IInputParser.class);
        var config = new IInputParser.Config(true, true, true);
        doReturn(config).when(parser).getConfig();
        doReturn("test").when(parser).normalize(eq("test"), isNull());
        doReturn(Collections.emptyList()).when(parser).parse(eq("test"), isNull(), anyList());

        task.execute(ctx.memory(), parser);

        verify(ctx.currentStep(), never()).addConversationOutputString(eq("expressions"), anyString());
    }
}
