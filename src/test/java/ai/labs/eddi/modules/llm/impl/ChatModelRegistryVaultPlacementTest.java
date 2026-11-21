/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.modules.llm.impl.builder.AnthropicLanguageModelBuilder;
import ai.labs.eddi.modules.llm.impl.builder.ILanguageModelBuilder;
import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.VaultHostAllowlist;
import ai.labs.eddi.secrets.VaultReferencePlacement.MisplacedVaultReferenceException;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
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

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Security review 2026-11, finding 1: an editor with a working key of their own
 * saved {@code "modelName": "${vault:any-secret}"}, and the provider's "model X
 * does not exist" error carried the plaintext back — to {@code taskErrors}, the
 * fallback handler and the {@code gen_ai.request.model} span attribute. The
 * registry now refuses a vault reference outside the builder's credential
 * parameters before anything is resolved or built.
 */
@DisplayName("ChatModelRegistry — vault references only in credential parameters")
class ChatModelRegistryVaultPlacementTest {

    private SecretResolver secretResolver;
    private GlobalVariableResolver globalVariableResolver;
    private LlmTelemetryListener telemetry;
    private final AtomicInteger builds = new AtomicInteger();
    private ChatModelRegistry registry;

    @BeforeEach
    void setUp() {
        Map<String, Provider<ILanguageModelBuilder>> builders = new HashMap<>();
        builders.put("openai", () -> new ILanguageModelBuilder() {
            @Override
            public ChatModel build(Map<String, String> parameters) {
                builds.incrementAndGet();
                return new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest chatRequest) {
                        return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
                    }
                };
            }

            @Override
            public StreamingChatModel buildStreaming(Map<String, String> parameters) {
                builds.incrementAndGet();
                return new StreamingChatModel() {
                    @Override
                    public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                    }
                };
            }

