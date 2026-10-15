/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;

/**
 * Trace-only facts about one LLM tool call that {@link ToolExecutionService}
 * cannot know by itself: which of the seven tool sources the tool came from,
 * and the id the model gave the call.
 *
 * <p>
 * They travel in the OpenTelemetry {@link Context} rather than as two more
 * parameters of {@code executeToolWrapped} on purpose. They are labels for the
 * {@code execute_tool} span and nothing else — no cache key, rate-limit bucket,
 * price or metric depends on them — so they belong with the trace, not in
 * {@link ToolInvocation}, whose equality is a cost and cache identity. The OTel
 * context is functional whether or not the SDK is installed, so this costs
 * nothing when tracing is off.
 * </p>
 *
 * <p>
 * Set by {@code ToolLoopRunner} around the one call it makes into the service:
 * </p>
 *
 * <pre>
 * try (Scope ignored = new ToolCallTrace(toolSource, toolRequest.id()).makeCurrent()) {
 *     result = toolExecutionService.executeToolWrapped(...);
 * }
 * </pre>
 *
 * @param source
 *            the tool's source ({@code builtin}, {@code http}, {@code mcp},
 *            {@code a2a}, {@code dynamic}, {@code memory}, {@code recall}), or
 *            null when unknown
 * @param callId
 *            the model-assigned tool call id, or null when the provider gave
 *            none
 */
public record ToolCallTrace(String source, String callId) {

    private static final ContextKey<ToolCallTrace> KEY = ContextKey.named("eddi.tool.call");

    /** Makes this the current call's trace facts until the scope is closed. */
    public Scope makeCurrent() {
        return Context.current().with(KEY, this).makeCurrent();
    }

    /** The facts set by the caller, or null when none were. */
    static ToolCallTrace current() {
        return Context.current().get(KEY);
    }
}
