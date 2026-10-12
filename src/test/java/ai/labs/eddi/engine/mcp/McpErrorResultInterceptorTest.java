/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.security.ForbiddenException;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link McpErrorResultInterceptor}: a tool's error payload must reach the MCP
 * client as {@code isError: true}. quarkus-mcp-server answers a
 * {@link ToolCallException} with exactly that, using the exception message as
 * the text content — so the assertions below are on the exception and its
 * message.
 */
class McpErrorResultInterceptorTest {

    private final McpErrorResultInterceptor interceptor = new McpErrorResultInterceptor();

    static class Fixture {
        @Tool(name = "a_tool", description = "x")
        public String aTool() {
            return "";
        }

        public String notATool() {
            return "";
        }
    }

    private static InvocationContext invocation(String methodName, Object result) throws Exception {
        Method method = Fixture.class.getMethod(methodName);
        InvocationContext ctx = mock(InvocationContext.class);
        when(ctx.getMethod()).thenReturn(method);
        when(ctx.proceed()).thenReturn(result);
        return ctx;
    }

    @Test
    @DisplayName("an errorJson result becomes a tool error carrying the same JSON")
    void errorPayloadBecomesToolError() throws Exception {
        String payload = McpToolUtils.errorJson("Access denied: you do not own this conversation");

        var thrown = assertThrows(ToolCallException.class, () -> interceptor.toToolError(invocation("aTool", payload)));

        assertEquals(payload, thrown.getMessage());
    }

    @Test
    @DisplayName("the structured errorJson (errorCode/details) is recognised too")
    void structuredErrorPayloadBecomesToolError() throws Exception {
        String payload = McpToolUtils.errorJson("HITL mutations are disabled over MCP", "DISABLED", Map.of("property", "x"));

        var thrown = assertThrows(ToolCallException.class, () -> interceptor.toToolError(invocation("aTool", payload)));

        assertEquals(payload, thrown.getMessage());
    }

    @Test
    @DisplayName("a successful result passes through untouched")
    void successPassesThrough() throws Exception {
        String ok = "{\"agentId\":\"a\",\"error\":\"a field called error further in is still a success\"}";

        assertSame(ok, interceptor.toToolError(invocation("aTool", ok)));
        assertEquals("Created group 'x'", interceptor.toToolError(invocation("aTool", "Created group 'x'")));
    }

    @Test
    @DisplayName("a role refusal becomes a FORBIDDEN tool error instead of a JSON-RPC internal error")
    void roleRefusalBecomesForbiddenToolError() throws Exception {
        InvocationContext ctx = invocation("aTool", null);
        when(ctx.proceed()).thenThrow(new ForbiddenException("MCP operation requires one of roles: eddi-admin"));

        var thrown = assertThrows(ToolCallException.class, () -> interceptor.toToolError(ctx));

        assertTrue(thrown.getMessage().startsWith("{\"error\":\"MCP operation requires one of roles: eddi-admin\""), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("\"errorCode\":\"FORBIDDEN\""), thrown.getMessage());
    }

    @Test
    @DisplayName("methods that are not @Tool are never touched")
    void nonToolMethodsAreIgnored() throws Exception {
        String payload = McpToolUtils.errorJson("x");
        assertSame(payload, interceptor.toToolError(invocation("notATool", payload)));
    }

    @Test
    @DisplayName("isErrorPayload matches every errorJson builder and nothing else")
    void isErrorPayload() {
        assertTrue(McpErrorResultInterceptor.isErrorPayload(McpToolUtils.errorJson("m")));
        assertTrue(McpErrorResultInterceptor.isErrorPayload(McpToolUtils.errorJson("m", new RuntimeException("c"))));
        assertTrue(McpErrorResultInterceptor.isErrorPayload(McpToolUtils.errorJson("m", "NOT_FOUND", null)));
        assertFalse(McpErrorResultInterceptor.isErrorPayload("{\"status\":\"RESUMED\"}"));
        assertFalse(McpErrorResultInterceptor.isErrorPayload("Document not found: x"));
        assertFalse(McpErrorResultInterceptor.isErrorPayload(""));
    }
}