            @Override
            public Set<String> secretParameters() {
                return Set.of("apiKey", "customHeaders");
            }
        });
        builders.put("undeclared", () -> parameters -> {
            builds.incrementAndGet();
            return mock(ChatModel.class);
        });
        secretResolver = mock(SecretResolver.class);
        when(secretResolver.resolveSecrets(any())).thenAnswer(inv -> {
            Map<String, String> resolved = new HashMap<>(inv.getArgument(0));
            resolved.replaceAll((k, v) -> v != null && v.contains("${vault:") ? "resolved-plaintext" : v);
            return resolved;
        });
        globalVariableResolver = mock(GlobalVariableResolver.class);
        when(globalVariableResolver.resolveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        telemetry = mock(LlmTelemetryListener.class);
        registry = new ChatModelRegistry(builders, globalVariableResolver, secretResolver, telemetry, mock(VaultHostAllowlist.class));
    }

    @Test
    @DisplayName("the exploit: a reference in modelName is refused, naming the key and not the value, before anything is resolved")
    void referenceInModelNameIsRefused() {
        Map<String, String> params = Map.of("apiKey", "own-key", "modelName", "${vault:any-secret}");

        var sync = assertThrows(MisplacedVaultReferenceException.class, () -> registry.getOrCreate("openai", params));
        var streaming = assertThrows(MisplacedVaultReferenceException.class, () -> registry.getOrCreateStreaming("openai", params));

        for (var refusal : List.of(sync, streaming)) {
            assertTrue(refusal.getMessage().contains("'modelName'"), refusal.getMessage());
            assertFalse(refusal.getMessage().contains("any-secret"), "the message must not name the reference");
            assertFalse(refusal.getMessage().contains("resolved-plaintext"));
        }
        verify(secretResolver, never()).resolveSecrets(any());
        assertEquals(0, builds.get());
    }

    @Test
    @DisplayName("no model is built, so no telemetry ever carries the resolved model name")
    void noTelemetryForARefusedModelName() {
        assertThrows(MisplacedVaultReferenceException.class,
                () -> registry.getOrCreate("openai", Map.of("apiKey", "k", "modelName", "${vault:any-secret}")));

        verifyNoInteractions(telemetry);
    }

    @Test
    @DisplayName("a ${vars:} that expands to a vault reference is caught after expansion")
    void variableExpandingToAReferenceIsRefused() {
        when(globalVariableResolver.resolveAll(any())).thenAnswer(inv -> {
            Map<String, String> expanded = new HashMap<>(inv.getArgument(0));
            expanded.replaceAll((k, v) -> "${vars:model}".equals(v) ? "${vault:any-secret}" : v);
            return expanded;
        });

        var refusal = assertThrows(MisplacedVaultReferenceException.class,
                () -> registry.getOrCreate("openai", Map.of("apiKey", "k", "modelName", "${vars:model}")));
        assertTrue(refusal.getMessage().contains("'modelName'"));
        verify(secretResolver, never()).resolveSecrets(any());
    }

    @Test
    @DisplayName("the legacy ${eddivault:} spelling is refused the same way")
    void legacyReferenceIsRefused() {
        assertThrows(MisplacedVaultReferenceException.class,
                () -> registry.getOrCreate("openai", Map.of("apiKey", "k", "baseUrl", "https://x.example/${eddivault:any-secret}")));
    }

    @Test
    @DisplayName("references in the credential parameters keep working")
    void referencesInCredentialsAreResolved() throws Exception {
        assertNotNull(registry.getOrCreate("openai",
                Map.of("apiKey", "${vault:openai-key}", "customHeaders", "{\"X-Org\":\"${vault:org-token}\"}", "modelName", "gpt-x")));
        assertNotNull(registry.getOrCreateStreaming("openai", Map.of("apiKey", "${vault:openai-key}")));
        assertEquals(2, builds.get());
    }

    @Test
    @DisplayName("a prompt is never resolved, so reference-shaped text in it is not refused")
    void promptsAreExempt() throws Exception {
        assertNotNull(registry.getOrCreate("openai", Map.of("apiKey", "k", "systemMessage", "Explain ${vault:x} syntax")));
    }

    @Test
    @DisplayName("a builder that declares no credential parameters accepts no reference at all")
    void undeclaredBuilderFailsClosed() {
        var refusal = assertThrows(MisplacedVaultReferenceException.class,
                () -> registry.getOrCreate("undeclared", Map.of("apiKey", "${vault:k}")));
        assertTrue(refusal.getMessage().contains("takes no credential parameter"), refusal.getMessage());
    }

    @Test
    @DisplayName("finding 3: a key restricted to api.openai.com is not sent to Anthropic's default host by switching the type")
    void restrictedKeyIsNotSentToAnotherVendorsDefaultHost() throws Exception {
        ISecretProvider provider = mock(ISecretProvider.class);
        when(provider.getMetadata(any(SecretReference.class))).thenReturn(new SecretMetadata("default", "openai-key", Instant.EPOCH, null,
                null, null, "OpenAI", List.of("*"), List.of("api.openai.com")));
        Map<String, Provider<ILanguageModelBuilder>> builders = new HashMap<>();
        builders.put("anthropic", AnthropicLanguageModelBuilder::new);
        var anthropic = new ChatModelRegistry(builders, globalVariableResolver, secretResolver, telemetry, new VaultHostAllowlist(provider));

        var refusal = assertThrows(VaultHostAllowlist.HostNotAllowedException.class,
                () -> anthropic.getOrCreate("anthropic", Map.of("apiKey", "${vault:openai-key}", "modelName", "claude-x")));
        assertTrue(refusal.getMessage().contains("api.anthropic.com"), refusal.getMessage());
        verify(secretResolver, never()).resolveSecrets(any());
    }

    @Test
    @DisplayName("finding 3: a secret without allowedHosts still goes to the vendor's default host")
    void unrestrictedKeyStillReachesTheDefaultHost() throws Exception {
        ISecretProvider provider = mock(ISecretProvider.class);
        when(provider.getMetadata(any(SecretReference.class)))
                .thenReturn(new SecretMetadata("default", "anthropic-key", Instant.EPOCH, null, null, null, "Anthropic", List.of("*")));
        var allowlist = new VaultHostAllowlist(provider);

        allowlist.requireAllowed(Map.of("apiKey", "${vault:anthropic-key}"), new AnthropicLanguageModelBuilder().defaultEndpoint(Map.of()),
                "LLM provider 'anthropic'");
    }
}
