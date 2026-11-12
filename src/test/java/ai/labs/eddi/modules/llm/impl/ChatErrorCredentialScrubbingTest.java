/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.engine.lifecycle.internal.LifecycleManager;
import ai.labs.eddi.modules.llm.impl.builder.ILanguageModelBuilder;
import ai.labs.eddi.secrets.ResolvedCredentials;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.VaultHostAllowlist;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import jakarta.inject.Provider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Review follow-up to the 2026-11 vault findings: a provider error that echoes
 * a credential without a recognisable prefix (a gateway's query key, a custom
 * header) is scrubbed by value before it reaches {@code taskErrors} or
 * {@code llm:error:<taskId>}. The registry captures each plaintext where it
 * substitutes the reference, so several references in one value are captured as
 * the separate secrets they are.
 */
@DisplayName("Chat error paths — credentials scrubbed by value")
class ChatErrorCredentialScrubbingTest {

    /**
     * A prefix-less value, built at runtime so no key-shaped literal is in the
     * source.
     */
    private static final String GATEWAY_VALUE = "gateway" + "q".repeat(12);

    private final AtomicReference<Map<String, String>> built = new AtomicReference<>();

    /**
     * A registry over a vault holding {@code secrets} by reference text, resolved
     * one reference at a time like the real resolver.
     */
    private ChatModelRegistry registry(SecretResolver secretResolver, Map<String, String> secrets) {
        Map<String, Provider<ILanguageModelBuilder>> builders = new HashMap<>();
        builders.put("openai", () -> new ILanguageModelBuilder() {
            @Override
            public ChatModel build(Map<String, String> parameters) {
                built.set(parameters);
                return mock(ChatModel.class);
            }

            @Override
            public Set<String> secretParameters() {
                return Set.of("apiKey", "customQueryParams", "customHeaders");
            }
        });
        when(secretResolver.resolveSecrets(any())).thenAnswer(inv -> {
            Map<String, String> resolved = new HashMap<>(inv.getArgument(0));
            resolved.replaceAll((k, v) -> {
                String out = v;
                for (var secret : secrets.entrySet()) {
                    out = out.replace(secret.getKey(), secret.getValue());
                }
                return out;
            });
            return resolved;
        });
        GlobalVariableResolver globalVariableResolver = mock(GlobalVariableResolver.class);
        when(globalVariableResolver.resolveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        var registry = new ChatModelRegistry(builders, globalVariableResolver, secretResolver, null, mock(VaultHostAllowlist.class));
        registry.resolvedCredentials = new ResolvedCredentials();
        return registry;
    }

    @Test
    @DisplayName("the registry records what it resolved into a client, and only that")
    void registryRecordsResolvedCredentials() throws Exception {
        SecretResolver secretResolver = mock(SecretResolver.class);
        // A second resolution would see a rotated value: the scrubber must record what
        // the build itself substituted, so it must not ask again.
        when(secretResolver.resolveValue(anyString())).thenReturn("rotated" + "r".repeat(12));
        var registry = registry(secretResolver, Map.of("${vault:gateway-key}", GATEWAY_VALUE));

        registry.getOrCreate("openai", Map.of("apiKey", "literal", "customQueryParams", "{\"api-key\":\"${vault:gateway-key}\"}"));

        assertEquals("{\"api-key\":\"" + GATEWAY_VALUE + "\"}", built.get().get("customQueryParams"));
        String scrubbed = registry.resolvedCredentials.scrub("401: " + GATEWAY_VALUE + " is not valid for this deployment");
        assertFalse(scrubbed.contains(GATEWAY_VALUE), scrubbed);
        assertTrue(scrubbed.contains("is not valid"), scrubbed);
        // Only what replaced the reference: the JSON around it is not a secret.
        assertEquals("{\"api-key\":", registry.resolvedCredentials.scrub("{\"api-key\":"));
        verify(secretResolver, never()).resolveValue(anyString());
    }

    @Test
    @DisplayName("two references with a separator that also occurs inside the first secret: each secret is captured in full")
    void separatorInsideASecretIsCapturedInFull() throws Exception {
        // The reviewer's cases: ${vault:a}0${vault:b} and ${vault:a}:${vault:b}, where
        // secret a itself contains the separator. Built at runtime, low-entropy.
        String withZero = "a".repeat(8) + "0" + "b".repeat(8);
        String withColon = "d".repeat(8) + ":" + "e".repeat(8);
        String second = "c".repeat(10);
        var registry = registry(mock(SecretResolver.class),
                Map.of("${vault:a}", withZero, "${vault:b}", second, "${vault:c}", withColon));

        registry.getOrCreate("openai", Map.of("apiKey", "${vault:a}0${vault:b}", "customHeaders", "${vault:c}:${vault:b}"));

        for (String secret : List.of(withZero, withColon)) {
            String scrubbed = registry.resolvedCredentials.scrub("provider rejected " + secret + " for this deployment");
            assertFalse(scrubbed.contains(secret.substring(0, 8)), scrubbed);
            assertFalse(scrubbed.contains(secret.substring(9)), scrubbed);
            assertTrue(scrubbed.contains("for this deployment"), scrubbed);
        }
        assertFalse(registry.resolvedCredentials.scrub("echo " + second).contains(second));
    }

    @Test
    @DisplayName("adjacent references with no separator are captured as two secrets")
    void adjacentReferencesAreCapturedSeparately() throws Exception {
        String one = "f".repeat(9);
        String two = "g".repeat(9);
        var registry = registry(mock(SecretResolver.class), Map.of("${vault:one}", one, "${vault:two}", two));

        registry.getOrCreate("openai", Map.of("apiKey", "${vault:one}${vault:two}"));

        assertEquals(one + two, built.get().get("apiKey"));
        assertFalse(registry.resolvedCredentials.scrub("rejected " + one + " only").contains(one));
        assertFalse(registry.resolvedCredentials.scrub("rejected " + two + " only").contains(two));
    }

    @Test
    @DisplayName("a short credential is not replaced everywhere, but a text that carries it anywhere is withheld whole")
    void shortCredentialWithholdsTheText() {
        var credentials = new ResolvedCredentials();
        String shortValue = "h".repeat(6);
        credentials.record(List.of(shortValue, "abc"));

        assertEquals(ResolvedCredentials.WITHHELD, credentials.scrub("rejected key " + shortValue + " for gateway"));
        // Joined to other letters or digits too.
        assertEquals(ResolvedCredentials.WITHHELD, credentials.scrub("prefix" + shortValue + "42"));
        assertEquals("connection refused", credentials.scrub("connection refused"));
        // Under four characters it cannot be covered on this process-wide path.
        assertEquals("abc is fine", credentials.scrub("abc is fine"));
        assertEquals("x", ResolvedCredentials.scrub(null, "x"));
    }

    @Test
    @DisplayName("a value pinned to a failure is scrubbed from it, and from a wrapper, after 20,000 other values have been recorded")
    void pinnedValueOutlivesTheBoundedSet() {
        var credentials = new ResolvedCredentials();
        var failure = new IllegalStateException("401: " + GATEWAY_VALUE + " rejected");
        credentials.pin(failure, List.of(GATEWAY_VALUE));
        List<String> churn = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            churn.add("churn-" + i + "-" + "c".repeat(8));
        }
        credentials.record(churn);
        var wrapped = new RuntimeException("task failed", failure);

        assertFalse(credentials.scrubFailure(failure, failure.getMessage()).contains(GATEWAY_VALUE));
        assertFalse(credentials.scrubFailure(wrapped, failure.getMessage()).contains(GATEWAY_VALUE));
        // A pinned value is the failing client's own: a short one withholds the text.
        var shortFailure = new IllegalStateException("rejected abc");
        credentials.pin(shortFailure, List.of("abc"));
        assertEquals(ResolvedCredentials.WITHHELD, credentials.scrubFailure(shortFailure, shortFailure.getMessage()));
    }

