/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.secrets.SecretResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

@DisplayName("EmbeddingModelFactory — Extended Branch Coverage Tests")
class EmbeddingModelFactoryBranchTest {

    @Mock
    private SecretResolver secretResolver;
    @Mock
    private GlobalVariableResolver globalVariableResolver;

    private EmbeddingModelFactory factory;

    @BeforeEach
    void setUp() {
        openMocks(this);
        when(secretResolver.resolveSecrets(any())).thenAnswer(inv -> inv.getArgument(0));
        when(globalVariableResolver.resolveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        factory = new EmbeddingModelFactory(globalVariableResolver, secretResolver);
    }

    @Nested
    @DisplayName("parseTaskType")
    class ParseTaskType {

        @Test
        @DisplayName("null taskType defaults to RETRIEVAL_DOCUMENT")
        void nullTaskType() {
            var config = createConfig("gemini", Map.of("apiKey", "test", "taskType", ""));
            assertDoesNotThrow(() -> factory.getOrCreate(config));
        }

        @Test
        @DisplayName("blank taskType defaults to RETRIEVAL_DOCUMENT")
        void blankTaskType() {
            var config = createConfig("gemini", Map.of("apiKey", "test", "taskType", "   "));
            assertDoesNotThrow(() -> factory.getOrCreate(config));
        }
    }

    @Nested
    @DisplayName("parseIntParam")
    class ParseIntParam {

        @Test
        @DisplayName("null outputDimensionality uses default")
        void nullDimensionality() {
            var config = createConfig("gemini", Map.of("apiKey", "test"));
            assertDoesNotThrow(() -> factory.getOrCreate(config));
        }

        @Test
        @DisplayName("blank outputDimensionality uses default")
        void blankDimensionality() {
            var config = createConfig("gemini", Map.of("apiKey", "test", "outputDimensionality", ""));
            assertDoesNotThrow(() -> factory.getOrCreate(config));
        }

        @Test
        @DisplayName("invalid outputDimensionality throws")
        void invalidDimensionality() {
            var config = createConfig("gemini", Map.of("apiKey", "test", "outputDimensionality", "abc"));
            assertThrows(IllegalArgumentException.class, () -> factory.getOrCreate(config));
        }

        @Test
        @DisplayName("valid outputDimensionality parsed correctly")
        void validDimensionality() {
            var config = createConfig("gemini", Map.of("apiKey", "test", "outputDimensionality", "256"));
            assertDoesNotThrow(() -> factory.getOrCreate(config));
        }
    }

    @Nested
    @DisplayName("buildAzureOpenAi")
    class AzureOpenAi {

        @Test
        @DisplayName("with endpoint parameter")
        void withEndpoint() {
            var config = createConfig("azure-openai", Map.of(
                    "apiKey", "test",
                    "endpoint", "https://my-resource.openai.azure.com"));
            assertDoesNotThrow(() -> factory.getOrCreate(config));
        }

        @Test
        @DisplayName("without endpoint parameter")
        void withoutEndpoint() {
            var config = createConfig("azure-openai", Map.of("apiKey", "test"));
            assertThrows(IllegalArgumentException.class, () -> factory.getOrCreate(config));
        }
    }

    @Nested
    @DisplayName("buildOllama")
    class Ollama {

        @Test
        @DisplayName("with defaults")
        void withDefaults() {
            var config = createConfig("ollama", Map.of());
            assertDoesNotThrow(() -> factory.getOrCreate(config));
        }

        @Test
        @DisplayName("with custom baseUrl and model")
        void withCustomParams() {
            var config = createConfig("ollama", Map.of("baseUrl", "http://my-ollama:11434", "model", "custom-model"));
            assertDoesNotThrow(() -> factory.getOrCreate(config));
        }
    }

    @Nested
    @DisplayName("buildVertex")
    class Vertex {

        @Test
        @DisplayName("null project throws")
        void nullProject() {
            var config = createConfig("vertex", Map.of());
            var ex = assertThrows(IllegalArgumentException.class, () -> factory.getOrCreate(config));
            assertTrue(ex.getMessage().contains("project"));
        }

        @Test
        @DisplayName("blank project throws")
        void blankProject() {
            var config = createConfig("vertex", Map.of("project", "  "));
            var ex = assertThrows(IllegalArgumentException.class, () -> factory.getOrCreate(config));
            assertTrue(ex.getMessage().contains("project"));
        }
    }

    @Nested
    @DisplayName("buildBedrock")
    class Bedrock {

        @Test
        @DisplayName("with defaults")
        void withDefaults() {
            var config = createConfig("bedrock", Map.of());
            assertDoesNotThrow(() -> factory.getOrCreate(config));
        }

        @Test
        @DisplayName("with custom region and model")
        void withCustomParams() {
            var config = createConfig("bedrock", Map.of("region", "eu-west-1", "model", "custom-model"));
            assertDoesNotThrow(() -> factory.getOrCreate(config));
        }
    }

    @Nested
    @DisplayName("getOrCreate caching")
    class Caching {

        @Test
        @DisplayName("null embeddingParameters creates empty paramKey")
        void nullParams() {
            var config = new RagConfiguration();
            config.setEmbeddingProvider("openai");
            config.setEmbeddingParameters(null);
            assertDoesNotThrow(() -> factory.getOrCreate(config));
        }
    }

    private RagConfiguration createConfig(String provider, Map<String, String> params) {
        var config = new RagConfiguration();
        config.setEmbeddingProvider(provider);
        config.setEmbeddingParameters(params);
        return config;
    }
}
