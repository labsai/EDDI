/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.modules.llm.impl.builder.ILanguageModelBuilder;
import ai.labs.eddi.secrets.ResolvedCredentials;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.VaultHostAllowlist;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.inject.Provider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
    @DisplayName("a short credential is not replaced everywhere, but a text that repeats it is withheld whole")
    void shortCredentialWithholdsTheText() {
        var credentials = new ResolvedCredentials();
        String shortValue = "h".repeat(6);
        credentials.record(List.of(shortValue, "abc"));

        assertEquals(ResolvedCredentials.WITHHELD, credentials.scrub("rejected key " + shortValue + " for gateway"));
        // Not as part of a longer word, and a text without it is unchanged.
        assertEquals("prefix" + shortValue + "suffix", credentials.scrub("prefix" + shortValue + "suffix"));
        assertEquals("connection refused", credentials.scrub("connection refused"));
        // Shorter than four characters is too unspecific to look for at all.
        assertEquals("abc is fine", credentials.scrub("abc is fine"));
        assertEquals("x", ResolvedCredentials.scrub(null, "x"));
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
