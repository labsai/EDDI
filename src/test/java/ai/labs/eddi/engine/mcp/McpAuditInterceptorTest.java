/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.engine.audit.rest.AdminActionRecorder;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.security.ForbiddenException;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("McpAuditInterceptor")
class McpAuditInterceptorTest {

    /** A stand-in for a tool class. */
    static class Fixture {
        @Tool(name = "erase_things", description = "x")
        @McpAudited(subject = 1)
        public String erase(String ignored, String userId) {
            return "ok";
        }

        @Tool(description = "x")
        @McpAudited
        public String unnamed() {
            return "ok";
        }
    }

    private AdminActionRecorder recorder;
    private McpAuditInterceptor interceptor;

    @BeforeEach
    void setUp() {
        recorder = mock(AdminActionRecorder.class);
        interceptor = new McpAuditInterceptor();
        interceptor.recorder = recorder;
    }

    private InvocationContext context(String method, Object... parameters) throws Exception {
        Method target = Fixture.class.getMethod(method, method.equals("erase") ? new Class<?>[]{String.class, String.class} : new Class<?>[0]);
        InvocationContext context = mock(InvocationContext.class);
        when(context.getMethod()).thenReturn(target);
        when(context.getParameters()).thenReturn(parameters);
        return context;
    }

    @Test
    @DisplayName("a successful call is recorded as 200 with the tool, the subject and the Class#method")
    void success() throws Exception {
        var context = context("erase", "x", "user-1");
        when(context.proceed()).thenReturn("{\"status\":\"deleted\"}");

        assertEquals("{\"status\":\"deleted\"}", interceptor.audit(context));

        verify(recorder).recordToolCall(eq("erase_things"), eq("user-1"), eq("Fixture#erase"), eq(200));
    }

    @Test
    @DisplayName("a tool without an explicit name is recorded under its method name, and with no subject")
    void unnamedTool() throws Exception {
        var context = context("unnamed");
        when(context.proceed()).thenReturn("ok");

        interceptor.audit(context);

        verify(recorder).recordToolCall(eq("unnamed"), isNull(), anyString(), eq(200));
    }

    @Test
    @DisplayName("an error payload is recorded with the status its error code stands for")
    void errorPayload() throws Exception {
        var context = context("erase", "x", "user-1");
        when(context.proceed()).thenReturn(McpToolUtils.errorJson("nope", "FORBIDDEN", null));

        interceptor.audit(context);

        verify(recorder).recordToolCall(anyString(), anyString(), anyString(), eq(403));
    }

    @Test
    @DisplayName("the ToolCallException the error interceptor makes of a failure is recorded and rethrown")
    void toolCallException() throws Exception {
        var context = context("erase", "x", "user-1");
        when(context.proceed()).thenThrow(new ToolCallException(McpToolUtils.errorJson("gone", "NOT_FOUND", null)));

        assertThrows(ToolCallException.class, () -> interceptor.audit(context));

        verify(recorder).recordToolCall(anyString(), anyString(), anyString(), eq(404));
    }

    @Test
    @DisplayName("a plain error without a code is a 400; a role refusal a 403; anything else a 500 — all rethrown")
    void otherOutcomes() throws Exception {
        var plain = context("erase", "x", "u");
        when(plain.proceed()).thenReturn(McpToolUtils.errorJson("userId is required"));
        interceptor.audit(plain);
        verify(recorder).recordToolCall(anyString(), anyString(), anyString(), eq(400));

        var refused = context("erase", "x", "u");
        when(refused.proceed()).thenThrow(new ForbiddenException("no"));
        assertThrows(ForbiddenException.class, () -> interceptor.audit(refused));
        verify(recorder).recordToolCall(anyString(), anyString(), anyString(), eq(403));

        var broken = context("erase", "x", "u");
        when(broken.proceed()).thenThrow(new IllegalStateException("boom"));
        assertThrows(IllegalStateException.class, () -> interceptor.audit(broken));
        verify(recorder).recordToolCall(anyString(), anyString(), anyString(), eq(500));
    }

    @Test
    @DisplayName("statusOf maps the error codes the tools emit")
    void statusMapping() {
        assertEquals(409, McpAuditInterceptor.statusOf(McpToolUtils.errorJson("m", "CONFLICT", null)));
        assertEquals(500, McpAuditInterceptor.statusOf(McpToolUtils.errorJson("m", "INTERNAL", null)));
        assertEquals(400, McpAuditInterceptor.statusOf(McpToolUtils.errorJson("m")));
    }
}
