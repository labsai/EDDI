/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.modules.llm.impl.builder.ILanguageModelBuilder;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.model.SecretReference;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import jakarta.inject.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;
import static dev.langchain4j.data.message.AiMessage.aiMessage;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ChatModelRegistry} — both sync and streaming model caching.
 */
class ChatModelRegistryTest {

    private ChatModelRegistry registry;
    /**
     * Registry whose builder returns a UNIQUE instance per build() call — without
     * that, assertNotSame cannot distinguish a cache hit from a rebuild.
     */
    private ChatModelRegistry uniqueRegistry;
    private ChatModel mockSyncModel;
    private StreamingChatModel mockStreamingModel;
    private final AtomicReference<Map<String, String>> lastBuildParams = new AtomicReference<>();
    private final AtomicReference<Map<String, String>> lastBuildStreamingParams = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        lastBuildParams.set(null);
        lastBuildStreamingParams.set(null);
        mockSyncModel = new ChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages) {
                return ChatResponse.builder().aiMessage(aiMessage("ok")).build();
            }
        };
        mockStreamingModel = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                // no-op for test
            }
        };

        Map<String, Provider<ILanguageModelBuilder>> builders = new HashMap<>();
        builders.put("openai", () -> new ILanguageModelBuilder() {
            @Override
            public ChatModel build(Map<String, String> parameters) {
                parseTimeoutLikeARealProvider(parameters);
                lastBuildParams.set(new HashMap<>(parameters));
                return mockSyncModel;
            }

            @Override
            public StreamingChatModel buildStreaming(Map<String, String> parameters) {
                parseTimeoutLikeARealProvider(parameters);
                lastBuildStreamingParams.set(new HashMap<>(parameters));
                return mockStreamingModel;
            }
        });
        builders.put("unsupported", () -> new ILanguageModelBuilder() {
            @Override
            public ChatModel build(Map<String, String> parameters) {
                return mockSyncModel;
            }
            // Uses default buildStreaming — throws UnsupportedOperationException
        });

        // Create pass-through mocks
        SecretResolver secretResolver = mock(SecretResolver.class);
        when(secretResolver.resolveSecrets(any())).thenAnswer(inv -> inv.getArgument(0));
        GlobalVariableResolver globalVariableResolver = mock(GlobalVariableResolver.class);
        when(globalVariableResolver.resolveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        registry = new ChatModelRegistry(builders, globalVariableResolver, secretResolver);

        Map<String, Provider<ILanguageModelBuilder>> uniqueBuilders = new HashMap<>();
        uniqueBuilders.put("openai", () -> new ILanguageModelBuilder() {
            @Override
            public ChatModel build(Map<String, String> parameters) {
                parseTimeoutLikeARealProvider(parameters);
                return new ChatModel() {
                    @Override
                    public ChatResponse chat(List<ChatMessage> messages) {
                        return ChatResponse.builder().aiMessage(aiMessage("ok")).build();
                    }
                };
            }

            @Override
            public StreamingChatModel buildStreaming(Map<String, String> parameters) {
                parseTimeoutLikeARealProvider(parameters);
                return new StreamingChatModel() {
                    @Override
                    public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                    }
                };
            }
        });
        uniqueRegistry = new ChatModelRegistry(uniqueBuilders, globalVariableResolver, secretResolver);
    }

    /**
     * Reproduces, verbatim, what every shipped provider builder does with
     * {@code timeout} — an {@code isNullOrEmpty} guard (which does <em>not</em>
     * trim) followed by an unguarded parse:
     *
     * <pre>
     * if (!isNullOrEmpty(parameters.get(KEY_TIMEOUT))) {
     *     builder.timeout(Duration.ofMillis(Long.parseLong(parameters.get(KEY_TIMEOUT))));
     * }
     * </pre>
     *
     * The real builders cannot be used here: constructing one opens a loopback
     * socket, which is unavailable in the unit-test sandbox (see
     * {@code LanguageModelBuildersTest}). Mirroring the expression keeps these
     * tests honest about what an unnormalised value would actually do at build
     * time.
     * <p>
     * The {@code Long.parseLong} is deliberately left unguarded and MUST stay that
     * way: catching {@link NumberFormatException} here would make the helper
     * tolerate values the real builders reject, so a regression in the registry's
     * timeout normalisation would silently slip past the {@code C3} tests below
     * instead of surfacing as the very {@code build()}-time crash those tests guard
     * against. A static-analysis note asking to wrap this parse in a try/catch is a
     * false positive for exactly that reason.
     */
    private static void parseTimeoutLikeARealProvider(Map<String, String> parameters) {
        if (!isNullOrEmpty(parameters.get("timeout"))) {
            Duration.ofMillis(Long.parseLong(parameters.get("timeout")));
        }
    }

    @Nested
    @DisplayName("Sync model tests")
    class SyncTests {

        @Test
        @DisplayName("getOrCreate creates model for valid type")
        void getOrCreate_validType_createsModel() throws Exception {
            ChatModel model = registry.getOrCreate("openai", Map.of("apiKey", "test"));
            assertNotNull(model);
            assertSame(mockSyncModel, model);
        }

        @Test
        @DisplayName("getOrCreate caches models by type+params")
        void getOrCreate_sameParams_returnsCached() throws Exception {
            var params = Map.of("apiKey", "test");
            ChatModel first = registry.getOrCreate("openai", params);
            ChatModel second = registry.getOrCreate("openai", params);
            assertSame(first, second);
        }

        @Test
        @DisplayName("getOrCreate throws for unknown type")
        void getOrCreate_unknownType_throwsException() {
            assertThrows(ChatModelRegistry.UnsupportedLlmTaskException.class, () -> registry.getOrCreate("unknown", Map.of()));
        }

        @Test
        @DisplayName("getOrCreate filters non-model params from cache key")
        void getOrCreate_filtersSystemMessage() throws Exception {
            var params1 = new HashMap<String, String>();
            params1.put("apiKey", "test");
            params1.put("systemMessage", "be helpful");

            var params2 = new HashMap<String, String>();
            params2.put("apiKey", "test");
            params2.put("systemMessage", "be different");

            ChatModel first = registry.getOrCreate("openai", params1);
            ChatModel second = registry.getOrCreate("openai", params2);
            assertSame(first, second);
        }
    }

    @Nested
    @DisplayName("Streaming model tests")
    class StreamingTests {

        @Test
        @DisplayName("getOrCreateStreaming creates streaming model for supported type")
        void getOrCreateStreaming_supportedType_createsModel() throws Exception {
            StreamingChatModel model = registry.getOrCreateStreaming("openai", Map.of("apiKey", "test"));
            assertNotNull(model);
            assertSame(mockStreamingModel, model);
        }

        @Test
        @DisplayName("getOrCreateStreaming caches streaming models")
        void getOrCreateStreaming_sameParams_returnsCached() throws Exception {
            var params = Map.of("apiKey", "test");
            StreamingChatModel first = registry.getOrCreateStreaming("openai", params);
            StreamingChatModel second = registry.getOrCreateStreaming("openai", params);
            assertSame(first, second);
        }

        @Test
        @DisplayName("getOrCreateStreaming returns null for unsupported builder")
        void getOrCreateStreaming_unsupportedBuilder_returnsNull() throws Exception {
            StreamingChatModel model = registry.getOrCreateStreaming("unsupported", Map.of());
            assertNull(model);
        }

        @Test
        @DisplayName("getOrCreateStreaming throws for unknown type")
        void getOrCreateStreaming_unknownType_throwsException() {
            assertThrows(ChatModelRegistry.UnsupportedLlmTaskException.class, () -> registry.getOrCreateStreaming("unknown", Map.of()));
        }

        @Test
        @DisplayName("Streaming and sync caches are independent")
        void getOrCreate_streamingAndSyncAreIndependent() throws Exception {
            var params = Map.of("apiKey", "test");
            ChatModel syncModel = registry.getOrCreate("openai", params);
            StreamingChatModel streamingModel = registry.getOrCreateStreaming("openai", params);

            assertNotNull(syncModel);
            assertNotNull(streamingModel);
            assertNotSame(syncModel, streamingModel);
        }
    }

    @Nested
    @DisplayName("Observability wrapping tests")
    class ObservabilityTests {

        @Test
        @DisplayName("getOrCreate wraps model when timeout param is present")
        void getOrCreate_withTimeout_wrapsWithObservable() throws Exception {
            var params = new HashMap<String, String>();
            params.put("apiKey", "test");
            params.put("timeout", "5000");

            ChatModel model = registry.getOrCreate("openai", params);
            assertNotNull(model);
            assertInstanceOf(ObservableChatModel.class, model, "Model should be wrapped with ObservableChatModel when timeout is set");
        }

        @Test
        @DisplayName("getOrCreate wraps model when logRequests param is present")
        void getOrCreate_withLogRequests_wrapsWithObservable() throws Exception {
            var params = new HashMap<String, String>();
            params.put("apiKey", "test");
            params.put("logRequests", "true");

            ChatModel model = registry.getOrCreate("openai", params);
            assertInstanceOf(ObservableChatModel.class, model);
        }

        @Test
        @DisplayName("getOrCreate wraps model when logResponses param is present")
        void getOrCreate_withLogResponses_wrapsWithObservable() throws Exception {
            var params = new HashMap<String, String>();
            params.put("apiKey", "test");
            params.put("logResponses", "true");

            ChatModel model = registry.getOrCreate("openai", params);
            assertInstanceOf(ObservableChatModel.class, model);
        }

        @Test
        @DisplayName("getOrCreate does NOT wrap when no observability params")
        void getOrCreate_noObservabilityParams_returnsRawModel() throws Exception {
            ChatModel model = registry.getOrCreate("openai", Map.of("apiKey", "test"));
            assertNotNull(model);
            assertSame(mockSyncModel, model, "Without observability params, raw model should be returned");
        }

        @Test
        @DisplayName("timeout reaches the builder; the logging flags deliberately do not")
        void getOrCreate_observabilityParamsReachBuilder() throws Exception {
            var params = new HashMap<String, String>();
            params.put("apiKey", "test");
            params.put("timeout", "5000");
            params.put("logRequests", "true");
            params.put("logResponses", "true");

            registry.getOrCreate("openai", params);

            var seen = lastBuildParams.get();
            assertNotNull(seen, "builder should have been invoked");
            assertEquals("5000", seen.get("timeout"), "timeout must reach the provider builder, not be filtered away");
            assertFalse(seen.containsKey("logRequests"),
                    "logRequests must NOT reach the provider builder — it installs langchain4j's untruncated body logger");
            assertFalse(seen.containsKey("logResponses"),
                    "logResponses must NOT reach the provider builder — it installs langchain4j's untruncated body logger");
        }
    }

    /**
     * C4: {@code logRequests}/{@code logResponses} still shape model identity, but
     * they no longer reach the provider builders. langchain4j's
     * {@code LoggingHttpClient} writes whole request and response bodies to the
     * application log at INFO with no truncation, i.e. full prompts, full
     * conversation history and full model output. EDDI's own
     * {@link ObservableChatModel}/{@link ObservableStreamingChatModel} honour both
     * flags and truncate to 200/500 chars, so they remain the single logging path.
     */
    @Nested
    @DisplayName("Logging flags never reach the provider builders (C4)")
    class ProviderLoggingTests {

        @Test
        @DisplayName("logging flags are stripped from the sync builder input but still wrap the model")
        void getOrCreate_loggingFlagsStrippedFromBuilder() throws Exception {
            var params = new HashMap<String, String>();
            params.put("apiKey", "test");
            params.put("logRequests", "true");
            params.put("logResponses", "true");

            ChatModel model = registry.getOrCreate("openai", params);

            var seen = lastBuildParams.get();
            assertNotNull(seen);
            assertFalse(seen.containsKey("logRequests"), "logRequests must not be handed to the provider builder");
            assertFalse(seen.containsKey("logResponses"), "logResponses must not be handed to the provider builder");
            assertInstanceOf(ObservableChatModel.class, model, "The flags must still be honoured by EDDI's truncating decorator");
        }

        @Test
        @DisplayName("logging flags are stripped from the streaming builder input but still wrap the model")
        void getOrCreateStreaming_loggingFlagsStrippedFromBuilder() throws Exception {
            var params = new HashMap<String, String>();
            params.put("apiKey", "test");
            params.put("logRequests", "true");
            params.put("logResponses", "true");

            StreamingChatModel model = registry.getOrCreateStreaming("openai", params);

            var seen = lastBuildStreamingParams.get();
            assertNotNull(seen);
            assertFalse(seen.containsKey("logRequests"), "logRequests must not be handed to the streaming provider builder");
            assertFalse(seen.containsKey("logResponses"), "logResponses must not be handed to the streaming provider builder");
            assertInstanceOf(ObservableStreamingChatModel.class, model, "The flags must still be honoured by EDDI's truncating decorator");
        }

        @Test
        @DisplayName("stripping them from the builder input does not remove them from the cache key")
        void loggingFlags_stillPartOfCacheKey() throws Exception {
            var quiet = new HashMap<String, String>();
            quiet.put("apiKey", "test");
            quiet.put("logRequests", "false");
            quiet.put("logResponses", "false");

            var loud = new HashMap<String, String>();
            loud.put("apiKey", "test");
            loud.put("logRequests", "true");
            loud.put("logResponses", "true");

            assertNotSame(uniqueRegistry.getOrCreate("openai", quiet), uniqueRegistry.getOrCreate("openai", loud),
                    "D11's cache-key correctness must survive: the flags still change which model instance a task gets");
            assertNotSame(uniqueRegistry.getOrCreateStreaming("openai", quiet), uniqueRegistry.getOrCreateStreaming("openai", loud),
                    "Same on the streaming path");
        }
    }

    /**
     * C3: {@code timeout} now flows into the provider builders, each of which
     * parses it with an unguarded {@code Long.parseLong}. Values that the previous
     * sole consumer ({@link ObservableChatModel#wrapIfNeeded}) tolerated — blank,
     * non-numeric, zero — would abort {@code build()} on every turn of an agent
     * whose stored config carries one. Normalising at the registry boundary keeps
     * those configs working without a migration.
     */
    @Nested
    @DisplayName("Stored timeout values are normalised before they reach a builder (C3)")
    class TimeoutNormalisationTests {

        @Test
        @DisplayName("a blank timeout still builds a model instead of throwing")
        void getOrCreate_blankTimeout_stillBuilds() throws Exception {
            var params = new HashMap<String, String>();
            params.put("apiKey", "test");
            params.put("timeout", " ");

            ChatModel model = registry.getOrCreate("openai", params);

            assertSame(mockSyncModel, model, "A blank timeout was previously tolerated and must not start failing turns");
            assertFalse(lastBuildParams.get().containsKey("timeout"), "An unusable timeout must be dropped, not forwarded");
        }

        @Test
        @DisplayName("a non-numeric timeout such as \"30s\" still builds a model")
        void getOrCreate_nonNumericTimeout_stillBuilds() throws Exception {
            var params = new HashMap<String, String>();
            params.put("apiKey", "test");
            params.put("timeout", "30s");

            ChatModel model = registry.getOrCreate("openai", params);

            assertSame(mockSyncModel, model, "A non-numeric timeout was previously tolerated and must not start failing turns");
            assertFalse(lastBuildParams.get().containsKey("timeout"));
        }

        @Test
        @DisplayName("a zero timeout (\"unlimited\") still builds a model and adds no bound")
        void getOrCreate_zeroTimeout_stillBuilds() throws Exception {
            var params = new HashMap<String, String>();
            params.put("apiKey", "test");
            params.put("timeout", "0");

            ChatModel model = registry.getOrCreate("openai", params);

            assertSame(mockSyncModel, model, "Zero meant 'no timeout' and must keep meaning that");
            assertFalse(lastBuildParams.get().containsKey("timeout"));
        }

        @Test
        @DisplayName("a blank timeout still builds a streaming model")
        void getOrCreateStreaming_blankTimeout_stillBuilds() throws Exception {
            var params = new HashMap<String, String>();
            params.put("apiKey", "test");
            params.put("timeout", "");

            StreamingChatModel model = registry.getOrCreateStreaming("openai", params);

            assertSame(mockStreamingModel, model);
            assertFalse(lastBuildStreamingParams.get().containsKey("timeout"));
        }

        @Test
        @DisplayName("a valid timeout still reaches the builder and still differentiates the cache key")
        void getOrCreate_validTimeout_survivesNormalisation() throws Exception {
            var params = new HashMap<String, String>();
            params.put("apiKey", "test");
            params.put("timeout", "5000");

            ChatModel model = registry.getOrCreate("openai", params);

            assertEquals("5000", lastBuildParams.get().get("timeout"), "A usable timeout must still configure the provider");
            assertInstanceOf(ObservableChatModel.class, model, "and must still be honoured by the decorator");

            var other = new HashMap<String, String>();
            other.put("apiKey", "test");
            other.put("timeout", "9000");
            assertNotSame(uniqueRegistry.getOrCreate("openai", params), uniqueRegistry.getOrCreate("openai", other),
                    "Normalisation must not collapse two genuinely different timeouts into one cached model");
        }

        @Test
        @DisplayName("whitespace around a valid timeout collapses to a single cached model")
        void getOrCreate_paddedTimeout_isTheSameModel() throws Exception {
            var padded = new HashMap<String, String>();
            padded.put("apiKey", "test");
            padded.put("timeout", " 5000 ");

            var plain = new HashMap<String, String>();
            plain.put("apiKey", "test");
            plain.put("timeout", "5000");

            assertSame(uniqueRegistry.getOrCreate("openai", padded), uniqueRegistry.getOrCreate("openai", plain),
                    "' 5000 ' and '5000' are the same timeout and must share one model instance");

            registry.getOrCreate("openai", padded);
            assertEquals("5000", lastBuildParams.get().get("timeout"), "The builder must receive the trimmed value it can parse");
        }

        @Test
        @DisplayName("an unusable timeout and no timeout at all are the same cached model")
        void getOrCreate_unusableTimeout_sharesTheTimeoutFreeModel() throws Exception {
            var unusable = new HashMap<String, String>();
            unusable.put("apiKey", "test");
            unusable.put("timeout", "not-a-number");

            var none = new HashMap<String, String>();
            none.put("apiKey", "test");

            assertSame(uniqueRegistry.getOrCreate("openai", unusable), uniqueRegistry.getOrCreate("openai", none),
                    "A dropped timeout must produce exactly the timeout-free model, not a second identical one");
        }
    }

    /**
     * C1: the caches are cleared from other threads — {@code invalidateForSecret}
     * on DEK/KEK rotation and the global-variable listener on any variable edit. A
     * {@code containsKey} check followed by a {@code get} could therefore return
     * {@code null}, which callers hand straight to {@code chat(...)}. The lookup
     * must be a single {@code get}.
     */
    @Nested
    @DisplayName("A concurrent cache clear never yields a null model (C1)")
    class ConcurrentInvalidationTests {

        @Test
        @DisplayName("a clear landing between check and act rebuilds instead of returning null (sync)")
        void getOrCreate_clearRacingTheLookup_neverReturnsNull() throws Exception {
            var racingCache = new ClearOnContainsKeyMap<Object, Object>();
            installCache(uniqueRegistry, "modelCache", racingCache);

            var params = Map.of("apiKey", "test");
            assertNotNull(uniqueRegistry.getOrCreate("openai", params), "first call must construct a model");

            ChatModel second = uniqueRegistry.getOrCreate("openai", params);

            assertNotNull(second, "A cache clear racing the lookup must fall through to construction, never hand null to the caller");
            assertEquals(0, racingCache.containsKeyCalls.get(),
                    "The lookup must be a single get(); containsKey() followed by get() is the check-then-act that returns null");
        }

        @Test
        @DisplayName("a clear landing between check and act rebuilds instead of returning null (streaming)")
        void getOrCreateStreaming_clearRacingTheLookup_neverReturnsNull() throws Exception {
            var racingCache = new ClearOnContainsKeyMap<Object, Object>();
            installCache(uniqueRegistry, "streamingModelCache", racingCache);

            var params = Map.of("apiKey", "test");
            assertNotNull(uniqueRegistry.getOrCreateStreaming("openai", params), "first call must construct a model");

            StreamingChatModel second = uniqueRegistry.getOrCreateStreaming("openai", params);

            assertNotNull(second, "A null here is indistinguishable from 'this provider cannot stream'");
            assertEquals(0, racingCache.containsKeyCalls.get(), "The streaming lookup must be a single get() too");
        }

        private void installCache(ChatModelRegistry target, String fieldName, Map<?, ?> replacement) throws Exception {
            Field field = ChatModelRegistry.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, replacement);
        }
    }

    /**
     * Cache map that makes the invalidation race deterministic: every
     * {@code containsKey} answers truthfully and then clears the map, i.e. exactly
     * the interleaving where {@code invalidateForSecret(null)} or the
     * global-variable listener lands between the check and the following
     * {@code get}. A single-{@code get} lookup never calls {@code containsKey} at
     * all, so the clear never happens.
     */
    private static final class ClearOnContainsKeyMap<K, V> extends ConcurrentHashMap<K, V> {
        private static final long serialVersionUID = 1L;

        private final transient AtomicInteger containsKeyCalls = new AtomicInteger();

        @Override
        public boolean containsKey(Object key) {
            containsKeyCalls.incrementAndGet();
            boolean present = super.containsKey(key);
            super.clear();
            return present;
        }
    }

    /**
     * D11 regression: {@code timeout}/{@code logRequests}/{@code logResponses}
     * shape the constructed model, so they must be part of the model cache key.
     * While they were stripped from the key, which model a task received depended
     * on which task happened to be constructed first — a task configured with a
     * timeout silently got a timeout-free model built for a different task.
     */
    @Nested
    @DisplayName("Observability params are part of the model identity (D11)")
    class ObservabilityCacheKeyTests {

        @Test
        @DisplayName("two tasks differing only in timeout get different sync models")
        void getOrCreate_differentTimeout_differentInstances() throws Exception {
            var slow = new HashMap<String, String>();
            slow.put("apiKey", "test");
            slow.put("timeout", "60000");

            var fast = new HashMap<String, String>();
            fast.put("apiKey", "test");
            fast.put("timeout", "1000");

            ChatModel slowModel = uniqueRegistry.getOrCreate("openai", slow);
            ChatModel fastModel = uniqueRegistry.getOrCreate("openai", fast);

            assertNotSame(slowModel, fastModel, "A different timeout must produce a different model instance");
            assertSame(slowModel, uniqueRegistry.getOrCreate("openai", slow), "Identical params must still hit the cache");
        }

        @Test
        @DisplayName("a task with a timeout is not served an unwrapped model built without one")
        void getOrCreate_timeoutlessFirst_doesNotPoisonTimeoutTask() throws Exception {
            var noTimeout = new HashMap<String, String>();
            noTimeout.put("apiKey", "test");

            var withTimeout = new HashMap<String, String>();
            withTimeout.put("apiKey", "test");
            withTimeout.put("timeout", "5000");

            // Construction order is the trigger: the timeout-free task is built first.
            ChatModel unwrapped = uniqueRegistry.getOrCreate("openai", noTimeout);
            ChatModel wrapped = uniqueRegistry.getOrCreate("openai", withTimeout);

            assertNotSame(unwrapped, wrapped, "The timeout task must not be served the cached timeout-free model");
            assertInstanceOf(ObservableChatModel.class, wrapped, "The timeout task must get a model that actually carries the timeout");
        }

        @Test
        @DisplayName("two tasks differing only in logRequests get different sync models")
        void getOrCreate_differentLogRequests_differentInstances() throws Exception {
            var quiet = new HashMap<String, String>();
            quiet.put("apiKey", "test");
            quiet.put("logRequests", "false");

            var loud = new HashMap<String, String>();
            loud.put("apiKey", "test");
            loud.put("logRequests", "true");

            assertNotSame(uniqueRegistry.getOrCreate("openai", quiet), uniqueRegistry.getOrCreate("openai", loud),
                    "A different logRequests must produce a different model instance");
        }

        @Test
        @DisplayName("two tasks differing only in logResponses get different sync models")
        void getOrCreate_differentLogResponses_differentInstances() throws Exception {
            var quiet = new HashMap<String, String>();
            quiet.put("apiKey", "test");
            quiet.put("logResponses", "false");

            var loud = new HashMap<String, String>();
            loud.put("apiKey", "test");
            loud.put("logResponses", "true");

            assertNotSame(uniqueRegistry.getOrCreate("openai", quiet), uniqueRegistry.getOrCreate("openai", loud),
                    "A different logResponses must produce a different model instance");
        }

        @Test
        @DisplayName("two streaming tasks differing only in timeout get different models")
        void getOrCreateStreaming_differentTimeout_differentInstances() throws Exception {
            var slow = new HashMap<String, String>();
            slow.put("apiKey", "test");
            slow.put("timeout", "60000");

            var fast = new HashMap<String, String>();
            fast.put("apiKey", "test");
            fast.put("timeout", "1000");

            assertNotSame(uniqueRegistry.getOrCreateStreaming("openai", slow), uniqueRegistry.getOrCreateStreaming("openai", fast),
                    "A different timeout must produce a different streaming model instance");
        }

        @Test
        @DisplayName("two streaming tasks differing only in logResponses get different models")
        void getOrCreateStreaming_differentLogResponses_differentInstances() throws Exception {
            var quiet = new HashMap<String, String>();
            quiet.put("apiKey", "test");
            quiet.put("logResponses", "false");

            var loud = new HashMap<String, String>();
            loud.put("apiKey", "test");
            loud.put("logResponses", "true");

            assertNotSame(uniqueRegistry.getOrCreateStreaming("openai", quiet), uniqueRegistry.getOrCreateStreaming("openai", loud),
                    "A different logResponses must produce a different streaming model instance");
        }

        @Test
        @DisplayName("a streaming task with a timeout gets a model that actually carries it")
        void getOrCreateStreaming_timeoutReachesBuilder() throws Exception {
            var params = new HashMap<String, String>();
            params.put("apiKey", "test");
            params.put("timeout", "7500");

            StreamingChatModel model = registry.getOrCreateStreaming("openai", params);

            assertNotNull(model);
            var seen = lastBuildStreamingParams.get();
            assertNotNull(seen, "streaming builder should have been invoked");
            assertEquals("7500", seen.get("timeout"),
                    "timeout must reach the streaming builder — it is the provider's streaming request timeout");
        }

        @Test
        @DisplayName("a streaming task with logResponses is wrapped for uniform logging")
        void getOrCreateStreaming_logResponses_wraps() throws Exception {
            var params = new HashMap<String, String>();
            params.put("apiKey", "test");
            params.put("logResponses", "true");

            StreamingChatModel model = registry.getOrCreateStreaming("openai", params);

            assertInstanceOf(ObservableStreamingChatModel.class, model,
                    "logResponses must be honoured on the streaming path, not silently discarded");
        }

        @Test
        @DisplayName("a streaming task without logging flags is returned unwrapped")
        void getOrCreateStreaming_noLogging_notWrapped() throws Exception {
            StreamingChatModel model = registry.getOrCreateStreaming("openai", Map.of("apiKey", "test"));
            assertSame(mockStreamingModel, model, "Without logging flags the raw streaming model should be returned");
        }
    }

    @Nested
    @DisplayName("Secret invalidation tests")
    class InvalidationTests {

        private ChatModelRegistry invalidationRegistry;

        /**
         * Build a registry whose builder produces UNIQUE ChatModel instances per
         * build() call. This is essential — if the builder returns the same instance,
         * assertSame can never distinguish a cache hit from a rebuild.
         */
        @BeforeEach
        void setUpUniqueBuilder() {
            Map<String, Provider<ILanguageModelBuilder>> builders = new HashMap<>();
            builders.put("openai", () -> new ILanguageModelBuilder() {
                @Override
                public ChatModel build(Map<String, String> parameters) {
                    return new ChatModel() {
                        @Override
                        public ChatResponse chat(List<ChatMessage> messages) {
                            return ChatResponse.builder().aiMessage(aiMessage("ok")).build();
                        }
                    };
                }

                @Override
                public StreamingChatModel buildStreaming(Map<String, String> parameters) {
                    return new StreamingChatModel() {
                        @Override
                        public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                        }
                    };
                }
            });

            SecretResolver secretResolver = mock(SecretResolver.class);
            when(secretResolver.resolveSecrets(any())).thenAnswer(inv -> inv.getArgument(0));
            GlobalVariableResolver globalVariableResolver = mock(GlobalVariableResolver.class);
            when(globalVariableResolver.resolveAll(any())).thenAnswer(inv -> inv.getArgument(0));
            invalidationRegistry = new ChatModelRegistry(builders, globalVariableResolver, secretResolver);
        }

        @Test
        @DisplayName("invalidateForSecret evicts only models using the changed secret")
        void invalidateForSecret_evictsOnlyMatching() throws Exception {
            var targetParams = new HashMap<String, String>();
            targetParams.put("apiKey", "${vault:openai-key}");
            ChatModel targetModel = invalidationRegistry.getOrCreate("openai", targetParams);

            var otherParams = new HashMap<String, String>();
            otherParams.put("apiKey", "${vault:anthropic-key}");
            ChatModel otherModel = invalidationRegistry.getOrCreate("openai", otherParams);

            // Invalidate only the openai secret
            invalidationRegistry.invalidateForSecret(new SecretReference("default", "openai-key"));

            // Target model should be evicted (new instance on rebuild)
            ChatModel rebuilt = invalidationRegistry.getOrCreate("openai", targetParams);
            assertNotSame(targetModel, rebuilt, "Evicted model should be rebuilt as a new instance");

            // Other model should still be the exact same cached instance
            ChatModel cached = invalidationRegistry.getOrCreate("openai", otherParams);
            assertSame(otherModel, cached, "Unrelated model should remain cached");
        }

        @Test
        @DisplayName("invalidateForSecret matches full vault reference form")
        void invalidateForSecret_fullForm_evicts() throws Exception {
            var params = new HashMap<String, String>();
            params.put("apiKey", "${vault:default/openai-key}");
            ChatModel original = invalidationRegistry.getOrCreate("openai", params);

            invalidationRegistry.invalidateForSecret(new SecretReference("default", "openai-key"));

            ChatModel rebuilt = invalidationRegistry.getOrCreate("openai", params);
            assertNotSame(original, rebuilt, "Full-form vault ref should be matched and evicted");
        }

        @Test
        @DisplayName("invalidateForSecret with non-default tenant does NOT evict default tenant models")
        void invalidateForSecret_nonDefaultTenant_noFalsePositive() throws Exception {
            // Bug regression: ${vault:openai-key} resolves to tenant "default".
            // Rotating acme's "openai-key" must NOT evict default-tenant models.
            var defaultParams = new HashMap<String, String>();
            defaultParams.put("apiKey", "${vault:openai-key}");
            ChatModel defaultModel = invalidationRegistry.getOrCreate("openai", defaultParams);

            // Invalidate acme tenant's secret with the same keyName
            invalidationRegistry.invalidateForSecret(new SecretReference("acme", "openai-key"));

            ChatModel stillCached = invalidationRegistry.getOrCreate("openai", defaultParams);
            assertSame(defaultModel, stillCached,
                    "Non-default tenant invalidation must not evict default-tenant models");
        }

        @Test
        @DisplayName("invalidateForSecret(null) clears all models")
        void invalidateForSecret_null_clearsAll() throws Exception {
            var params1 = new HashMap<String, String>();
            params1.put("apiKey", "${vault:key1}");
            ChatModel model1 = invalidationRegistry.getOrCreate("openai", params1);

            var params2 = new HashMap<String, String>();
            params2.put("apiKey", "${vault:key2}");
            ChatModel model2 = invalidationRegistry.getOrCreate("openai", params2);

            invalidationRegistry.invalidateForSecret(null);

            assertNotSame(model1, invalidationRegistry.getOrCreate("openai", params1),
                    "All models should be evicted on null (bulk rotation)");
            assertNotSame(model2, invalidationRegistry.getOrCreate("openai", params2),
                    "All models should be evicted on null (bulk rotation)");
        }

        @Test
        @DisplayName("invalidateForSecret with no matching models is a no-op")
        void invalidateForSecret_noMatch_leavesAllCached() throws Exception {
            var params = new HashMap<String, String>();
            params.put("apiKey", "${vault:openai-key}");
            ChatModel original = invalidationRegistry.getOrCreate("openai", params);

            invalidationRegistry.invalidateForSecret(new SecretReference("default", "unrelated-key"));

            ChatModel stillCached = invalidationRegistry.getOrCreate("openai", params);
            assertSame(original, stillCached, "Non-matching invalidation should leave cache intact");
        }

        @Test
        @DisplayName("invalidateForSecret evicts from both sync and streaming caches")
        void invalidateForSecret_evictsBothCaches() throws Exception {
            var params = new HashMap<String, String>();
            params.put("apiKey", "${vault:openai-key}");

            ChatModel syncOriginal = invalidationRegistry.getOrCreate("openai", params);
            StreamingChatModel streamOriginal = invalidationRegistry.getOrCreateStreaming("openai", params);
            assertNotNull(syncOriginal);
            assertNotNull(streamOriginal);

            invalidationRegistry.invalidateForSecret(new SecretReference("default", "openai-key"));

            ChatModel syncRebuilt = invalidationRegistry.getOrCreate("openai", params);
            StreamingChatModel streamRebuilt = invalidationRegistry.getOrCreateStreaming("openai", params);
            assertNotSame(syncOriginal, syncRebuilt, "Sync model should be evicted and rebuilt");
            assertNotSame(streamOriginal, streamRebuilt, "Streaming model should be evicted and rebuilt");
        }
    }
}
