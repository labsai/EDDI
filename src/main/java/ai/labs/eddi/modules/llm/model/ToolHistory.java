/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.model;

import java.util.List;

/**
 * The tool calls of one conversation as its stored tool-loop trace records
 * them: the body of {@code GET /llm/tools/history/{conversationId}}.
 * <p>
 * It carries only what the trace knows. Timing, cost and cache hits are not
 * recorded per call in the trace, so they are not reported here; a
 * conversation's tool spend is served by {@code GET
 * /llm/tools/costs/conversation/{conversationId}}.
 *
 * @param toolCalls
 *            every call in trace order, including calls refused before they ran
 * @param hasErrors
 *            whether any call did not succeed
 */
public record ToolHistory(List<Call> toolCalls, boolean hasErrors) {

    public ToolHistory {
        toolCalls = List.copyOf(toolCalls);
    }

    /**
     * One tool call.
     *
     * @param toolName
     *            the tool the model called
     * @param callId
     *            the model's id for the call; null when the provider sends none or
     *            the trace predates call ids
     * @param arguments
     *            the arguments as the trace stores them (secrets redacted); null
     *            for a call refused before it was traced as a call
     * @param result
     *            the result as the trace stores it (redacted, possibly truncated);
     *            null when the call never returned one
     * @param success
     *            true only when the call ran and its result is not a failure
     * @param error
     *            why the call did not succeed: the trace's own error code or
     *            message, the refusal's trace type ({@code tool_repeat_refused},
     *            {@code tool_not_activated}, {@code tool_call_capped}), the failed
     *            result's outcome ({@code failed_retryable},
     *            {@code failed_terminal}, {@code not_executed}),
     *            {@code blocked by tool-result guardrail}, or {@code no_result} for
     *            a call the trace records no outcome for; null on success
     */
    public record Call(String toolName, String callId, String arguments, String result, boolean success, String error) {
    }
}
