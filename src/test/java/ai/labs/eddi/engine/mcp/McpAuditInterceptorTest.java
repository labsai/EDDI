/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.engine.audit.rest.AdminActionRecorder;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.inject.Instance;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("McpAuditInterceptor")
class McpAuditInterceptorTest {

    /** A stand-in for a tool class. */
    static class Fixture {
        @Tool(name = "erase_things", description = "x")
        @McpAudited(subject = 1, target = {0, 2})
        public String erase(String groupId, String userId, String itemId) {
            return "ok";
        }

        @Tool(description = "x")
        @McpAudited
        public String unnamed() {
            return "ok";
        }

        @Tool(description = "x")
        public String notMarked() {
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
        Method target = Fixture.class.getMethod(method,
                method.equals("erase") ? new Class<?>[]{String.class, String.class, String.class} : new Class<?>[0]);
        InvocationContext context = mock(InvocationContext.class);
        when(context.getMethod()).thenReturn(target);
        when(context.getParameters()).thenReturn(parameters);
        return context;
    }

    @SuppressWarnings("unchecked")
    private void caller(boolean anonymous) {
        var identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(anonymous);
        Instance<SecurityIdentity> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(identity);
        interceptor.identity = instance;
    }

    @Test
    @DisplayName("a successful call is recorded as 200 with the tool, the target ids, the subject and the Class#method")
    void success() throws Exception {
        var context = context("erase", "group-9", "user-1", "item-3");
        when(context.proceed()).thenReturn("{\"status\":\"deleted\"}");

        assertEquals("{\"status\":\"deleted\"}", interceptor.audit(context));

        verify(recorder).recordToolCall(eq("erase_things"), eq("user-1"), eq(List.of("group-9", "item-3")), eq("Fixture#erase"), eq(200));
    }

    @Test
    @DisplayName("a tool without an explicit name is recorded under its method name, with no subject and no target")
    void unnamedTool() throws Exception {
        var context = context("unnamed");
        when(context.proceed()).thenReturn("ok");

        interceptor.audit(context);

        verify(recorder).recordToolCall(eq("unnamed"), isNull(), eq(List.of()), anyString(), eq(200));
    }

    @Test
    @DisplayName("a method without the marker is not recorded")
    void unmarkedMethodIsSkipped() throws Exception {
        var context = context("notMarked");
        when(context.proceed()).thenReturn("ok");

        assertEquals("ok", interceptor.audit(context));

        verify(recorder, never()).recordToolCall(anyString(), anyString(), anyList(), anyString(), eq(200));
    }

    @Test
    @DisplayName("an error payload is recorded with the status its error code stands for")
    void errorPayload() throws Exception {
        var context = context("erase", "g", "user-1", "i");
        when(context.proceed()).thenReturn(McpToolUtils.errorJson("nope", "FORBIDDEN", null));

        interceptor.audit(context);

        verify(recorder).recordToolCall(anyString(), anyString(), anyList(), anyString(), eq(403));
    }

    @Test
    @DisplayName("the ToolCallException the error interceptor makes of a failure is recorded and rethrown")
    void toolCallException() throws Exception {
        var context = context("erase", "g", "user-1", "i");
        when(context.proceed()).thenThrow(new ToolCallException(McpToolUtils.errorJson("gone", "NOT_FOUND", null)));

        assertThrows(ToolCallException.class, () -> interceptor.audit(context));

        verify(recorder).recordToolCall(anyString(), anyString(), anyList(), anyString(), eq(404));
    }

    @Test
    @DisplayName("a plain error is 400, a refused request with another code 400, INTERNAL and a thrown exception 500; all rethrown or returned")
    void otherOutcomes() throws Exception {
        var plain = context("erase", "g", "u", "i");
        when(plain.proceed()).thenReturn(McpToolUtils.errorJson("userId is required"));
        interceptor.audit(plain);
        verify(recorder).recordToolCall(anyString(), anyString(), anyList(), anyString(), eq(400));

        var disabled = context("erase", "g", "u", "i");
        when(disabled.proceed()).thenReturn(McpToolUtils.errorJson("off", "DISABLED", null));
        interceptor.audit(disabled);

        var broken = context("erase", "g", "u", "i");
        when(broken.proceed()).thenThrow(new IllegalStateException("boom"));
        assertThrows(IllegalStateException.class, () -> interceptor.audit(broken));
        verify(recorder).recordToolCall(anyString(), anyString(), anyList(), anyString(), eq(500));
    }

    @Test
    @DisplayName("a refusal of an authenticated caller is recorded; an anonymous caller's refusal is not (a probe cannot fill the ledger)")
    void anonymousRefusalIsNotRecorded() throws Exception {
        caller(false);
        var known = context("erase", "g", "u", "i");
        when(known.proceed()).thenThrow(new ForbiddenException("no"));
        assertThrows(ForbiddenException.class, () -> interceptor.audit(known));
        verify(recorder).recordToolCall(anyString(), anyString(), anyList(), anyString(), eq(403));

        caller(true);
        var probe = context("erase", "g", "u", "i");
        when(probe.proceed()).thenThrow(new ForbiddenException("no"));
        assertThrows(ForbiddenException.class, () -> interceptor.audit(probe));
        var probeError = context("erase", "g", "u", "i");
        when(probeError.proceed()).thenReturn(McpToolUtils.errorJson("no", "FORBIDDEN", null));
        interceptor.audit(probeError);

        // still only the one entry of the authenticated caller
        verify(recorder).recordToolCall(anyString(), anyString(), anyList(), anyString(), eq(403));
    }

    @Test
    @DisplayName("statusOf maps the error codes the tools emit")
    void statusMapping() {
        assertEquals(409, McpAuditInterceptor.statusOf(McpToolUtils.errorJson("m", "CONFLICT", null)));
        assertEquals(500, McpAuditInterceptor.statusOf(McpToolUtils.errorJson("m", "INTERNAL", null)));
        assertEquals(400, McpAuditInterceptor.statusOf(McpToolUtils.errorJson("m", "WRONG_STATE", null)));
        assertEquals(400, McpAuditInterceptor.statusOf(McpToolUtils.errorJson("m")));
    }
}
