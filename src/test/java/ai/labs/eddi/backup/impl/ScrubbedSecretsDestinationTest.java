/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * A target's hidden credential is put back only where the request still goes
 * where the target's did. Keeping a call's name and changing its host used to
 * bind the target's literal credential to a destination the editor chose.
 */
@DisplayName("ScrubbedSecrets — a hidden credential does not follow a changed destination")
class ScrubbedSecretsDestinationTest {

    private IJsonSerialization jsonSerialization;

    @BeforeEach
    void useRealJsonRoundTripping() throws Exception {
        var mapper = new ObjectMapper();
        jsonSerialization = Mockito.mock(IJsonSerialization.class);
        when(jsonSerialization.deserialize(anyString()))
                .thenAnswer(inv -> mapper.readValue((String) inv.getArgument(0), Object.class));
        when(jsonSerialization.serialize(any()))
                .thenAnswer(inv -> mapper.writeValueAsString(inv.getArgument(0)));
    }

    private static final String TARGET = """
            {"targetServerUrl":"https://billing.example.com","httpCalls":[
              {"name":"charge","request":{"path":"/charge",
                 "headers":{"Authorization":"Bearer live-billing-token"}}}]}""";

    @Test
    @DisplayName("a changed targetServerUrl keeps the placeholder")
    void changedServerUrl() throws Exception {
        String source = """
                {"targetServerUrl":"https://attacker.example.net","httpCalls":[
                  {"name":"charge","request":{"path":"/charge",
                     "headers":{"Authorization":"${vault:REDACTED}"}}}]}""";

        String merged = ScrubbedSecrets.restore(source, TARGET, jsonSerialization);

        assertFalse(merged.contains("live-billing-token"), merged);
        assertTrue(merged.contains(ScrubbedSecrets.PLACEHOLDER), merged);
    }

    @Test
    @DisplayName("an absolute request path pointing elsewhere keeps the placeholder")
    void changedAbsolutePath() throws Exception {
        String source = """
                {"targetServerUrl":"https://billing.example.com","httpCalls":[
                  {"name":"charge","request":{"path":"https://attacker.example.net/collect",
                     "headers":{"Authorization":"${vault:REDACTED}"}}}]}""";

        String merged = ScrubbedSecrets.restore(source, TARGET, jsonSerialization);

        assertFalse(merged.contains("live-billing-token"), merged);
    }

    private static final String LLM_TARGET = """
            {"tasks":[{"id":"t","type":"openai","port":443,"useTls":true,
              "parameters":{"apiKey":"live-openai-key","modelName":"gpt-a"}}]}""";

    @Test
    @DisplayName("a changed provider type keeps the placeholder: the key would go to the other vendor's default endpoint")
    void changedProviderType() throws Exception {
        String source = """
                {"tasks":[{"id":"t","type":"anthropic","port":443,"useTls":true,
                  "parameters":{"apiKey":"${vault:REDACTED}","modelName":"gpt-a"}}]}""";

        String merged = ScrubbedSecrets.restore(source, LLM_TARGET, jsonSerialization);

        assertFalse(merged.contains("live-openai-key"), merged);
        assertTrue(merged.contains(ScrubbedSecrets.PLACEHOLDER), merged);
    }

    @Test
    @DisplayName("a changed port or useTls keeps the placeholder")
    void changedPortOrTls() throws Exception {
        String port = """
                {"tasks":[{"id":"t","type":"openai","port":8080,"useTls":true,
                  "parameters":{"apiKey":"${vault:REDACTED}","modelName":"gpt-a"}}]}""";
        String tls = """
                {"tasks":[{"id":"t","type":"openai","port":443,"useTls":false,
                  "parameters":{"apiKey":"${vault:REDACTED}","modelName":"gpt-a"}}]}""";

        assertFalse(ScrubbedSecrets.restore(port, LLM_TARGET, jsonSerialization).contains("live-openai-key"));
        assertFalse(ScrubbedSecrets.restore(tls, LLM_TARGET, jsonSerialization).contains("live-openai-key"));
    }

    @Test
    @DisplayName("a new baseUrl carrying a vault fragment is a changed destination, not a target-bound value")
    void newBaseUrlWithAVaultFragment() throws Exception {
        // The target has no baseUrl, so nothing replaces the source's: merge keeps it,
        // and the target's key must not be restored beside it.
        String source = """
                {"tasks":[{"id":"t","type":"openai","port":443,"useTls":true,
                  "parameters":{"apiKey":"${vault:REDACTED}","modelName":"gpt-a",
                    "baseUrl":"https://attacker.example/v1?ref=${vault:openai}"}}]}""";

        String merged = ScrubbedSecrets.restore(source, LLM_TARGET, jsonSerialization);

        assertFalse(merged.contains("live-openai-key"), merged);
        assertTrue(merged.contains("attacker.example"), merged);
    }

    @Test
    @DisplayName("an unchanged provider type still gets its credential back")
    void unchangedProviderType() throws Exception {
        String source = """
                {"tasks":[{"id":"t","type":"openai","port":443,"useTls":true,
                  "parameters":{"apiKey":"${vault:REDACTED}","modelName":"gpt-b"}}]}""";

        assertTrue(ScrubbedSecrets.restore(source, LLM_TARGET, jsonSerialization).contains("live-openai-key"));
    }

    @Test
    @DisplayName("an unchanged destination still gets its credential back")
    void unchangedDestination() throws Exception {
        String source = """
                {"targetServerUrl":"https://billing.example.com","httpCalls":[
                  {"name":"charge","request":{"path":"/charge/v2",
                     "headers":{"Authorization":"${vault:REDACTED}"}}}]}""";

        String merged = ScrubbedSecrets.restore(source, TARGET, jsonSerialization);

        assertTrue(merged.contains("live-billing-token"), "a relative path change stays on the same host: " + merged);
    }

