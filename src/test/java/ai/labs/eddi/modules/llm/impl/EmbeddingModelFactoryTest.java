/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.secrets.SecretResolver;
import dev.langchain4j.model.embedding.EmbeddingModel;
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

class EmbeddingModelFactoryTest {

    @Mock
    private SecretResolver secretResolver;

    private EmbeddingModelFactory factory;

    @BeforeEach
    void setUp() {
        openMocks(this);
        when(secretResolver.resolveSecrets(any())).thenAnswer(inv -> inv.getArgument(0));
        factory = new EmbeddingModelFactory(secretResolver);
    }

    @Test
    void sameConfig_shouldReturnCachedInstance() {
        var config = createConfig("openai", Map.of("model", "text-embedding-3-small", "apiKey", "test-key"));

        EmbeddingModel model1 = factory.getOrCreate(config);
        EmbeddingModel model2 = factory.getOrCreate(config);

        assertSame(model1, model2, "Same config should return cached instance");
    }

    @Test
    void differentParams_shouldReturnDifferentInstances() {
        var config1 = createConfig("openai", Map.of("model", "text-embedding-3-small", "apiKey", "key1"));
        var config2 = createConfig("openai", Map.of("model", "text-embedding-3-large", "apiKey", "key2"));

        EmbeddingModel model1 = factory.getOrCreate(config1);
        EmbeddingModel model2 = factory.getOrCreate(config2);

        assertNotSame(model1, model2, "Different params should return different instances");
    }

    @Test
    void clearCache_shouldEvictEntries() {
        var config = createConfig("openai", Map.of("model", "text-embedding-3-small", "apiKey", "test-key"));
        EmbeddingModel before = factory.getOrCreate(config);

        factory.clearCache();
        EmbeddingModel after = factory.getOrCreate(config);

        assertNotSame(before, after, "After clearing cache, a new instance should be created");
    }

    @Test
    void unsupportedProvider_shouldThrow() {
        var config = createConfig("unsupported_provider", Map.of());

        var ex = assertThrows(IllegalArgumentException.class, () -> factory.getOrCreate(config));
        assertTrue(ex.getMessage().contains("Supported:"), "Error message should list supported providers");
    }

    @Test
    void openaiProvider_shouldCreateModel() {
        var config = createConfig("openai", Map.of("apiKey", "test-key"));

        EmbeddingModel model = factory.getOrCreate(config);

        assertNotNull(model);
    }

    @Test
    void nullEmbeddingParameters_shouldNotThrow() {
        var config = new RagConfiguration();
        config.setEmbeddingProvider("openai");
        config.setEmbeddingParameters(null);

        EmbeddingModel model = factory.getOrCreate(config);
        assertNotNull(model);
    }

    @Nested
    @DisplayName("New Provider Tests")
    class NewProviderTests {

        @Test
        @DisplayName("Mistral provider should create model")
        void mistralProvider_shouldCreateModel() {
            var config = createConfig("mistral", Map.of("apiKey", "test-key"));
            EmbeddingModel model = factory.getOrCreate(config);
            assertNotNull(model);
        }

        @Test
        @DisplayName("Vertex provider without project should throw")
        void vertexProvider_noProject_shouldThrow() {
            var config = createConfig("vertex", Map.of());
            var ex = assertThrows(IllegalArgumentException.class, () -> factory.getOrCreate(config));
            assertTrue(ex.getMessage().contains("project"), "Error should mention missing project");
        }

        @Test
        @DisplayName("Cohere provider should create model")
        void cohereProvider_shouldCreateModel() {
            var config = createConfig("cohere", Map.of("apiKey", "test-key"));
            EmbeddingModel model = factory.getOrCreate(config);
            assertNotNull(model);
        }

        @Test
        @DisplayName("Gemini provider should create model with default task type")
        void geminiProvider_shouldCreateModel() {
            var config = createConfig("gemini", Map.of("apiKey", "test-key"));
            EmbeddingModel model = factory.getOrCreate(config);
            assertNotNull(model);
        }

        @Test
        @DisplayName("Gemini provider with custom task type should create model")
        void geminiProvider_customTaskType_shouldCreateModel() {
            var config = createConfig("gemini", Map.of(
                    "apiKey", "test-key",
                    "tasktype", "RETRIEVAL_QUERY"));
            EmbeddingModel model = factory.getOrCreate(config);
            assertNotNull(model);
        }

        @Test
        @DisplayName("Gemini provider with invalid task type should throw")
        void geminiProvider_invalidTaskType_shouldThrow() {
            var config = createConfig("gemini", Map.of(
                    "apiKey", "test-key",
                    "tasktype", "INVALID_TASK"));
            assertThrows(IllegalArgumentException.class, () -> factory.getOrCreate(config));
        }

        @Test
        @DisplayName("Gemini provider with custom model should create model")
        void geminiProvider_customModel_shouldCreateModel() {
            var config = createConfig("gemini", Map.of(
                    "apiKey", "test-key",
                    "model", "gemini-embedding-002"));
            EmbeddingModel model = factory.getOrCreate(config);
            assertNotNull(model);
        }
    }

    private RagConfiguration createConfig(String provider, Map<String, String> params) {
        var config = new RagConfiguration();
        config.setEmbeddingProvider(provider);
        config.setEmbeddingParameters(params);
        return config;
    }
}
