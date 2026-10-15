/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * The catalog describes exactly what the builders read.
 * <p>
 * A catalog key the builder never reads would be offered in the Manager and
 * then silently dropped; a builder key missing from the catalog would never be
 * offered at all. Both directions fail here, per provider, against the real
 * builders' {@code recognisedParameters()} — minus the keys every provider form
 * handles itself ({@link ModelParameterCatalog#dedicatedKeys}).
 */
@DisplayName("model-parameters.json matches the builders")
class ModelParameterCatalogDriftTest {

    private static final ModelParameterCatalog CATALOG = ModelParameterCatalog.defaultCatalog();

    static Stream<Arguments> firstPartyBuilders() {
        return Stream.of(
                builder("openai", OpenAILanguageModelBuilder::new),
                builder("anthropic", AnthropicLanguageModelBuilder::new),
                builder("gemini", GeminiLanguageModelBuilder::new),
                builder("gemini-vertex", VertexGeminiLanguageModelBuilder::new),
                builder("azure-openai", AzureOpenAiLanguageModelBuilder::new),
                builder("mistral", MistralAiLanguageModelBuilder::new),
                builder("ollama", OllamaLanguageModelBuilder::new),
                builder("bedrock", BedrockLanguageModelBuilder::new),
                builder("huggingface", HuggingFaceLanguageModelBuilder::new),
                builder("jlama", JlamaLanguageModelBuilder::new),
                builder("oracle-genai", OracleGenAiLanguageModelBuilder::new));
    }

    private static Arguments builder(String type, Supplier<ILanguageModelBuilder> factory) {
        return arguments(type, factory);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("firstPartyBuilders")
    @DisplayName("first-party builder: catalog keys == recognised keys minus dedicated ones")
    void firstParty(String type, Supplier<ILanguageModelBuilder> factory) {
        Set<String> expected = new TreeSet<>(factory.get().recognisedParameters());
        expected.removeAll(ModelParameterCatalog.dedicatedKeys(type));
        assertEquals(expected, new TreeSet<>(CATALOG.keys(type)), type);
    }

    static Stream<String> compatibleProviders() {
        return OpenAiCompatibleProviders.all().stream().map(OpenAiCompatibleProvider::id);
    }

    /**
     * An OpenAI-compatible provider's builder recognises every catalog key by
     * construction, so the meaningful check is the other way round: every non-body,
     * non-header key must be one the OpenAI delegate actually reads, since nothing
     * else would ever send it.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("compatibleProviders")
    @DisplayName("OpenAI-compatible: catalog keys == delegate keys + vendor body/header keys")
    void compatible(String type) {
        OpenAILanguageModelBuilder delegate = new OpenAILanguageModelBuilder();
        Set<String> expected = new TreeSet<>(delegate.recognisedParameters());
        for (ModelParameterCatalog.Param p : CATALOG.params(type)) {
            if (p.body() || p.header() != null) {
                expected.add(p.key());
            }
        }
        expected.removeAll(ModelParameterCatalog.dedicatedKeys(type));
        assertEquals(expected, new TreeSet<>(CATALOG.keys(type)), type);

        var builder = new OpenAiCompatibleLanguageModelBuilder(OpenAiCompatibleProviders.find(type).orElseThrow(), delegate);
        Set<String> recognised = new TreeSet<>(builder.recognisedParameters());
        recognised.removeAll(ModelParameterCatalog.dedicatedKeys(type));
        assertEquals(expected, recognised, type + " builder");
    }

    @Test
    @DisplayName("the catalog describes no provider type EDDI does not ship")
    void noStrayTypes() {
        Set<String> shipped = new TreeSet<>();
        firstPartyBuilders().forEach(a -> shipped.add((String) a.get()[0]));
        compatibleProviders().forEach(shipped::add);
        assertEquals(shipped, new TreeSet<>(CATALOG.types()));
    }
}
