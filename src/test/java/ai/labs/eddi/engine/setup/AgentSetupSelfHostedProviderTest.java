/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.setup;

import ai.labs.eddi.engine.setup.AgentSetupService.AgentSetupException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WS10: setting up an agent on a self-hosted {@code openai-compatible} endpoint
 * needs a model and a base URL (there is no default for either), and no API
 * key.
 */
@DisplayName("AgentSetupService — openai-compatible provider")
class AgentSetupSelfHostedProviderTest {

    @Test
    @DisplayName("no API key is required")
    void keyless() {
        assertTrue(AgentSetupService.isLocalLlmProvider("openai-compatible"));
    }

    @Test
    @DisplayName("a complete http endpoint passes")
    void complete() {
        assertDoesNotThrow(() -> AgentSetupService.requireSelfHostedEndpoint("openai-compatible", "granite", "http://vllm:8000/v1", "baseUrl"));
    }

    @Test
    @DisplayName("no model: refused instead of injecting the SaaS default model")
    void modelRequired() {
        var e = assertThrows(AgentSetupException.class,
                () -> AgentSetupService.requireSelfHostedEndpoint("openai-compatible", " ", "http://vllm:8000/v1", "baseUrl"));
        assertTrue(e.getMessage().contains("requires model"), e.getMessage());
    }

    @Test
    @DisplayName("no base URL: refused, naming the request field")
    void urlRequired() {
        var e = assertThrows(AgentSetupException.class,
                () -> AgentSetupService.requireSelfHostedEndpoint("openai-compatible", "granite", null, "llmBaseUrl"));
        assertTrue(e.getMessage().contains("llmBaseUrl"), e.getMessage());
    }

    @Test
    @DisplayName("a non-http URL and the metadata service are refused")
    void badUrls() {
        assertThrows(AgentSetupException.class,
                () -> AgentSetupService.requireSelfHostedEndpoint("openai-compatible", "granite", "ftp://x/v1", "baseUrl"));
        assertThrows(AgentSetupException.class,
                () -> AgentSetupService.requireSelfHostedEndpoint("openai-compatible", "granite", "http://169.254.169.254/v1", "baseUrl"));
    }

    @Test
    @DisplayName("every other provider is untouched")
    void otherProviders() {
        assertDoesNotThrow(() -> AgentSetupService.requireSelfHostedEndpoint("openai", null, null, "baseUrl"));
        assertDoesNotThrow(() -> AgentSetupService.requireSelfHostedEndpoint(null, null, null, "baseUrl"));
    }
}
