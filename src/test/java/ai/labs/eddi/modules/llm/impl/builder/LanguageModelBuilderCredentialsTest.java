/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * What each builder declares as its credential parameters (the only keys a
 * vault reference may sit in) and as its default endpoint (checked against a
 * secret's {@code allowedHosts} when the configuration names none). Neither
 * builds a client, so no socket is needed.
 */
@DisplayName("Language model builders — credential keys and default endpoints")
class LanguageModelBuilderCredentialsTest {

    @Nested
    @DisplayName("secretParameters")
    class SecretParameters {

        @Test
        void vendorApiKeyProviders() {
            Set<String> apiKeyAndHeaders = Set.of("apiKey", "customHeaders");
            assertEquals(Set.of("apiKey", "customHeaders", "customQueryParams"), new OpenAILanguageModelBuilder().secretParameters());
            assertEquals(apiKeyAndHeaders, new AnthropicLanguageModelBuilder().secretParameters());
            assertEquals(apiKeyAndHeaders, new GeminiLanguageModelBuilder().secretParameters());
            assertEquals(apiKeyAndHeaders, new MistralAiLanguageModelBuilder().secretParameters());
        }

        @Test
        void azureTakesBothKeys() {
            assertEquals(Set.of("apiKey", "nonAzureApiKey", "customHeaders"), new AzureOpenAiLanguageModelBuilder().secretParameters());
        }

        @Test
        void tokenProviders() {
            assertEquals(Set.of("accessToken"), new HuggingFaceLanguageModelBuilder().secretParameters());
            assertEquals(Set.of("authToken"), new JlamaLanguageModelBuilder().secretParameters());
        }

        @Test
        void cloudCredentialChainProvidersTakeHeadersOnly() {
            assertEquals(Set.of("customHeaders"), new OllamaLanguageModelBuilder().secretParameters());
            assertEquals(Set.of("customHeaders"), new BedrockLanguageModelBuilder().secretParameters());
            assertEquals(Set.of("customHeaders"), new VertexGeminiLanguageModelBuilder().secretParameters());
            assertEquals(Set.of(), new OracleGenAiLanguageModelBuilder().secretParameters());
        }

        @Test
        void openAiCompatibleTypesUseTheOpenAiKeys() {
            var delegate = new OpenAILanguageModelBuilder();
            assertEquals(delegate.secretParameters(),
                    new OpenAiCompatibleLanguageModelBuilder(OpenAiCompatibleProviders.find("xai").orElseThrow(), delegate).secretParameters());
            assertEquals(delegate.secretParameters(), new SelfHostedOpenAiLanguageModelBuilder(delegate).secretParameters());
        }

        @Test
        @DisplayName("model names and endpoints are never credentials")
        void modelAndEndpointKeysAreNot() {
            for (ILanguageModelBuilder builder : new ILanguageModelBuilder[]{new OpenAILanguageModelBuilder(),
                    new AnthropicLanguageModelBuilder(), new AzureOpenAiLanguageModelBuilder(), new OllamaLanguageModelBuilder(),
                    new JlamaLanguageModelBuilder()}) {
                for (String key : new String[]{"modelName", "model", "modelId", "deploymentName", "baseUrl", "endpoint"}) {
                    assertFalse(builder.secretParameters().contains(key), builder.getClass().getSimpleName() + " " + key);
                }
            }
        }
    }

    @Nested
    @DisplayName("defaultEndpoint")
    class DefaultEndpoint {

        @Test
        void vendorHostsWhenNoBaseUrl() {
            assertEquals("https://api.openai.com/v1", new OpenAILanguageModelBuilder().defaultEndpoint(Map.of()));
            assertEquals("https://api.anthropic.com/v1/", new AnthropicLanguageModelBuilder().defaultEndpoint(Map.of()));
            assertEquals("https://generativelanguage.googleapis.com/v1beta", new GeminiLanguageModelBuilder().defaultEndpoint(Map.of()));
            assertEquals("https://api.mistral.ai/v1", new MistralAiLanguageModelBuilder().defaultEndpoint(Map.of()));
            assertEquals("https://router.huggingface.co/hf-inference/", new HuggingFaceLanguageModelBuilder().defaultEndpoint(Map.of()));
            assertEquals("http://localhost:11434", new OllamaLanguageModelBuilder().defaultEndpoint(Map.of()));
            assertEquals("https://huggingface.co", new JlamaLanguageModelBuilder().defaultEndpoint(Map.of()));
        }

        @Test
        @DisplayName("none once the key the builder reads is set")
        void noDefaultOnceConfigured() {
            Map<String, String> baseUrl = Map.of("baseUrl", "https://proxy.example/v1");
            assertNull(new OpenAILanguageModelBuilder().defaultEndpoint(baseUrl));
            assertNull(new AnthropicLanguageModelBuilder().defaultEndpoint(baseUrl));
            assertNull(new GeminiLanguageModelBuilder().defaultEndpoint(baseUrl));
            assertNull(new MistralAiLanguageModelBuilder().defaultEndpoint(baseUrl));
            assertNull(new HuggingFaceLanguageModelBuilder().defaultEndpoint(baseUrl));
            assertNull(new OllamaLanguageModelBuilder().defaultEndpoint(baseUrl));
            assertNull(new AzureOpenAiLanguageModelBuilder().defaultEndpoint(Map.of("endpoint", "https://r.openai.azure.com/")));
            assertNull(new VertexGeminiLanguageModelBuilder().defaultEndpoint(Map.of("apiEndpoint", "x.example:443", "location", "eu")));
        }

        @Test
        void azureWithoutEndpointGoesToOpenAi() {
            assertEquals("https://api.openai.com/v1", new AzureOpenAiLanguageModelBuilder().defaultEndpoint(Map.of("nonAzureApiKey", "k")));
        }

        @Test
        void regionalHosts() {
            assertEquals("https://bedrock-runtime.us-east-1.amazonaws.com", new BedrockLanguageModelBuilder().defaultEndpoint(Map.of()));
            assertEquals("https://bedrock-runtime.eu-west-1.amazonaws.com",
                    new BedrockLanguageModelBuilder().defaultEndpoint(Map.of("region", "eu-west-1")));
            assertEquals("https://europe-west4-aiplatform.googleapis.com",
                    new VertexGeminiLanguageModelBuilder().defaultEndpoint(Map.of("location", "europe-west4")));
            assertNull(new VertexGeminiLanguageModelBuilder().defaultEndpoint(Map.of()));
        }
    }
}
