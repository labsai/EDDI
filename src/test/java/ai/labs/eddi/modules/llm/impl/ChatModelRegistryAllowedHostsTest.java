/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.modules.llm.impl.builder.ILanguageModelBuilder;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.VaultHostAllowlist;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import jakarta.inject.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The registry's half of M1: an editor who can set {@code baseUrl} must not be
 * able to send a vault-held key to a host of their own.
 */
@DisplayName("ChatModelRegistry — allowedHosts against the configured endpoint")
class ChatModelRegistryAllowedHostsTest {

    private ChatModelRegistry registry;
    private VaultHostAllowlist allowlist;

    @BeforeEach
    void setUp() {
        Map<String, Provider<ILanguageModelBuilder>> builders = new HashMap<>();
        builders.put("openai", () -> new ILanguageModelBuilder() {
            @Override
            public ChatModel build(Map<String, String> parameters) {
                return new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
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
        when(secretResolver.resolveSecrets(any())).thenAnswer(inv -> {
            Map<String, String> resolved = new HashMap<>(inv.getArgument(0));
            resolved.replaceAll((k, v) -> v != null && v.contains("${vault:") ? "resolved" : v);
            return resolved;
        });
        GlobalVariableResolver globalVariableResolver = mock(GlobalVariableResolver.class);
        when(globalVariableResolver.resolveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        allowlist = mock(VaultHostAllowlist.class);
        registry = new ChatModelRegistry(builders, globalVariableResolver, secretResolver, null, allowlist);
    }

    @Test
    @DisplayName("the configured baseUrl is checked with the unresolved references, on the sync and the streaming path")
    void baseUrlIsChecked() throws Exception {
        Map<String, String> params = Map.of("apiKey", "${vault:openai-key}", "baseUrl", "https://attacker.example/v1", "modelName", "gpt-x");

        assertNotNull(registry.getOrCreate("openai", params));
        assertNotNull(registry.getOrCreateStreaming("openai", params));

        verify(allowlist, times(2)).requireAllowed(argThatHoldsReference(), eq("https://attacker.example/v1"), eq("LLM provider 'openai'"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> argThatHoldsReference() {
        return argThat((Map<String, String> m) -> "${vault:openai-key}".equals(m.get("apiKey")));
    }

    @Test
    @DisplayName("a refusal fails the build, before the key is resolved into a client")
    void refusalFailsTheBuild() {
        doThrow(new VaultHostAllowlist.HostNotAllowedException("not on allowedHosts")).when(allowlist).requireAllowed(anyMap(), anyString(),
                anyString());

        assertThrows(VaultHostAllowlist.HostNotAllowedException.class, () -> registry.getOrCreate("openai",
                Map.of("apiKey", "${vault:openai-key}", "baseUrl", "https://attacker.example/v1")));
    }

    @Test
    @DisplayName("every endpoint key present is checked, not only the first — a builder may read the other one")
    void everyEndpointKeyChecked() throws Exception {
        assertNotNull(registry.getOrCreate("openai",
                Map.of("apiKey", "${vault:openai-key}", "baseUrl", "https://api.openai.com", "endpoint", "https://attacker.example")));

        verify(allowlist).requireAllowed(argThatHoldsReference(), eq("https://api.openai.com"), eq("LLM provider 'openai'"));
        verify(allowlist).requireAllowed(argThatHoldsReference(), eq("https://attacker.example"), eq("LLM provider 'openai'"));
    }

    @Test
    @DisplayName("without an endpoint parameter the provider's own default is used, which an editor cannot choose — nothing to check")
    void noEndpointNothingToCheck() throws Exception {
        assertNotNull(registry.getOrCreate("openai", Map.of("apiKey", "${vault:openai-key}")));

        verify(allowlist, never()).requireAllowed(anyMap(), anyString(), anyString());
    }
}
