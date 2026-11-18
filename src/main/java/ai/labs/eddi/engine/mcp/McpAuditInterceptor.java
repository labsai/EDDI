/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.engine.audit.rest.AdminActionRecorder;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Records an MCP tool call that changes state in the admin-action ledger, the
 * way {@code AdminActionAuditFilter} records a mutating REST request: who (the
 * caller's principal), what (the tool), on whom (a user argument,
 * pseudonymised), and how it ended — a refusal is on record as well as a
 * success.
 * <p>
 * Runs outside {@link McpErrorResultInterceptor}, so an error result arrives
 * here as the {@link ToolCallException} that interceptor makes of it, and the
 * outcome is read from the error code it carries. Never the arguments, which
 * carry prompts, secrets and configuration. Recording never throws
 * ({@link AdminActionRecorder}): the tool's outcome does not depend on it.
 */
@McpAuditBinding
@Interceptor
@Priority(Interceptor.Priority.APPLICATION)
public class McpAuditInterceptor {

    @Inject
    AdminActionRecorder recorder;

    @Inject
    Instance<SecurityIdentity> identity;

    @AroundInvoke
    Object audit(InvocationContext context) throws Exception {
        Method method = context.getMethod();
        if (!method.isAnnotationPresent(McpAudited.class)) {
            return context.proceed();
        }
        Object[] parameters = context.getParameters();
        try {
            Object result = context.proceed();
            record(method, parameters, result instanceof String text && McpErrorResultInterceptor.isErrorPayload(text) ? statusOf(text) : 200);
            return result;
        } catch (ToolCallException e) {
            record(method, parameters, statusOf(e.getMessage()));
            throw e;
        } catch (ForbiddenException | UnauthorizedException e) {
            record(method, parameters, 403);
            throw e;
        } catch (Exception e) {
            record(method, parameters, 500);
            throw e;
        }
    }

    private void record(Method method, Object[] parameters, int status) {
        // As the REST filter: a request an HTTP path policy refuses (an unauthenticated
        // caller) is never on record, so a probe cannot fill the ledger. A refusal of
        // an
        // authenticated caller is.
        if (status == 403 && isAnonymous()) {
            return;
        }
        recorder.recordToolCall(toolName(method), subjectOf(method, parameters), targetsOf(method, parameters),
                method.getDeclaringClass().getSimpleName().replaceAll("_.*", "") + "#" + method.getName(), status);
    }

    private boolean isAnonymous() {
        try {
            return identity != null && identity.isResolvable() && identity.get().isAnonymous();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static List<String> targetsOf(Method method, Object[] parameters) {
        McpAudited audited = method.getAnnotation(McpAudited.class);
        List<String> targets = new ArrayList<>();
        if (audited == null || parameters == null) {
            return targets;
        }
        for (int index : audited.target()) {
            if (index >= 0 && index < parameters.length && parameters[index] instanceof String id) {
                targets.add(id);
            }
        }
        return targets;
    }

    /**
     * The tool's name as the client calls it: {@code @Tool(name)}, else the method
     * name.
     */
    static String toolName(Method method) {
        Tool tool = method.getAnnotation(Tool.class);
        return tool != null && !tool.name().isBlank() && !Tool.ELEMENT_NAME.equals(tool.name()) ? tool.name() : method.getName();
    }

    private static String subjectOf(Method method, Object[] parameters) {
        McpAudited audited = method.getAnnotation(McpAudited.class);
        if (audited == null || audited.subject() < 0 || parameters == null || audited.subject() >= parameters.length) {
            return null;
        }
        return parameters[audited.subject()] instanceof String subject ? subject : null;
    }

    /** The HTTP status an error payload stands for, from its {@code errorCode}. */
    static int statusOf(String errorPayload) {
        String code = errorCode(errorPayload);
        if (code == null) {
            return 400;
        }
        return switch (code) {
            case "FORBIDDEN" -> 403;
            case "NOT_FOUND" -> 404;
            case "CONFLICT" -> 409;
            case "BAD_REQUEST" -> 400;
            case "UNAVAILABLE" -> 503;
            case "TIMEOUT" -> 504;
            case "INTERNAL" -> 500;
            default -> 400; // WRONG_STATE, DISABLED, PLAN_CHANGED, ... : the caller's request was refused
        };
    }

    private static String errorCode(String payload) {
        if (payload == null) {
            return null;
        }
        int key = payload.indexOf("\"errorCode\":\"");
        if (key < 0) {
            return null;
        }
        int start = key + "\"errorCode\":\"".length();
        int end = payload.indexOf('"', start);
        return end < 0 ? null : payload.substring(start, end);
    }
}
