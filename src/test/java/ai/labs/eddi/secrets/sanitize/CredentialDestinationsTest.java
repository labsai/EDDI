/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.sanitize;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every field that decides where a request goes, in every configuration that
 * can hold a literal credential beside it, must be a destination field — or an
 * editor can change it beside a hidden key (JSON Patch) and an upgrade restores
 * the target's key under it ({@code ScrubbedSecrets}). A new destination
 * parameter belongs in the list below.
 */
@DisplayName("CredentialDestinations — which fields decide where a request goes")
class CredentialDestinationsTest {

    @ParameterizedTest(name = "{0} is a destination field")
    @ValueSource(strings = {
            // LLM builders (modules/llm/impl/builder): OpenAI, Anthropic, Mistral, Gemini,
            // self-hosted and OpenAI-compatible baseUrl; Azure OpenAI endpoint; Vertex
            // Gemini apiEndpoint; the OpenAI-compatible providers derive the base URL
            // from region.
            "baseUrl", "endpoint", "apiEndpoint", "region",
            // The task's provider and the cascade / judge / consolidation / dream model.
            "type", "llmProvider",
            // RAG: embedding provider and parameters (EmbeddingModelFactory baseUrl and
            // endpoint), store type and storeParameters (EmbeddingStoreFactory host,
            // port, useTls, serverUrl, baseUrl, endpoint).
            "embeddingProvider", "storeType", "host", "port", "useTls", "serverUrl",
            // MCP calls (mcpCalls mcpServerUrl), LLM mcpServers[] / a2aAgents[] url.
            "mcpServerUrl", "url", "uri",
            // httpcalls: targetServerUrl (request.path is covered when absolute).
            "targetServerUrl",
            // Email channel settings: host / smtpHost / port / smtpPort.
            "smtpHost", "smtpPort", "hostname"})
    void destinationFields(String name) {
        assertTrue(CredentialDestinations.isDestinationField(name, "a", "b"), name);
    }

    @ParameterizedTest(name = "{0} is not a destination field")
    @ValueSource(strings = {"apiKey", "modelName", "temperature", "maxTokens", "serverName", "database", "databaseName", "transport",
            "report", "description", "name"})
    void nonDestinationFields(String name) {
        assertFalse(CredentialDestinations.isDestinationField(name, "a", "b"), name);
    }

    @ParameterizedTest(name = "path ''{0}'' is absolute")
    @ValueSource(strings = {"https://attacker.example/x", "http:/attacker.example/x", "https:attacker.example/x", "HTTP://attacker.example",
            "  http://attacker.example", "httpbin.example/x"})
    void absolutePathsAreAtLeastAsWideAsTheExecutor(String path) {
        // ApiCallExecutor.buildRequest uses any path that starts with "http" as the
        // whole URL and drops targetServerUrl.
        assertTrue(CredentialDestinations.isAbsolute(path), path);
        assertTrue(CredentialDestinations.isDestinationField("path", path, "/v1/items"), path);
    }

    @ParameterizedTest(name = "path ''{0}'' stays on targetServerUrl")
    @ValueSource(strings = {"/v1/items", "v1/items", "/search?q={properties.q}"})
    void relativePathsAreNotDestinations(String path) {
        assertFalse(CredentialDestinations.isDestinationField("path", path, "/v1/other"), path);
    }

    @Test
    @DisplayName("apiEndpoint added beside a key is a change; the same origin on another path is not")
    void apiEndpointChange() {
        assertEquals("apiEndpoint", CredentialDestinations.changedDestinationField(Map.of("apiKey", "k", "apiEndpoint", "evil.example:443"),
                Map.of("apiKey", "k"), value -> false));
        assertNull(CredentialDestinations.changedDestinationField(Map.of("baseUrl", "https://a.example/v2"),
                Map.of("baseUrl", "https://A.example:443/v1"), value -> false));
    }
}
