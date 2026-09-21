/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp;

import ai.labs.eddi.engine.TestMemoryFactory;
import ai.labs.eddi.engine.TestMemoryFactory.MemoryContext;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import ai.labs.eddi.modules.nlp.extensions.corrections.providers.ICorrectionProvider;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.providers.IDictionaryProvider;
import ai.labs.eddi.modules.nlp.extensions.normalizers.providers.INormalizerProvider;
import ai.labs.eddi.modules.nlp.internal.InputParser;
import ai.labs.eddi.modules.nlp.internal.Limits;
import ai.labs.eddi.modules.nlp.internal.matches.RawSolution;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.engine.memory.MemoryKeys.EXPRESSIONS_PARSED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers how {@link InputParserTask} writes parse results into conversation
 * memory, and how the parser's safety limits are read from the workflow
 * configuration.
 */
@DisplayName("InputParserTask — result storage & parser limits")
class InputParserTaskExpressionStorageTest {

    private InputParserTask task;
    private IExpressionProvider expressionProvider;

    @BeforeEach
    void setUp() {
        expressionProvider = mock(IExpressionProvider.class);
        Map<String, Provider<INormalizerProvider>> normalizerProviders = new HashMap<>();
        Map<String, Provider<IDictionaryProvider>> dictionaryProviders = new HashMap<>();
        Map<String, Provider<ICorrectionProvider>> correctionProviders = new HashMap<>();
        task = new InputParserTask(expressionProvider, normalizerProviders, dictionaryProviders, correctionProviders, new ObjectMapper());
    }

    // ==================== E14: appendExpressions must not disable storage
    // ====================

    @Test
    @DisplayName("appendExpressions=false still stores parsed expressions")
    void appendExpressionsDisabled_stillStoresExpressions() throws Exception {
        MemoryContext ctx = TestMemoryFactory.createWithInput("hello");
        IInputParser parser = parserYielding("hello", new IInputParser.Config(false, true, true), greeting("hello"));

        task.execute(ctx.memory(), parser);

        verify(ctx.currentStep())
                .storeData(argThat(data -> "expressions:parsed".equals(data.getKey()) && "greeting(hello)".equals(data.getResult())));
        verify(ctx.currentStep()).addConversationOutputString("expressions", "greeting(hello)");
    }

    @Test
    @DisplayName("appendExpressions=false still stores the derived intents")
    void appendExpressionsDisabled_stillStoresIntents() throws Exception {
        MemoryContext ctx = TestMemoryFactory.createWithInput("hello");
        IInputParser parser = parserYielding("hello", new IInputParser.Config(false, true, true), greeting("hello"));

        task.execute(ctx.memory(), parser);

        verify(ctx.currentStep()).storeData(argThat(data -> "intents".equals(data.getKey()) && List.of("greeting").equals(data.getResult())));
        verify(ctx.currentStep()).addConversationOutputList("intents", List.of("greeting"));
    }

    @Test
    @DisplayName("appendExpressions=false replaces, it does not merge with earlier expressions")
    void appendExpressionsDisabled_doesNotMergeWithEarlierExpressions() throws Exception {
        MemoryContext ctx = TestMemoryFactory.createWithInput("hello");
        givenExpressionsAlreadyOnStep(ctx, "greeting(hi)");
        IInputParser parser = parserYielding("hello", new IInputParser.Config(false, true, true), greeting("hello"));

        task.execute(ctx.memory(), parser);

        verify(ctx.currentStep())
                .storeData(argThat(data -> "expressions:parsed".equals(data.getKey()) && "greeting(hello)".equals(data.getResult())));
    }

    @Test
    @DisplayName("appendExpressions=true merges with earlier expressions")
    void appendExpressionsEnabled_mergesWithEarlierExpressions() throws Exception {
        MemoryContext ctx = TestMemoryFactory.createWithInput("hello");
        givenExpressionsAlreadyOnStep(ctx, "greeting(hi)");
        when(expressionProvider.parseExpressions("greeting(hi)")).thenReturn(greeting("hi"));
        IInputParser parser = parserYielding("hello", new IInputParser.Config(true, true, true), greeting("hello"));

        task.execute(ctx.memory(), parser);

        verify(ctx.currentStep()).storeData(
                argThat(data -> "expressions:parsed".equals(data.getKey()) && "greeting(hi), greeting(hello)".equals(data.getResult())));
    }

    // ==================== E17: configurable parser limits ====================

    @Test
    @DisplayName("configure applies the default limits when none are configured")
    void configure_defaultLimits() throws Exception {
        var parser = (InputParser) task.configure(Map.of(), Map.of());

        assertEquals(Limits.DEFAULT, parser.getLimits());
    }

    @Test
    @DisplayName("configure reads maxInputTokens, maxSuggestions and maxSolutions")
    void configure_readsLimitsFromConfiguration() throws Exception {
        Map<String, Object> configuration = Map.of("maxInputTokens", "12", "maxSuggestions", "34", "maxSolutions", "56");

        var parser = (InputParser) task.configure(configuration, Map.of());

        assertEquals(new Limits(12, 34, 56), parser.getLimits());
    }

    @Test
    @DisplayName("configure falls back to defaults for non-positive or unparseable limits")
    void configure_invalidLimitsFallBackToDefaults() throws Exception {
        Map<String, Object> configuration = Map.of("maxInputTokens", "0", "maxSuggestions", "not-a-number", "maxSolutions", "-3");

        var parser = (InputParser) task.configure(configuration, Map.of());

        assertEquals(Limits.DEFAULT, parser.getLimits());
    }

    @Test
    @DisplayName("extension descriptor exposes the limit configs so agent designers can tune them")
    void extensionDescriptor_exposesLimitConfigs() {
        var configs = task.getExtensionDescriptor().getConfigs();

        assertTrue(configs.containsKey("maxInputTokens"));
        assertTrue(configs.containsKey("maxSuggestions"));
        assertTrue(configs.containsKey("maxSolutions"));
    }

    // ==================== helpers ====================

    private static Expressions greeting(String value) {
        return new Expressions(new Expression("greeting", new Expression(value)));
    }

    private static void givenExpressionsAlreadyOnStep(MemoryContext ctx, String expressions) {
        IData<String> previous = new Data<>(EXPRESSIONS_PARSED.key(), expressions);
        when(ctx.currentStep().getLatestData(eq(EXPRESSIONS_PARSED))).thenReturn(previous);
    }

    private static IInputParser parserYielding(String normalizedInput, IInputParser.Config config, Expressions expressions) throws Exception {
        IInputParser parser = mock(IInputParser.class);
        when(parser.getConfig()).thenReturn(config);
        when(parser.normalize(anyString(), any())).thenReturn(normalizedInput);

        IDictionary.IFoundWord foundWord = mock(IDictionary.IFoundWord.class);
        when(foundWord.getExpressions()).thenReturn(expressions);
        RawSolution rawSolution = mock(RawSolution.class);
        when(rawSolution.getDictionaryEntries()).thenReturn(List.of(foundWord));
        when(parser.parse(anyString(), any(), anyList())).thenReturn(List.of(rawSolution));

        return parser;
    }
}
