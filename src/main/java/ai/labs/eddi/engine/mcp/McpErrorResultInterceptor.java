/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.engine.security.EscalationGuard;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.security.ForbiddenException;
import jakarta.annotation.Priority;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

import java.util.Optional;

import static ai.labs.eddi.engine.mcp.McpToolUtils.errorJson;

/**
 * Turns an MCP tool's failure into an MCP tool error ({@code isError: true}).
 * <p>
 * The tools report failures by <em>returning</em> the JSON that
 * {@link McpToolUtils#errorJson(String)} builds — a plain {@code String}, which
 * quarkus-mcp-server sends as a successful result. An MCP client (and the model
 * behind it) reads {@code isError} to tell a failed call from a successful one,
 * so every "Access denied", "not found" and validation failure looked like a
 * success that happened to contain the word "error".
 * <p>
 * Rather than change the return type of every tool (and every assertion on
 * those strings), this interceptor inspects the result: a string that is an
 * error payload is rethrown as {@link ToolCallException}, which the server
 * answers with {@code isError: true} and the very same JSON as the text
 * content. A role refusal ({@link ForbiddenException} from {@code requireRole}/
 * {@code requireAnyRole}) is converted the same way, with errorCode
 * {@code FORBIDDEN}, instead of escaping as a JSON-RPC internal error.
 * <p>
 * Only {@code @Tool} methods are touched; the payload test is
 * {@link #isErrorPayload(String)}, which matches exactly what the
 * {@code errorJson} builders emit.
 * <p>
 * It is also the receiving side of the email escalation guard (email plan v3
 * §5.3) for EDDI's MCP server: a call of one of
 * {@link EscalationGuard#GUARDED_TOOLS} from a request that carries the origin
 * taint ({@code X-Eddi-Turn-Origin: email}, as every MCP request a mail-started
 * turn sends does) fails with errorCode {@code EMAIL_ORIGIN} before the tool
 * runs — whatever name the calling client listed the tool under.
 */
@McpErrorResults
@Interceptor
@Priority(Interceptor.Priority.PLATFORM_AFTER)
public class McpErrorResultInterceptor {

    @AroundInvoke
    Object toToolError(InvocationContext context) throws Exception {
        Tool tool = context.getMethod().getAnnotation(Tool.class);
        if (tool == null) {
            return context.proceed();
        }
        Optional<String> escalation = EscalationGuard.refuseTool(toolName(tool, context.getMethod().getName()));
        if (escalation.isPresent()) {
            throw new ToolCallException(errorJson(escalation.get(), "EMAIL_ORIGIN", null));
        }
        Object result;
        try {
            result = context.proceed();
        } catch (ForbiddenException e) {
            throw new ToolCallException(errorJson(e.getMessage() != null ? e.getMessage() : "Access denied", "FORBIDDEN", null));
        }
        if (result instanceof String text && isErrorPayload(text)) {
            throw new ToolCallException(text);
        }
        return result;
    }

    /**
     * The name a client calls the tool by: its declared name, else the method's.
     */
    static String toolName(Tool tool, String methodName) {
        String declared = tool.name();
        return declared == null || declared.isBlank() || Tool.ELEMENT_NAME.equals(declared) ? methodName : declared;
    }

    /**
     * Whether {@code text} is a payload built by one of the
     * {@code McpToolUtils.errorJson} overloads. They all start with the
     * {@code error} member; no successful result does — the success payloads are
     * serialized records and maps whose first member is an id, a count or a status.
     */
    static boolean isErrorPayload(String text) {
        return text.startsWith("{\"error\":");
    }
}
