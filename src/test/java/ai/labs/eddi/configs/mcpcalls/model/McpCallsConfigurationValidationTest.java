/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.mcpcalls.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Findings A10 / I3 — a configuration the engine cannot honour as written is
 * reported instead of being accepted and silently ignored.
 * <p>
 * This is the write-boundary contract: {@code validate()} throws. Loading an
 * already-stored config is deliberately lenient (see
 * {@code McpCallsTaskFailurePathTest.ConfigureBackwardCompatibility}), so a
 * pre-existing config cannot take a whole agent down.
 */
@DisplayName("McpCallsConfiguration.validate()")
class McpCallsConfigurationValidationTest {

    private static McpCallsConfiguration config(String url, String transport) {
        var config = new McpCallsConfiguration();
        config.setMcpServerUrl(url);
        config.setTransport(transport);
        return config;
    }

    @Test
    @DisplayName("I3: an unimplemented transport is rejected with an actionable message")
    void rejectsStdioTransport() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> config("http://mcp.example.com/mcp", "stdio").validate());
        assertTrue(e.getMessage().contains("stdio"), e.getMessage());
        assertTrue(e.getMessage().contains("StreamableHTTP"), "message must say what IS supported: " + e.getMessage());
        assertTrue(e.getMessage().contains("sidecar"), "a stdio-only server has a supported answer — the message must name it: " + e.getMessage());
    }

    @Test
    @DisplayName("'sse' is accepted as a deprecated alias — the write path must not reject what the engine runs")
    void acceptsSseAsDeprecatedAlias() {
        // McpToolProviderManager deliberately honours "sse" (served over
        // StreamableHTTP, with a one-time deprecation warning) rather than stripping
        // every tool from an agent written against the old documentation. This
        // validator rejecting it made the two disagree, so a stored config could be
        // read and then not saved back unchanged.
        config("http://mcp.example.com/mcp", "sse").validate();
        config("http://mcp.example.com/mcp", "SSE").validate();
    }

    @Test
    @DisplayName("an unrecognised transport is still rejected, and the message points at the bridge")
    void rejectsUnknownTransport() {
        var e = assertThrows(IllegalArgumentException.class, () -> config("http://mcp.example.com/mcp", "websocket").validate());
        assertTrue(e.getMessage().contains("websocket"), e.getMessage());
    }

    @Test
    @DisplayName("I3: the implemented transport is accepted, case-insensitively")
    void acceptsHttpTransport() {
        config("http://mcp.example.com/mcp", "http").validate();
        config("http://mcp.example.com/mcp", "HTTP").validate();
        config("http://mcp.example.com/mcp", "streamable-http").validate();
        config("http://mcp.example.com/mcp", null).validate();
    }

    @Test
    @DisplayName("A10: a non-http scheme is rejected")
    void rejectsNonHttpUrl() {
        var e = assertThrows(IllegalArgumentException.class, () -> config("file:///etc/passwd", "http").validate());
        assertTrue(e.getMessage().contains("http"), e.getMessage());
    }

    @Test
    @DisplayName("a missing server URL is rejected")
    void rejectsMissingUrl() {
        assertThrows(IllegalArgumentException.class, () -> config(null, "http").validate());
        assertThrows(IllegalArgumentException.class, () -> config("   ", "http").validate());
    }
}