    @Test
    @DisplayName("the same origin spelled with its default port, other case or a trailing slash is not a change")
    void equivalentOriginSpellings() throws Exception {
        String source = """
                {"targetServerUrl":"HTTPS://Billing.Example.com:443/","httpCalls":[
                  {"name":"charge","request":{"path":"/charge",
                     "headers":{"Authorization":"${vault:REDACTED}"}}}]}""";

        String merged = ScrubbedSecrets.restore(source, TARGET, jsonSerialization);

        assertTrue(merged.contains("live-billing-token"), "the request still goes to the same origin: " + merged);
    }

    @Test
    @DisplayName("a vault reference is still the target's under a changed destination")
    void vaultReferenceTravels() throws Exception {
        String target = """
                {"parameters":{"baseUrl":"https://prod.example.com","apiKey":"${vault:prod-key}"}}""";
        String source = """
                {"parameters":{"baseUrl":"https://staging.example.com","apiKey":"${vault:staging-key}"}}""";

        String merged = ScrubbedSecrets.restore(source, target, jsonSerialization);

        assertTrue(merged.contains("${vault:prod-key}"), "allowedHosts guards where a vault secret may go: " + merged);
    }

    @Test
    @DisplayName("a long array is matched by identity, not scanned per element")
    void identityIndex() throws Exception {
        List<Map<String, Object>> sourceCalls = new ArrayList<>();
        List<Map<String, Object>> targetCalls = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            sourceCalls.add(Map.of("name", "call" + i, "headers", Map.of("Authorization", ScrubbedSecrets.PLACEHOLDER)));
            targetCalls.add(Map.of("name", "call" + (1999 - i), "headers", Map.of("Authorization", "Bearer t" + (1999 - i))));
        }
        var mapper = new ObjectMapper();
        String merged = ScrubbedSecrets.restore(mapper.writeValueAsString(Map.of("httpCalls", sourceCalls)),
                mapper.writeValueAsString(Map.of("httpCalls", targetCalls)), jsonSerialization);

        assertTrue(merged.contains("\"name\":\"call7\",\"headers\":{\"Authorization\":\"Bearer t7\"}")
                || merged.contains("\"headers\":{\"Authorization\":\"Bearer t7\"},\"name\":\"call7\""), "each call keeps its own token");
        assertFalse(merged.contains(ScrubbedSecrets.PLACEHOLDER), "every call has a counterpart");
    }

    @Test
    @DisplayName("a source that drops the target's endpoint goes to the provider default, and keeps the placeholder")
    void removedEndpoint() throws Exception {
        String target = """
                {"tasks":[{"id":"support","parameters":{"baseUrl":"https://gateway.internal.example.com","apiKey":"live-gateway-key"}}]}""";
        String source = """
                {"tasks":[{"id":"support","parameters":{"apiKey":"${vault:REDACTED}"}}]}""";

        String merged = ScrubbedSecrets.restore(source, target, jsonSerialization);

        assertFalse(merged.contains("live-gateway-key"), merged);
        assertTrue(merged.contains(ScrubbedSecrets.PLACEHOLDER), merged);
    }

    @Test
    @DisplayName("a destination that is not a string is compared too, not skipped")
    void nonStringHost() throws Exception {
        String target = """
                {"store":{"host":"qdrant.internal.example.com","apiKey":"live-qdrant-key"}}""";
        String source = """
                {"store":{"host":167772161,"apiKey":"${vault:REDACTED}"}}""";

        String merged = ScrubbedSecrets.restore(source, target, jsonSerialization);

        assertFalse(merged.contains("live-qdrant-key"), merged);
    }

    /**
     * Every key a model builder or embedding provider reads an endpoint from —
     * Vertex's {@code apiEndpoint}, the {@code apiBase} spellings the embedding
     * check refuses — is a destination here too. This list used to be its own, and
     * knew none of those three.
     */
    @ParameterizedTest
    @ValueSource(strings = {"apiEndpoint", "apiBase", "api_base", "API_BASE", "baseUrl", "baseURL", "base_url", "serverUrl", "apiUrl",
            "url", "uri", "endpoint", "host", "hostname"})
    @DisplayName("every endpoint parameter is a destination: changing it keeps the placeholder")
    void everyEndpointParameterIsADestination(String key) throws Exception {
        String target = """
                {"parameters":{"apiKey":"live-provider-key","%s":"https://good.example.com"}}""".formatted(key);
        String source = """
                {"parameters":{"apiKey":"${vault:REDACTED}","%s":"https://evil.example.net"}}""".formatted(key);

        String merged = ScrubbedSecrets.restore(source, target, jsonSerialization);

        assertFalse(merged.contains("live-provider-key"), key + ": " + merged);
        assertTrue(merged.contains(ScrubbedSecrets.PLACEHOLDER), key + ": " + merged);
    }

    @Test
    @DisplayName("a parameter that names no endpoint is not a destination")
    void ordinaryParameterIsNotADestination() throws Exception {
        String target = """
                {"parameters":{"apiKey":"live-provider-key","modelName":"gpt-a","apiVersion":"2024-01"}}""";
        String source = """
                {"parameters":{"apiKey":"${vault:REDACTED}","modelName":"gpt-b","apiVersion":"2025-01"}}""";

        String merged = ScrubbedSecrets.restore(source, target, jsonSerialization);

        assertTrue(merged.contains("live-provider-key"), merged);
    }
}
