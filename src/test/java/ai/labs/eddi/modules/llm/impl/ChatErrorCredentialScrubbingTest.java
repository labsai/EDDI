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
 * {@code llm:error:<taskId>}.
 */
@DisplayName("Chat error paths — credentials scrubbed by value")
class ChatErrorCredentialScrubbingTest {

    /**
     * A prefix-less value, built at runtime so no key-shaped literal is in the
     * source.
     */
    private static final String GATEWAY_VALUE = "gateway" + "q".repeat(12);

    @Test
    @DisplayName("the registry records what it resolved into a client, and only that")
    void registryRecordsResolvedCredentials() throws Exception {
        Map<String, Provider<ILanguageModelBuilder>> builders = new HashMap<>();
        builders.put("openai", () -> new ILanguageModelBuilder() {
            @Override
            public ChatModel build(Map<String, String> parameters) {
                return mock(ChatModel.class);
            }

            @Override
            public Set<String> secretParameters() {
                return Set.of("apiKey", "customQueryParams");
            }
        });
        SecretResolver secretResolver = mock(SecretResolver.class);
        when(secretResolver.resolveSecrets(any())).thenAnswer(inv -> {
            Map<String, String> resolved = new HashMap<>(inv.getArgument(0));
            resolved.replaceAll((k, v) -> v.replace("${vault:gateway-key}", GATEWAY_VALUE));
            return resolved;
        });
        // A second resolution would see a rotated value: the scrubber must record what
        // the build itself resolved, so it must not ask again.
        when(secretResolver.resolveValue(anyString())).thenReturn("rotated" + "r".repeat(12));
        GlobalVariableResolver globalVariableResolver = mock(GlobalVariableResolver.class);
        when(globalVariableResolver.resolveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        var registry = new ChatModelRegistry(builders, globalVariableResolver, secretResolver, null, mock(VaultHostAllowlist.class));
        registry.resolvedCredentials = new ResolvedCredentials();

        registry.getOrCreate("openai", Map.of("apiKey", "literal", "customQueryParams", "{\"api-key\":\"${vault:gateway-key}\"}"));

        String scrubbed = registry.resolvedCredentials.scrub("401: " + GATEWAY_VALUE + " is not valid for this deployment");
        assertFalse(scrubbed.contains(GATEWAY_VALUE), scrubbed);
        assertTrue(scrubbed.contains("is not valid"), scrubbed);
        // Only what replaced the reference: the JSON around it is not a secret.
        assertEquals("{\"api-key\":", registry.resolvedCredentials.scrub("{\"api-key\":"));
        verify(secretResolver, never()).resolveValue(anyString());
    }

    @Test
    @DisplayName("the plaintext of each reference is read off the resolved value, text around it excluded")
    void substitutedPlaintexts() {
        assertEquals(List.of(GATEWAY_VALUE),
                ChatModelRegistry.substitutedPlaintexts("{\"k\":\"${vault:a}\"}", "{\"k\":\"" + GATEWAY_VALUE + "\"}"));
        assertEquals(List.of("first-plain", "second-plain"),
                ChatModelRegistry.substitutedPlaintexts("${vault:a} and ${vault:b}", "first-plain and second-plain"));
        // Adjacent references are taken together.
        assertEquals(List.of("onetwo"), ChatModelRegistry.substitutedPlaintexts("${vault:a}${vault:b}", "onetwo"));
        // An unresolved reference is not a plaintext.
        assertEquals(List.of(), ChatModelRegistry.substitutedPlaintexts("x ${vault:a}", "x ${vault:a}"));
        assertEquals(List.of(), ChatModelRegistry.substitutedPlaintexts("plain", "plain"));
    }

    @Test
    @DisplayName("short values are not recorded: replacing them everywhere would shred unrelated text")
    void shortValuesAreNotRecorded() {
        var credentials = new ResolvedCredentials();
        credentials.record(List.of("abc"));

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
