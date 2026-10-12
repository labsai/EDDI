/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.security.ForbiddenException;
import jakarta.annotation.Priority;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

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
 */
@McpErrorResults
@Interceptor
@Priority(Interceptor.Priority.PLATFORM_AFTER)
public class McpErrorResultInterceptor {

    @AroundInvoke
    Object toToolError(InvocationContext context) throws Exception {
        if (!context.getMethod().isAnnotationPresent(Tool.class)) {
            return context.proceed();
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
     * Whether {@code text} is a payload built by one of the
     * {@code McpToolUtils.errorJson} overloads. They all start with the
     * {@code error} member; no successful result does — the success payloads are
     * serialized records and maps whose first member is an id, a count or a status.
     */
    static boolean isErrorPayload(String text) {
        return text.startsWith("{\"error\":");
    }
}
