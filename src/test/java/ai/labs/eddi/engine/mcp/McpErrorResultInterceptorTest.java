/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.engine.security.TurnOrigin;
import ai.labs.eddi.engine.security.TurnOriginTestSupport;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

        @Tool(name = "update_email_senders", description = "x")
        public String updateEmailSenders() {
            return "";
        }

        @Tool(description = "x")
        public String accept_team_handoff() {
            return "";
        }

        @Tool(name = "update_resource", description = "x")
        public String updateResource() {
            return "";
        }
    }

    @Test
    @DisplayName("a configuration tool from a tainted MCP request needs X-Eddi-Approved-Call; approval never unlocks a guarded tool")
    void configurationToolNeedsApproval() throws Exception {
        try (var request = TurnOriginTestSupport.inboundRequest(TurnOrigin.EMAIL)) {
            InvocationContext refused = invocation("updateResource", "{\"ok\":true}");
            var thrown = assertThrows(ToolCallException.class, () -> interceptor.toToolError(refused));
            assertTrue(thrown.getMessage().contains("unless a person approves"), thrown.getMessage());
            verify(refused, never()).proceed();

            try (var approved = TurnOriginTestSupport.inboundApproved(true)) {
                assertEquals("{\"ok\":true}", interceptor.toToolError(invocation("updateResource", "{\"ok\":true}")));
                assertThrows(ToolCallException.class, () -> interceptor.toToolError(invocation("updateEmailSenders", "{\"ok\":true}")));
            }
        }
        assertEquals("{\"ok\":true}", interceptor.toToolError(invocation("updateResource", "{\"ok\":true}")), "untainted");
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

    /**
     * The receiving side of the email escalation guard on EDDI's MCP server: a
     * request a mail-started turn sends carries {@code X-Eddi-Turn-Origin: email},
     * and a guarded tool fails before it runs — by its declared name or, without
     * one, by its method name.
     */
    @Test
    @DisplayName("a guarded tool called from a tainted MCP request fails with EMAIL_ORIGIN and never runs")
    void taintedRequestRefusedGuardedTool() throws Exception {
        for (String method : new String[]{"updateEmailSenders", "accept_team_handoff"}) {
            InvocationContext ctx = invocation(method, "{\"ok\":true}");
            try (var request = TurnOriginTestSupport.inboundRequest(TurnOrigin.EMAIL)) {
                var thrown = assertThrows(ToolCallException.class, () -> interceptor.toToolError(ctx));
                assertTrue(thrown.getMessage().contains("EMAIL_ORIGIN"), thrown.getMessage());
            }
            verify(ctx, never()).proceed();
        }
    }

    @Test
    @DisplayName("the same tool runs for an untainted request, and an unguarded tool for a tainted one")
    void othersRun() throws Exception {
        assertEquals("{\"ok\":true}", interceptor.toToolError(invocation("updateEmailSenders", "{\"ok\":true}")));
        try (var request = TurnOriginTestSupport.inboundRequest(TurnOrigin.EMAIL)) {
            assertEquals("{\"ok\":true}", interceptor.toToolError(invocation("aTool", "{\"ok\":true}")));
        }
    }
}