    /**
     * A registry whose models block inside the provider call until released, then
     * fail echoing the key they were built with — sync and streaming — over a vault
     * whose value the test rotates while the request is blocked.
     */
    private ChatModelRegistry blockingRegistry(Map<String, String> vault, ResolvedCredentials credentials, CountDownLatch entered,
                                               CountDownLatch release) {
        Map<String, Provider<ILanguageModelBuilder>> builders = new HashMap<>();
        builders.put("openai", () -> new ILanguageModelBuilder() {
            @Override
            public ChatModel build(Map<String, String> parameters) {
                String key = parameters.get("apiKey");
                return new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest request) {
                        blockUntilReleased(entered, release);
                        throw new IllegalStateException("provider rejected key " + key);
                    }
                };
            }

            @Override
            public StreamingChatModel buildStreaming(Map<String, String> parameters) {
                String key = parameters.get("apiKey");
                return new StreamingChatModel() {
                    @Override
                    public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                        blockUntilReleased(entered, release);
                        handler.onError(new IllegalStateException("provider rejected key " + key));
                    }
                };
            }

            @Override
            public Set<String> secretParameters() {
                return Set.of("apiKey");
            }
        });
        SecretResolver secretResolver = mock(SecretResolver.class);
        when(secretResolver.resolveSecrets(any())).thenAnswer(inv -> {
            Map<String, String> resolved = new HashMap<>(inv.getArgument(0));
            resolved.replaceAll((k, v) -> vault.getOrDefault(v, v));
            return resolved;
        });
        GlobalVariableResolver globalVariableResolver = mock(GlobalVariableResolver.class);
        when(globalVariableResolver.resolveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        var registry = new ChatModelRegistry(builders, globalVariableResolver, secretResolver, null, mock(VaultHostAllowlist.class));
        registry.resolvedCredentials = credentials;
        return registry;
    }

    private static void blockUntilReleased(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        try {
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static final ChatRequest REQUEST = ChatRequest.builder().messages(UserMessage.from("hello")).build();

    /**
     * While the request is blocked in the provider: the key is rotated, the model
     * evicted from the registry, and the process-wide set churned past its cap. The
     * global path is then shown not to hold the old key, so the outputs can only be
     * clean through the credentials pinned to the failure.
     */
    private static void rotateEvictAndChurn(Map<String, String> vault, ChatModelRegistry registry, ResolvedCredentials credentials) {
        vault.put("${vault:gateway-key}", "rotated" + "r".repeat(12));
        registry.invalidateForSecret(null);
        List<String> churn = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            churn.add("churn-" + i + "-" + "c".repeat(8));
        }
        credentials.record(churn);
        assertTrue(credentials.scrub("echo " + GATEWAY_VALUE).contains(GATEWAY_VALUE),
                "the process-wide set no longer holds the old key, so it cannot be what scrubs it");
    }

    private static void assertNoPartOfTheOldKey(String text) {
        assertFalse(text.contains(GATEWAY_VALUE), text);
        assertFalse(text.contains("qqqq"), text);
    }

    @Test
    @DisplayName("a sync request in flight while its key is rotated, its model evicted and the set churned fails scrubbed of the old key")
    void inFlightSyncFailureIsScrubbed() throws Exception {
        var credentials = new ResolvedCredentials(0);
        Map<String, String> vault = new ConcurrentHashMap<>(Map.of("${vault:gateway-key}", GATEWAY_VALUE));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var registry = blockingRegistry(vault, credentials, entered, release);
        ChatModel model = registry.getOrCreate("openai", Map.of("apiKey", "${vault:gateway-key}"));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> request = executor.submit(() -> model.chat(REQUEST));
            assertTrue(entered.await(10, TimeUnit.SECONDS), "the request reached the provider");

            rotateEvictAndChurn(vault, registry, credentials);
            release.countDown();
            ExecutionException thrown = assertThrows(ExecutionException.class, () -> request.get(10, TimeUnit.SECONDS));

            var failure = new RuntimeException("task failed", thrown.getCause());
            UnaryOperator<String> scrubber = text -> ResolvedCredentials.scrubFailure(credentials, failure, text);
            String fallback = String.valueOf(LlmFallbackHandler.describe(failure, scrubber).get("message"));
            String lifecycle = LifecycleManager.summarizeForAudit(failure, scrubber);
            assertNoPartOfTheOldKey(fallback);
            assertNoPartOfTheOldKey(lifecycle);
            assertTrue(fallback.contains("provider rejected key"), fallback);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("a streaming request in flight while its key is rotated, its model evicted and the set churned fails scrubbed of the old key")
    void inFlightStreamingFailureIsScrubbed() throws Exception {
        var credentials = new ResolvedCredentials(0);
        Map<String, String> vault = new ConcurrentHashMap<>(Map.of("${vault:gateway-key}", GATEWAY_VALUE));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var registry = blockingRegistry(vault, credentials, entered, release);
        StreamingChatModel model = registry.getOrCreateStreaming("openai", Map.of("apiKey", "${vault:gateway-key}"));
        AtomicReference<Throwable> error = new AtomicReference<>();
        var done = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> model.chat(REQUEST, new StreamingChatResponseHandler() {
                @Override
                public void onCompleteResponse(ChatResponse response) {
                    done.countDown();
                }

                @Override
                public void onError(Throwable e) {
                    error.set(e);
                    done.countDown();
                }
            }));
            assertTrue(entered.await(10, TimeUnit.SECONDS), "the request reached the provider");

            rotateEvictAndChurn(vault, registry, credentials);
            release.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "the request finished");

            Throwable failure = error.get();
            assertNotNull(failure);
            UnaryOperator<String> scrubber = text -> ResolvedCredentials.scrubFailure(credentials, failure, text);
            assertNoPartOfTheOldKey(String.valueOf(LlmFallbackHandler.describe(failure, scrubber).get("message")));
            assertNoPartOfTheOldKey(LifecycleManager.summarizeForAudit(failure, scrubber));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
    @Test
    @DisplayName("the registry ties a built model's credentials to that model")
    void registryRecordsAgainstTheModel() throws Exception {
        var registry = registry(mock(SecretResolver.class), Map.of("${vault:gateway-key}", GATEWAY_VALUE));
        var credentials = mock(ResolvedCredentials.class);
        registry.resolvedCredentials = credentials;

        var model = registry.getOrCreate("openai", Map.of("apiKey", "${vault:gateway-key}"));

        verify(credentials).record(same(model), eq(List.of(GATEWAY_VALUE)));
    }

    @Test
    @DisplayName("llm:error:<taskId> carries the scrubbed message")
    void fallbackDescriptionIsScrubbed() {
        var credentials = new ResolvedCredentials();
        credentials.record(List.of(GATEWAY_VALUE));

        var described = LlmFallbackHandler.describe(new IllegalStateException("rejected " + GATEWAY_VALUE), credentials::scrub);

        assertFalse(String.valueOf(described.get("message")).contains(GATEWAY_VALUE), String.valueOf(described.get("message")));
    }
}
