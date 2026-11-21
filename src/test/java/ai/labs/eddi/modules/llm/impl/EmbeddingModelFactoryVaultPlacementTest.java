/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.VaultHostAllowlist;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import dev.langchain4j.model.embedding.request.EmbeddingInputType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Security review 2026-11, findings 1 and 3 on the embedding path: a vault
 * reference in {@code model} came back in the provider's error (which RAG
 * search and the ingestion status return), and a secret restricted to one
 * vendor's host could be sent to another vendor's fixed default by choosing
 * that provider.
 */
@DisplayName("EmbeddingModelFactory — vault references and default endpoints")
class EmbeddingModelFactoryVaultPlacementTest {

    private SecretResolver secretResolver;
    private GlobalVariableResolver globalVariableResolver;
    private EmbeddingModelFactory factory;

    @BeforeEach
    void setUp() {
        secretResolver = mock(SecretResolver.class);
        globalVariableResolver = mock(GlobalVariableResolver.class);
        when(globalVariableResolver.resolveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        factory = new EmbeddingModelFactory(globalVariableResolver, secretResolver);
    }

    private static RagConfiguration config(String provider, Map<String, String> params) {
        var config = new RagConfiguration();
        config.setName("kb");
        config.setEmbeddingProvider(provider);
        config.setEmbeddingParameters(params);
        return config;
    }

    @Test
    @DisplayName("the exploit: a reference in model is refused before it is resolved, and the refusal names only the key")
    void referenceInModelIsRefused() {
        var refusal = assertThrows(IllegalArgumentException.class, () -> factory.getOrCreate(
                config("openai", Map.of("apiKey", "own-key", "model", "${vault:any-secret}")), EmbeddingInputType.QUERY));

        assertTrue(refusal.getMessage().contains("'model'"), refusal.getMessage());
        assertFalse(refusal.getMessage().contains("any-secret"));
        verify(secretResolver, never()).resolveSecrets(any());
    }

    @Test
    @DisplayName("a ${vars:} that expands to a reference is caught after expansion")
    void variableIndirectionIsRefused() {
        when(globalVariableResolver.resolveAll(any())).thenAnswer(inv -> {
            Map<String, String> expanded = new HashMap<>(inv.getArgument(0));
            expanded.replaceAll((k, v) -> "${vars:m}".equals(v) ? "${vault:any-secret}" : v);
            return expanded;
        });

        assertThrows(IllegalArgumentException.class,
                () -> factory.getOrCreate(config("mistral", Map.of("apiKey", "k", "modelName", "${vars:m}")), EmbeddingInputType.DOCUMENT));
        verify(secretResolver, never()).resolveSecrets(any());
    }

    @Test
    @DisplayName("a provider without a credential parameter accepts no reference at all")
    void credentialFreeProvidersAcceptNone() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.getOrCreate(config("ollama", Map.of("baseUrl", "${vault:internal-url}")), EmbeddingInputType.DOCUMENT));
        assertThrows(IllegalArgumentException.class,
                () -> factory.getOrCreate(config("vertex", Map.of("project", "${vault:p}")), EmbeddingInputType.DOCUMENT));
    }

    @Test
    @DisplayName("a key restricted to api.openai.com is refused for Mistral's default host, before it is resolved")
    void restrictedKeyIsNotSentToAnotherVendorsDefault() throws Exception {
        ISecretProvider provider = mock(ISecretProvider.class);
        when(provider.getMetadata(any(SecretReference.class))).thenReturn(new SecretMetadata("default", "openai-key", Instant.EPOCH, null,
                null, null, "OpenAI", List.of("*"), List.of("api.openai.com")));
        factory.vaultHostAllowlist = new VaultHostAllowlist(provider);

        var refusal = assertThrows(VaultHostAllowlist.HostNotAllowedException.class,
                () -> factory.getOrCreate(config("mistral", Map.of("apiKey", "${vault:openai-key}")), EmbeddingInputType.DOCUMENT));
        assertTrue(refusal.getMessage().contains("api.mistral.ai"), refusal.getMessage());
        verify(secretResolver, never()).resolveSecrets(any());
    }

    @Test
    @DisplayName("default endpoints per provider, none once the provider's own key is set")
    void defaultEndpoints() {
        assertEquals("https://api.openai.com/v1", EmbeddingModelFactory.defaultEndpoint("openai", Map.of()));
        assertEquals("https://api.mistral.ai/v1", EmbeddingModelFactory.defaultEndpoint("mistral", Map.of()));
        assertEquals("https://api.cohere.ai/v1/", EmbeddingModelFactory.defaultEndpoint("cohere", Map.of()));
        assertEquals("https://generativelanguage.googleapis.com/v1beta", EmbeddingModelFactory.defaultEndpoint("gemini", Map.of()));
        assertEquals("http://localhost:11434", EmbeddingModelFactory.defaultEndpoint("ollama", Map.of()));
        assertEquals("https://bedrock-runtime.us-east-1.amazonaws.com", EmbeddingModelFactory.defaultEndpoint("bedrock", Map.of()));
        assertEquals("https://us-central1-aiplatform.googleapis.com", EmbeddingModelFactory.defaultEndpoint("vertex", Map.of()));
        assertNull(EmbeddingModelFactory.defaultEndpoint("azure-openai", Map.of()));
        assertNull(EmbeddingModelFactory.defaultEndpoint("openai", Map.of("baseUrl", "https://proxy.example/v1")));
        assertNull(EmbeddingModelFactory.defaultEndpoint("vertex", Map.of("endpoint", "x.example:443")));
    }
}
