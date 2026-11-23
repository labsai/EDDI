/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.model.SecretReference;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.request.EmbeddingInputType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

class EmbeddingModelFactoryTest {

    @Mock
    private SecretResolver secretResolver;

    @Mock
    private GlobalVariableResolver globalVariableResolver;

    private EmbeddingModelFactory factory;

    @BeforeEach
    void setUp() {
        openMocks(this);
        when(secretResolver.resolveSecrets(any())).thenAnswer(inv -> resolvingVault(inv.getArgument(0)));
        when(globalVariableResolver.resolveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        factory = new EmbeddingModelFactory(globalVariableResolver, secretResolver);
    }

    /**
     * A working vault: every reference resolves, except names starting with
     * {@code no-such}, which stay unresolved like a missing secret does.
     */
    private static Map<String, String> resolvingVault(Map<String, String> params) {
        if (params == null) {
            return null;
        }
        var resolved = new HashMap<>(params);
        resolved.replaceAll((key, value) -> value != null && value.startsWith("${vault:") && !value.startsWith("${vault:no-such")
                ? "resolved-secret"
                : value);
        return resolved;
    }

    @Test
    @DisplayName("an unresolvable vault reference fails before the model is built, instead of reaching the provider as the key")
    void unresolvedVaultReferenceFailsClosed() {
        var config = createConfig("openai", Map.of("model", "text-embedding-3-small", "apiKey", "${vault:no-such-key}"));

        var e = assertThrows(SecretResolver.UnresolvedSecretReferenceException.class, () -> factory.getOrCreate(config, EmbeddingInputType.DOCUMENT));

        assertTrue(e.getMessage().contains("'apiKey'"), e.getMessage());
        assertTrue(e.getMessage().contains("embedding model 'openai'"), e.getMessage());
    }

    @Test
    void sameConfig_shouldReturnCachedInstance() {
        var config = createConfig("openai", Map.of("model", "text-embedding-3-small", "apiKey", "test-key"));

        EmbeddingModel model1 = factory.getOrCreate(config, EmbeddingInputType.DOCUMENT);
        EmbeddingModel model2 = factory.getOrCreate(config, EmbeddingInputType.DOCUMENT);

        assertSame(model1, model2, "Same config should return cached instance");
    }

    @Test
    void differentParams_shouldReturnDifferentInstances() {
        var config1 = createConfig("openai", Map.of("model", "text-embedding-3-small", "apiKey", "key1"));
        var config2 = createConfig("openai", Map.of("model", "text-embedding-3-large", "apiKey", "key2"));

        EmbeddingModel model1 = factory.getOrCreate(config1, EmbeddingInputType.DOCUMENT);
        EmbeddingModel model2 = factory.getOrCreate(config2, EmbeddingInputType.DOCUMENT);

        assertNotSame(model1, model2, "Different params should return different instances");
    }

    @Test
    void parameterMapsThatRenderAlike_doNotShareACachedClient() {
        // TreeMap.toString() renders both as {apiKey=k, model=m}: a string cache key
        // made them one entry, handing one configuration the other's client.
        var config1 = createConfig("openai", Map.of("apiKey", "k, model=m"));
        var config2 = createConfig("openai", Map.of("apiKey", "k", "model", "m"));

        EmbeddingModel model1 = factory.getOrCreate(config1, EmbeddingInputType.DOCUMENT);
        EmbeddingModel model2 = factory.getOrCreate(config2, EmbeddingInputType.DOCUMENT);

        assertNotSame(model1, model2);
    }

    @Test
    void clearCache_shouldEvictEntries() {
        var config = createConfig("openai", Map.of("model", "text-embedding-3-small", "apiKey", "test-key"));
        EmbeddingModel before = factory.getOrCreate(config, EmbeddingInputType.DOCUMENT);

        factory.clearCache();
        EmbeddingModel after = factory.getOrCreate(config, EmbeddingInputType.DOCUMENT);

        assertNotSame(before, after, "After clearing cache, a new instance should be created");
    }

    /**
     * The cache had no invalidation hook at all. {@code expireAfterAccess} resets
     * on every read, so a model that is actually serving traffic never reaches its
     * TTL, and {@code clearCache()} had no production caller — rotating an
     * embedding provider's API key kept authenticating with the old one for the
     * lifetime of the process.
     */
    @Nested
    @DisplayName("credential rotation evicts cached models")
    class InvalidationTests {

        @Test
        void rotatingASecretEvictsTheCachedModel() {
            var secretListener = ArgumentCaptor.forClass(Consumer.class);
            factory.registerInvalidation();
            verify(secretResolver).registerInvalidationListener(secretListener.capture());

            var config = createConfig("openai", Map.of("apiKey", "${vault:openai-key}"));
            EmbeddingModel before = factory.getOrCreate(config, EmbeddingInputType.DOCUMENT);

            @SuppressWarnings("unchecked")
            Consumer<SecretReference> listener = secretListener.getValue();
            listener.accept(new SecretReference("default", "openai-key"));

            assertNotSame(before, factory.getOrCreate(config, EmbeddingInputType.DOCUMENT),
                    "after the key rotated the model must be rebuilt, not served from the cache");
        }

        @Test
        void editingAGlobalVariableEvictsTheCachedModel() {
            var variableListener = ArgumentCaptor.forClass(Runnable.class);
            factory.registerInvalidation();
            verify(globalVariableResolver).registerInvalidationListener(variableListener.capture());

            var config = createConfig("openai", Map.of("apiKey", "test-key"));
            EmbeddingModel before = factory.getOrCreate(config, EmbeddingInputType.DOCUMENT);

            variableListener.getValue().run();

            assertNotSame(before, factory.getOrCreate(config, EmbeddingInputType.DOCUMENT));
        }
    }

    @Test
    void unsupportedProvider_shouldThrow() {
        var config = createConfig("unsupported_provider", Map.of());

        var ex = assertThrows(IllegalArgumentException.class, () -> factory.getOrCreate(config, EmbeddingInputType.DOCUMENT));
        assertTrue(ex.getMessage().contains("Supported:"), "Error message should list supported providers");
    }

    /**
     * {@code RagConfiguration.validate()} guards its provider check with
     * {@code embeddingProvider != null}, so a knowledge base saved with an explicit
     * null provider is accepted and only fails here. It has to fail by naming the
     * missing configuration, not as a NullPointerException raised by switching on
     * null from inside the factory.
     */
    @Test
    void nullProvider_shouldThrowNamingTheMissingConfiguration() {
        var config = createConfig(null, Map.of());

        var ex = assertThrows(IllegalArgumentException.class, () -> factory.getOrCreate(config, EmbeddingInputType.DOCUMENT));
        assertTrue(ex.getMessage().contains("Supported:"), "Error message should list supported providers");
    }

    /** A provider of nothing but whitespace is the same omission as a null one. */
    @Test
    void blankProvider_shouldThrowNamingTheMissingConfiguration() {
        var config = createConfig("   ", Map.of());

        var ex = assertThrows(IllegalArgumentException.class, () -> factory.getOrCreate(config, EmbeddingInputType.DOCUMENT));
        assertTrue(ex.getMessage().contains("Supported:"), "Error message should list supported providers");
    }

    @Test
    void openaiProvider_shouldCreateModel() {
        var config = createConfig("openai", Map.of("apiKey", "test-key"));

        EmbeddingModel model = factory.getOrCreate(config, EmbeddingInputType.DOCUMENT);

        assertNotNull(model);
    }

    @Test
    void nullEmbeddingParameters_shouldNotThrow() {
        var config = new RagConfiguration();
        config.setEmbeddingProvider("openai");
        config.setEmbeddingParameters(null);

        EmbeddingModel model = factory.getOrCreate(config, EmbeddingInputType.DOCUMENT);
        assertNotNull(model);
    }

    @Nested
    @DisplayName("New Provider Tests")
    class NewProviderTests {

        @Test
        @DisplayName("Mistral provider should create model")
        void mistralProvider_shouldCreateModel() {
            var config = createConfig("mistral", Map.of("apiKey", "test-key"));
            EmbeddingModel model = factory.getOrCreate(config, EmbeddingInputType.DOCUMENT);
            assertNotNull(model);
        }

        @Test
        @DisplayName("Vertex provider without project should throw")
        void vertexProvider_noProject_shouldThrow() {
            var config = createConfig("vertex", Map.of());
            var ex = assertThrows(IllegalArgumentException.class, () -> factory.getOrCreate(config, EmbeddingInputType.DOCUMENT));
            assertTrue(ex.getMessage().contains("project"), "Error should mention missing project");
        }

        @Test
        @DisplayName("Cohere provider should create model")
        void cohereProvider_shouldCreateModel() {
            var config = createConfig("cohere", Map.of("apiKey", "test-key"));
            EmbeddingModel model = factory.getOrCreate(config, EmbeddingInputType.DOCUMENT);
            assertNotNull(model);
        }

        @Test
        @DisplayName("Gemini provider should create model with default task type")
        void geminiProvider_shouldCreateModel() {
            var config = createConfig("gemini", Map.of("apiKey", "test-key"));
            EmbeddingModel model = factory.getOrCreate(config, EmbeddingInputType.DOCUMENT);
            assertNotNull(model);
        }

        @Test
        @DisplayName("Gemini provider with custom task type should create model")
        void geminiProvider_customTaskType_shouldCreateModel() {
            var config = createConfig("gemini", Map.of(
                    "apiKey", "test-key",
                    "taskType", "RETRIEVAL_QUERY"));
            EmbeddingModel model = factory.getOrCreate(config, EmbeddingInputType.DOCUMENT);
            assertNotNull(model);
        }

        @Test
        @DisplayName("Gemini provider with invalid task type should throw")
        void geminiProvider_invalidTaskType_shouldThrow() {
            var config = createConfig("gemini", Map.of(
                    "apiKey", "test-key",
                    "taskType", "INVALID_TASK"));
            assertThrows(IllegalArgumentException.class, () -> factory.getOrCreate(config, EmbeddingInputType.DOCUMENT));
        }

        @Test
        @DisplayName("Gemini provider with custom model should create model")
        void geminiProvider_customModel_shouldCreateModel() {
            var config = createConfig("gemini", Map.of(
                    "apiKey", "test-key",
                    "model", "gemini-embedding-002"));
            EmbeddingModel model = factory.getOrCreate(config, EmbeddingInputType.DOCUMENT);
            assertNotNull(model);
        }
    }

    /**
     * Tagging every RAG call with its role fixes queries being embedded as
     * documents, but a Gemini {@code taskType} set on purpose has to survive it:
     * {@code GoogleAiEmbeddingModel.toTaskType} falls back to the build-time task
     * type only when no input type is given, so an unconditional role would
     * silently retire a configured {@code SEMANTIC_SIMILARITY} and mix two vector
     * geometries in one index.
     */
    @Nested
    @DisplayName("pinsNonRetrievalTaskType — the decision, without a provider client")
    class PinnedTaskTypeDecision {

        @Test
        @DisplayName("SEMANTIC_SIMILARITY on a task-type-aware Gemini model is pinned")
        void semanticSimilarityIsPinned() {
            assertTrue(EmbeddingModelFactory.pinsNonRetrievalTaskType("gemini",
                    Map.of("model", "gemini-embedding-001", "taskType", "SEMANTIC_SIMILARITY")));
        }

        @Test
        @DisplayName("CLASSIFICATION and CLUSTERING are pinned as well")
        void otherNonRetrievalTaskTypesArePinned() {
            assertTrue(EmbeddingModelFactory.pinsNonRetrievalTaskType("gemini",
                    Map.of("model", "gemini-embedding-001", "taskType", "CLASSIFICATION")));
            assertTrue(EmbeddingModelFactory.pinsNonRetrievalTaskType("gemini",
                    Map.of("model", "gemini-embedding-001", "taskType", "CLUSTERING")));
        }

        @Test
        @DisplayName("RETRIEVAL_DOCUMENT is the default that caused the defect, so it is not a pin")
        void retrievalDocumentIsNotAPin() {
            assertFalse(EmbeddingModelFactory.pinsNonRetrievalTaskType("gemini",
                    Map.of("model", "gemini-embedding-001", "taskType", "RETRIEVAL_DOCUMENT")));
        }

        @Test
        @DisplayName("RETRIEVAL_QUERY is not a pin either — both say 'this is for retrieval'")
        void retrievalQueryIsNotAPin() {
            assertFalse(EmbeddingModelFactory.pinsNonRetrievalTaskType("gemini",
                    Map.of("model", "gemini-embedding-001", "taskType", "RETRIEVAL_QUERY")));
        }

        @Test
        @DisplayName("an absent or blank taskType is not a pin")
        void absentTaskTypeIsNotAPin() {
            assertFalse(EmbeddingModelFactory.pinsNonRetrievalTaskType("gemini", Map.of("model", "gemini-embedding-001")));
            assertFalse(EmbeddingModelFactory.pinsNonRetrievalTaskType("gemini",
                    Map.of("model", "gemini-embedding-001", "taskType", "   ")));
        }

        @Test
        @DisplayName("Gemini Embedding 2 ignores task_type, so nothing is pinned there — including the default model")
        void embedding2IsNeverAPin() {
            assertFalse(EmbeddingModelFactory.pinsNonRetrievalTaskType("gemini",
                    Map.of("model", "gemini-embedding-2", "taskType", "SEMANTIC_SIMILARITY")));
            assertFalse(EmbeddingModelFactory.pinsNonRetrievalTaskType("gemini",
                    Map.of("taskType", "SEMANTIC_SIMILARITY")),
                    "the default model is gemini-embedding-2, which sends no task_type at all");
        }

        @Test
        @DisplayName("taskType is a Gemini parameter — it pins nothing on another provider")
        void otherProvidersAreNeverPinned() {
            // A model name that is NOT embedding-2, or the Gemini-2 branch would
            // answer first and this would pass whether the provider is checked or
            // not.
            assertFalse(EmbeddingModelFactory.pinsNonRetrievalTaskType("cohere",
                    Map.of("model", "embed-english-v3.0", "taskType", "SEMANTIC_SIMILARITY")));
            assertFalse(EmbeddingModelFactory.pinsNonRetrievalTaskType("openai",
                    Map.of("model", "text-embedding-3-small", "taskType", "SEMANTIC_SIMILARITY")));
        }
    }

    @Nested
    @DisplayName("a deliberately pinned Gemini taskType survives the role")
    class PinnedTaskType {

        @Test
        @DisplayName("a non-retrieval taskType on a task-type-aware model keeps the role off the request")
        void nonRetrievalTaskType_isNotOverriddenByTheRole() {
            var config = createConfig("gemini", Map.of(
                    "apiKey", "test-key",
                    "model", "gemini-embedding-001",
                    "taskType", "SEMANTIC_SIMILARITY"));

            EmbeddingModel model = factory.getOrCreate(config, EmbeddingInputType.DOCUMENT);

            // Unwrapped: the throttle decorator is always outermost on the ingestion
            // side, and an instanceof on it would pass whatever is underneath.
            EmbeddingModel underneath = assertInstanceOf(ThrottledEmbeddingModel.class, model).delegate();
            assertFalse(underneath instanceof InputTypedEmbeddingModel,
                    "a pinned SEMANTIC_SIMILARITY must reach the provider, so no role may be attached");
        }

        @Test
        @DisplayName("CLASSIFICATION is pinned for the query role too, not only for ingestion")
        void nonRetrievalTaskType_isNotOverriddenOnTheQuerySide() {
            var config = createConfig("gemini", Map.of(
                    "apiKey", "test-key",
                    "model", "gemini-embedding-001",
                    "taskType", "CLASSIFICATION"));

            EmbeddingModel model = factory.getOrCreate(config, EmbeddingInputType.QUERY);

            assertFalse(model instanceof InputTypedEmbeddingModel,
                    "pinning must apply to both roles, or the two sides embed with different task types");
        }

        @Test
        @DisplayName("an explicit RETRIEVAL_DOCUMENT is the defect, not a pin, so the role still applies")
        void explicitRetrievalDocument_stillGetsTheRole() {
            var config = createConfig("gemini", Map.of(
                    "apiKey", "test-key",
                    "model", "gemini-embedding-001",
                    "taskType", "RETRIEVAL_DOCUMENT"));

            EmbeddingModel model = factory.getOrCreate(config, EmbeddingInputType.QUERY);

            var typed = assertInstanceOf(InputTypedEmbeddingModel.class, model,
                    "writing the default out by hand must not opt back into embedding queries as documents");
            assertEquals(EmbeddingInputType.QUERY, typed.inputType());
        }

        @Test
        @DisplayName("Gemini Embedding 2 ignores taskType entirely, so a pin there must not cost the role")
        void embedding2_ignoresThePinAndKeepsTheRole() {
            var config = createConfig("gemini", Map.of(
                    "apiKey", "test-key",
                    "model", "gemini-embedding-2",
                    "taskType", "SEMANTIC_SIMILARITY"));

            EmbeddingModel model = factory.getOrCreate(config, EmbeddingInputType.QUERY);

            var typed = assertInstanceOf(InputTypedEmbeddingModel.class, model,
                    "langchain4j sends no task_type for an embedding-2 model and uses a role instruction instead");
            assertEquals(EmbeddingInputType.QUERY, typed.inputType());
        }

        @Test
        @DisplayName("with no taskType configured at all the role applies as before")
        void noTaskType_getsTheRole() {
            var config = createConfig("gemini", Map.of(
                    "apiKey", "test-key",
                    "model", "gemini-embedding-001"));

            EmbeddingModel model = factory.getOrCreate(config, EmbeddingInputType.DOCUMENT);

            var typed = assertInstanceOf(InputTypedEmbeddingModel.class,
                    assertInstanceOf(ThrottledEmbeddingModel.class, model).delegate());
            assertEquals(EmbeddingInputType.DOCUMENT, typed.inputType());
        }

        @Test
        @DisplayName("a non-Gemini provider that accepts an input type is unaffected by a stray taskType")
        void cohere_isUnaffected() {
            var config = createConfig("cohere", Map.of(
                    "apiKey", "test-key",
                    "taskType", "SEMANTIC_SIMILARITY"));

            EmbeddingModel model = factory.getOrCreate(config, EmbeddingInputType.QUERY);

            var typed = assertInstanceOf(InputTypedEmbeddingModel.class, model,
                    "taskType is a Gemini parameter; it must not disable the role anywhere else");
            assertEquals(EmbeddingInputType.QUERY, typed.inputType());
        }
    }

    @Nested
    @DisplayName("provider throttling is waited out on the ingestion side only")
    class Throttling {

        @Test
        @DisplayName("the DOCUMENT role is wrapped outside the role decorator, with the default five retries")
        void documentRoleWaitsOnThrottles() {
            var config = createConfig("gemini", Map.of("apiKey", "test-key", "model", "gemini-embedding-2"));

            EmbeddingModel model = factory.getOrCreate(config, EmbeddingInputType.DOCUMENT);

            var throttled = assertInstanceOf(ThrottledEmbeddingModel.class, model);
            assertEquals(ThrottledEmbeddingModel.DEFAULT_MAX_RETRIES, throttled.maxRetries());
            assertTrue(throttled.retriesTransientFailures(), "Gemini's own retries are off, so the decorator retries");
            var typed = assertInstanceOf(InputTypedEmbeddingModel.class, throttled.delegate());
            assertEquals(EmbeddingInputType.DOCUMENT, typed.inputType());
            assertEquals(EmbeddingInputType.DOCUMENT, model.defaultRequestParameters().inputType(),
                    "the role still reaches every request through the decorator");
        }

        @Test
        @DisplayName("the QUERY role never waits on a throttle")
        void queryRoleIsNotWrapped() {
            var config = createConfig("gemini", Map.of("apiKey", "test-key", "model", "gemini-embedding-2"));

            EmbeddingModel model = factory.getOrCreate(config, EmbeddingInputType.QUERY);

            assertFalse(model instanceof ThrottledEmbeddingModel, "a user is waiting for this answer");
            assertEquals(EmbeddingInputType.QUERY, assertInstanceOf(InputTypedEmbeddingModel.class, model).inputType());
        }

        @Test
        @DisplayName("a configured maxRetries is the decorator's, not the provider's, for ingestion")
        void configuredRetriesMoveToTheDecorator() {
            var config = createConfig("gemini", Map.of("apiKey", "test-key", "maxRetries", "7"));

            var throttled = assertInstanceOf(ThrottledEmbeddingModel.class,
                    factory.getOrCreate(config, EmbeddingInputType.DOCUMENT));

            assertEquals(7, throttled.maxRetries());
        }

        @Test
        @DisplayName("the provider is built with maxRetries 0 for ingestion and unchanged for queries")
        void providerRetriesOffForIngestionOnly() {
            Map<String, String> params = Map.of("apiKey", "k", "maxRetries", "7");

            assertEquals("0", EmbeddingModelFactory.providerParameters("gemini", params, EmbeddingInputType.DOCUMENT)
                    .get("maxRetries"));
            assertEquals("0", EmbeddingModelFactory.providerParameters("openai", Map.of("apiKey", "k"),
                    EmbeddingInputType.DOCUMENT).get("maxRetries"), "set even when nothing was configured");
            assertSame(params, EmbeddingModelFactory.providerParameters("gemini", params, EmbeddingInputType.QUERY));
        }

        /**
         * The client the factory really builds, not the parameter map: the provider's
         * own {@code maxRetries} field is 0 for ingestion, and the configured value for
         * queries. A provider dropped from the set the decorator owns fails here.
         */
        @ParameterizedTest(name = "{0}")
        @MethodSource("ai.labs.eddi.modules.llm.impl.EmbeddingModelFactoryTest#langchain4jClientProviders")
        @DisplayName("a factory-built DOCUMENT client has its own retries off; the QUERY client keeps them")
        void builtDocumentClientHasRetriesOff(String provider, Map<String, String> params) throws Exception {
            var withRetries = new HashMap<>(params);
            withRetries.put("maxRetries", "4");
            var config = createConfig(provider, withRetries);

            EmbeddingModel document = factory.getOrCreate(config, EmbeddingInputType.DOCUMENT);
            EmbeddingModel query = factory.getOrCreate(config, EmbeddingInputType.QUERY);

            assertEquals(0, providerMaxRetries(document), provider + " ingestion client retries");
            assertEquals(4, providerMaxRetries(query), provider + " query client retries");
            assertEquals(4, assertInstanceOf(ThrottledEmbeddingModel.class, document).maxRetries(),
                    "the configured retries are the decorator's");
        }

        @Test
        @DisplayName("the unbound wait and the batch size reach the decorator")
        void configuredWaitAndBatchSize() {
            factory.unboundMaxWait = Duration.ofSeconds(3);
            factory.embedBatchSize = 25;

            var throttled = assertInstanceOf(ThrottledEmbeddingModel.class, factory.getOrCreate(
                    createConfig("gemini", Map.of("apiKey", "test-key")), EmbeddingInputType.DOCUMENT));

            assertEquals(Duration.ofSeconds(3), throttled.unboundMaxWait());
            assertEquals(25, throttled.batchSize());
        }

        @Test
        @DisplayName("a provider whose SDK retries by itself keeps its retries, and the decorator adds none for outages")
        void sdkProvidersKeepTheirRetries() {
            Map<String, String> params = Map.of("project", "p", "maxRetries", "4");
            assertSame(params, EmbeddingModelFactory.providerParameters("vertex", params, EmbeddingInputType.DOCUMENT));

            var throttled = assertInstanceOf(ThrottledEmbeddingModel.class,
                    factory.getOrCreate(createConfig("cohere", Map.of("apiKey", "test-key")), EmbeddingInputType.DOCUMENT));
            assertFalse(throttled.retriesTransientFailures());
        }
    }

    static Stream<Arguments> langchain4jClientProviders() {
        return Stream.of(
                Arguments.of("openai", Map.of("apiKey", "test-key", "model", "text-embedding-3-small")),
                Arguments.of("ollama", Map.of("model", "nomic-embed-text")),
                Arguments.of("mistral", Map.of("apiKey", "test-key")));
    }

    /**
     * The {@code maxRetries} the provider's langchain4j client was built with, read
     * from its private field under every EDDI decorator.
     */
    private static int providerMaxRetries(EmbeddingModel model) throws ReflectiveOperationException {
        EmbeddingModel current = model;
        while (current instanceof ThrottledEmbeddingModel || current instanceof InputTypedEmbeddingModel) {
            current = current instanceof ThrottledEmbeddingModel throttled
                    ? throttled.delegate()
                    : (EmbeddingModel) readField(current, "delegate");
        }
        Object value = readField(current, "maxRetries");
        assertNotNull(value, current.getClass().getSimpleName() + " carries no maxRetries");
        return (Integer) value;
    }

    private static Object readField(Object target, String name) throws ReflectiveOperationException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException e) {
                // Declared further up.
            }
        }
        throw new NoSuchFieldException(name + " on " + target.getClass().getName());
    }

    private RagConfiguration createConfig(String provider, Map<String, String> params) {
        var config = new RagConfiguration();
        config.setEmbeddingProvider(provider);
        config.setEmbeddingParameters(params);
        return config;
    }
}
