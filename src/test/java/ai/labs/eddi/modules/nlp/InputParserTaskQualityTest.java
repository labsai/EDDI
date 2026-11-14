/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp;

import ai.labs.eddi.engine.lifecycle.exceptions.IllegalExtensionConfigurationException;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.llm.impl.EmbeddingModelFactory;
import ai.labs.eddi.modules.nlp.expressions.ExpressionFactory;
import ai.labs.eddi.modules.nlp.expressions.utilities.ExpressionProvider;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.RegularDictionary;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.providers.IDictionaryProvider;
import ai.labs.eddi.modules.nlp.extensions.normalizers.providers.INormalizerProvider;
import ai.labs.eddi.modules.nlp.extensions.normalizers.providers.PunctuationNormalizerProvider;
import ai.labs.eddi.modules.nlp.semantic.KeywordEmbeddingModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.inject.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What {@link InputParserTask} stores beyond the expressions — confidence,
 * alternatives, truncation — and the quick-reply and semantic-fallback paths.
 */
@DisplayName("InputParserTask — match quality, quick replies, semantic fallback")
class InputParserTaskQualityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MEMORY_ID = "aabbccddeeff112233445566";

    private final ExpressionProvider expressionProvider = new ExpressionProvider(new ExpressionFactory());
    private final Map<String, Provider<INormalizerProvider>> normalizers = new HashMap<>();
    private final Map<String, Provider<IDictionaryProvider>> dictionaries = new HashMap<>();
    private final EmbeddingModelFactory embeddingModelFactory = mock(EmbeddingModelFactory.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final KeywordEmbeddingModel embeddingModel = new KeywordEmbeddingModel("cancel", "order", "refund");
    private InputParserTask task;

    @BeforeEach
    void setUp() {
        normalizers.put(PunctuationNormalizerProvider.ID, PunctuationNormalizerProvider::new);
        task = new InputParserTask(expressionProvider, normalizers, dictionaries, new HashMap<>(), MAPPER, mock(IResourceClientLibrary.class),
                embeddingModelFactory, meterRegistry);
        when(embeddingModelFactory.getOrCreate(any(), any())).thenReturn(embeddingModel);
    }

    private IInputParser configure(Map<String, Object> config) throws Exception {
        return configure(config, Map.of());
    }

    private IInputParser configure(Map<String, Object> config, Map<String, Object> extensions) throws Exception {
        return (IInputParser) task.configure(new HashMap<>(config), new HashMap<>(extensions));
    }

    /**
     * Registers a dictionary type {@code id} holding one word, and returns the
     * extension entry that selects it.
     */
    private Map<String, Object> dictionaryWith(String id, String word, String expressions) {
        IDictionaryProvider provider = mock(IDictionaryProvider.class);
        when(provider.getId()).thenReturn(id);
        try {
            when(provider.provide(any())).thenAnswer(invocation -> {
                var dictionary = new RegularDictionary();
                dictionary.addWord(word, expressionProvider.parseExpressions(expressions), 0);
                return dictionary;
            });
        } catch (IllegalExtensionConfigurationException e) {
            throw new IllegalStateException(e);
        }
        dictionaries.put(id, () -> provider);
        return Map.of("type", "eddi://" + id);
    }

    private static ConversationMemory turn(String input) {
        var memory = new ConversationMemory(MEMORY_ID, "agent-1", 1, "user-1");
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.INPUT.key(), input));
        return memory;
    }

    private static <T> T stored(ConversationMemory memory, String key) {
        IData<T> data = memory.getCurrentStep().getLatestData(key);
        return data == null ? null : data.getResult();
    }

    @Nested
    @DisplayName("quick replies")
    class QuickReplies {

        private ConversationMemory answering(String offered, String expressions, String input) {
            var memory = new ConversationMemory(MEMORY_ID, "agent-1", 1, "user-1");
            Map<String, Object> quickReply = new HashMap<>();
            quickReply.put("value", offered);
            if (expressions != null) {
                quickReply.put("expressions", expressions);
            }
            memory.getCurrentStep().storeData(new Data<>("quickReplies:offer", List.of(quickReply)));
            memory.startNextStep();
            memory.getCurrentStep().storeData(new Data<>(MemoryKeys.INPUT.key(), input));
            return memory;
        }

        /**
         * The input is normalized before it is parsed, the quick reply's value was not:
         * with the punctuation normalizer, a click on "Yes, please!" arrived as "Yes ,
         * please !" and matched nothing.
         */
        @Test
        @DisplayName("a quick reply with punctuation still matches its own click under the punctuation normalizer")
        void punctuationNormalizer() throws Exception {
            var parser = configure(Map.of(), Map.of("normalizer", List.of(Map.of("type", "eddi://" + PunctuationNormalizerProvider.ID))));
            var memory = answering("Yes, please!", "confirm", "Yes, please!");

            task.execute(memory, parser);

            assertEquals("confirm", stored(memory, MemoryKeys.EXPRESSIONS_PARSED.key()));
        }

        @Test
        @DisplayName("a quick reply in a non-Latin script gets an expression its click produces")
        void nonLatinValue() throws Exception {
            var parser = configure(Map.of());
            var memory = answering("Да", null, "Да");

            task.execute(memory, parser);

            assertEquals("да", stored(memory, MemoryKeys.EXPRESSIONS_PARSED.key()));
        }
    }

    @Test
    @DisplayName("generated quick-reply expressions keep letters of every script and never come out empty")
    void generatedExpressions() {
        assertEquals("größe_m", InputParserTask.generateExpression("Größe M"));
        assertEquals("yes_please", InputParserTask.generateExpression("Yes, please!"));
        assertEquals("日本語", InputParserTask.generateExpression("日本語"));
        assertEquals("👍", InputParserTask.generateExpression("👍"));
        assertEquals("?!", InputParserTask.generateExpression("(?!)"));
    }

    @Nested
    @DisplayName("stored diagnostics")
    class Diagnostics {

        @Test
        @DisplayName("an exact match stores confidence 1.0")
        void confidence() throws Exception {
            var parser = configure(Map.of(), Map.of("dictionaries", List.of(dictionaryWith("greetings", "hello", "greeting"))));
            var memory = turn("hello");

            task.execute(memory, parser);

            assertEquals(1.0, (Double) stored(memory, MemoryKeys.EXPRESSIONS_CONFIDENCE.key()));
            assertNull(stored(memory, MemoryKeys.EXPRESSIONS_INPUT_TRUNCATED.key()));
            assertNull(stored(memory, MemoryKeys.EXPRESSIONS_ALTERNATIVES.key()), "alternatives are off by default");
        }

        @Test
        @DisplayName("nothing understood stores no confidence")
        void noConfidence() throws Exception {
            var memory = turn("xyz");

            task.execute(memory, configure(Map.of()));

            assertEquals("unknown(xyz)", stored(memory, MemoryKeys.EXPRESSIONS_PARSED.key()));
            assertNull(stored(memory, MemoryKeys.EXPRESSIONS_CONFIDENCE.key()));
        }

        @Test
        @DisplayName("an input over maxInputTokens is flagged as truncated")
        void truncated() throws Exception {
            var memory = turn("one two three");

            task.execute(memory, configure(Map.of("maxInputTokens", "2")));

            assertEquals(Boolean.TRUE, stored(memory, MemoryKeys.EXPRESSIONS_INPUT_TRUNCATED.key()));
        }

        @Test
        @DisplayName("maxAlternatives stores the other readings")
        void alternatives() throws Exception {
            var first = dictionaryWith("finance", "bank", "finance");
            var second = dictionaryWith("geography", "bank", "river");
            var parser = configure(Map.of("maxAlternatives", "3"), Map.of("dictionaries", List.of(first, second)));
            var memory = turn("bank");

            task.execute(memory, parser);

            assertEquals("finance", stored(memory, MemoryKeys.EXPRESSIONS_PARSED.key()));
            assertEquals(List.of("river"), stored(memory, MemoryKeys.EXPRESSIONS_ALTERNATIVES.key()));
        }
    }

    @Nested
    @DisplayName("configuration")
    class Configuration {

        @Test
        @DisplayName("the new keys are read, out-of-range values fall back to their defaults")
        void keys() throws Exception {
            var config = configure(Map.of("matchPartialPhrases", "true", "minPartialPhraseCoverage", "0.6", "minMatchingAccuracy", "0.7",
                    "maxAlternatives", "2")).getConfig();
            assertTrue(config.isMatchPartialPhrases());
            assertEquals(0.6, config.getMinPartialPhraseCoverage());
            assertEquals(0.7, config.getMinMatchingAccuracy());
            assertEquals(2, config.getMaxAlternatives());

            var fallback = configure(Map.of("minPartialPhraseCoverage", "0", "minMatchingAccuracy", "1.5", "maxAlternatives", "99")).getConfig();
            assertEquals(IInputParser.Config.DEFAULT_MIN_PARTIAL_PHRASE_COVERAGE, fallback.getMinPartialPhraseCoverage());
            assertEquals(0.0, fallback.getMinMatchingAccuracy());
            assertEquals(InputParserTask.MAX_ALTERNATIVES_LIMIT, fallback.getMaxAlternatives());
        }

        @Test
        @DisplayName("the extension descriptor lists the new keys")
        void descriptor() {
            var configs = task.getExtensionDescriptor().getConfigs();
            for (String key : List.of("matchPartialPhrases", "minPartialPhraseCoverage", "minMatchingAccuracy", "maxAlternatives")) {
                assertTrue(configs.containsKey(key), key);
            }
        }
    }

    @Nested
    @DisplayName("semantic fallback")
    class SemanticFallbackPath {

        private Map<String, Object> fallback(Object examples) {
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("embeddingProvider", "openai");
            fallback.put("embeddingParameters", Map.of("modelName", "text-embedding-3-small"));
            fallback.put("examples", examples);
            return Map.of("semanticFallback", fallback);
        }

        private Map<String, Object> cancelExample() {
            return Map.of("text", "I want to cancel my order", "expressions", "intent(cancel_order)");
        }

        @Test
        @DisplayName("an input the dictionaries do not know is mapped to the closest example")
        void matches() throws Exception {
            var parser = configure(fallback(List.of(cancelExample())));
            var memory = turn("please cancel this order");

            task.execute(memory, parser);

            assertEquals("intent(cancel_order)", stored(memory, MemoryKeys.EXPRESSIONS_PARSED.key()));
            assertEquals(List.of("intent"), stored(memory, MemoryKeys.INTENTS.key()));
            Double confidence = stored(memory, MemoryKeys.EXPRESSIONS_CONFIDENCE.key());
            assertTrue(confidence >= InputParserTask.DEFAULT_SEMANTIC_MIN_SCORE, String.valueOf(confidence));
            List<String> details = stored(memory, MemoryKeys.EXPRESSIONS_MATCHES.key());
            assertTrue(details.getFirst().contains("[semantic"), details.toString());
            assertEquals(1.0, meterRegistry.counter(InputParserTask.METRIC_SEMANTIC_FALLBACK, "outcome", "match").count());
        }

        /**
         * The dictionaries read only the first maxInputTokens tokens, while the
         * fallback was handed the whole input, so words past the limit could still
         * select an example.
         */
        @Test
        @DisplayName("words past maxInputTokens do not reach the fallback either")
        void fallbackHonoursTheTokenLimit() throws Exception {
            Map<String, Object> config = new HashMap<>(fallback(List.of(cancelExample())));
            config.put("maxInputTokens", "2");
            var memory = turn("hello there cancel my order");

            task.execute(memory, configure(config));

            String parsed = stored(memory, MemoryKeys.EXPRESSIONS_PARSED.key());
            assertFalse(String.valueOf(parsed).contains("cancel_order"), String.valueOf(parsed));
        }

        /**
         * Without a conversation language the dictionaries parse in the default one,
         * but the fallback was given no language and accepted examples of any.
         */
        @Test
        @DisplayName("without a conversation language, examples of another language do not match")
        void fallbackUsesTheEffectiveLanguage() throws Exception {
            var german = Map.of("text", "cancel my order", "expressions", "intent(cancel_order)", "lang", "de");
            var memory = turn("cancel order");

            task.execute(memory, configure(fallback(List.of(german))));

            String parsed = stored(memory, MemoryKeys.EXPRESSIONS_PARSED.key());
            assertFalse(String.valueOf(parsed).contains("cancel_order"), String.valueOf(parsed));
        }

        @Test
        @DisplayName("without a conversation language, examples in the default language match")
        void fallbackDefaultsToTheParsersLanguage() throws Exception {
            var english = Map.of("text", "cancel my order", "expressions", "intent(cancel_order)", "lang", "en");
            var memory = turn("cancel order");

            task.execute(memory, configure(fallback(List.of(english))));

            assertEquals("intent(cancel_order)", stored(memory, MemoryKeys.EXPRESSIONS_PARSED.key()));
        }

        @Test
        @DisplayName("no similar example leaves the dictionaries' result")
        void noMatch() throws Exception {
            var parser = configure(fallback(List.of(cancelExample())));
            var memory = turn("lovely weather");

            task.execute(memory, parser);

            assertEquals("unknown(lovely), unknown(weather)", stored(memory, MemoryKeys.EXPRESSIONS_PARSED.key()));
            assertEquals(1.0, meterRegistry.counter(InputParserTask.METRIC_SEMANTIC_FALLBACK, "outcome", "no_match").count());
        }

        @Test
        @DisplayName("a provider failure leaves the dictionaries' result and fails nothing")
        void providerFailure() throws Exception {
            embeddingModel.failure = new IllegalStateException("provider down");
            var parser = configure(fallback(List.of(cancelExample())));
            var memory = turn("cancel order");

            assertDoesNotThrow(() -> task.execute(memory, parser));

            assertEquals("unknown(cancel), unknown(order)", stored(memory, MemoryKeys.EXPRESSIONS_PARSED.key()));
        }

        @Test
        @DisplayName("is not consulted when the dictionaries understood the input")
        void notConsultedWhenUnderstood() throws Exception {
            var fallbackConfig = new HashMap<>(fallback(List.of(cancelExample())));
            var parser = configure(fallbackConfig, Map.of("dictionaries", List.of(dictionaryWith("orders", "cancel", "intent(cancel)"))));
            var memory = turn("cancel");

            task.execute(memory, parser);

            assertEquals("intent(cancel)", stored(memory, MemoryKeys.EXPRESSIONS_PARSED.key()));
            assertEquals(0, embeddingModel.requests.get());
        }

        @Test
        @DisplayName("a broken block fails the deployment, not a conversation")
        void validation() {
            assertThrows(IllegalExtensionConfigurationException.class, () -> configure(Map.of("semanticFallback", Map.of("examples", List.of()))));
            assertThrows(IllegalExtensionConfigurationException.class, () -> configure(fallback(List.of())));
            assertThrows(IllegalExtensionConfigurationException.class,
                    () -> configure(fallback(List.of(Map.of("text", "no expressions here")))));
            Map<String, Object> badScore = new HashMap<>(fallback(List.of(cancelExample())));
            @SuppressWarnings("unchecked")
            var block = new HashMap<>((Map<String, Object>) badScore.get("semanticFallback"));
            block.put("minScore", 1.5);
            assertThrows(IllegalExtensionConfigurationException.class, () -> configure(Map.of("semanticFallback", block)));

            var withoutFactory = new InputParserTask(expressionProvider, normalizers, dictionaries, new HashMap<>(), MAPPER,
                    mock(IResourceClientLibrary.class));
            assertThrows(IllegalExtensionConfigurationException.class,
                    () -> withoutFactory.configure(new HashMap<>(fallback(List.of(cancelExample()))), new HashMap<>()));
        }
    }
}
