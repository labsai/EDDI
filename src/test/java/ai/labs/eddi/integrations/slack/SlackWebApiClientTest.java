/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SlackWebApiClient}.
 * <p>
 * These tests verify the constructor, exception contract for retryable errors,
 * and graceful handling of non-retryable API responses. Full HTTP integration
 * tests (using WireMock) are in the integration test suite.
 */
class SlackWebApiClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SlackWebApiClient client;

    @BeforeEach
    void setUp() {
        client = new SlackWebApiClient(objectMapper);
    }

    @Test
    void constructor_createsInstance() {
        assertNotNull(client);
    }

    @Test
    void postMessage_invalidToken_returnsNull() {
        // Slack returns HTTP 200 + ok:false for invalid tokens — non-retryable,
        // should return null without throwing
        String result = client.postMessage("Bearer xoxb-invalid", "C0123", null, "test message");
        assertNull(result);
    }

    @Test
    void postMessage_withThreadTs_returnsNull() {
        // Invalid token + thread_ts — verifies thread_ts is included without error
        String result = client.postMessage("Bearer xoxb-invalid", "C0123", "12345.000", "test");
        assertNull(result);
    }

    @Test
    void postMessage_specialCharacters_noSerializationError() {
        // Special chars (control chars, unicode) should be properly escaped by
        // Jackson — the call should reach the API without JSON serialization errors
        String textWithSpecials = "He said \"hello\"\npath\\file\ttab\u0000null";
        String result = client.postMessage("Bearer xoxb-invalid", "C0123", null, textWithSpecials);
        assertNull(result); // API rejects, but no crash
    }

    @Test
    void postMessage_nullText_noNPE() {
        // Null text should be serialized by Jackson as JSON null — should not NPE
        String result = client.postMessage("Bearer xoxb-invalid", "C0123", null, null);
        assertNull(result);
    }

    @Test
    void postMessage_emptyText_returnsNull() {
        String result = client.postMessage("Bearer xoxb-invalid", "C0123", null, "");
        assertNull(result);
    }

    @Test
    void postMessage_longMessage_returnsNull() {
        String longText = "a".repeat(10_000);
        String result = client.postMessage("Bearer xoxb-invalid", "C0123", null, longText);
        assertNull(result);
    }
}
