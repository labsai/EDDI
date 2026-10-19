/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Classifies the RAW result of one tool call for the {@link ToolRepeatGuard}.
 * <p>
 * Tool results have no common failure structure, so this maps the shapes EDDI
 * actually produces onto four outcomes:
 *
 * <ul>
 * <li>HTTP tool results are JSON with an integer {@code httpCode}: 2xx is a
 * success, 408/425/429/5xx are retryable, every other status is terminal.</li>
 * <li>An {@code {"error": "..."}} object (the http tool's exception shape) is
 * retryable: the cause is unknown, so the lenient class applies.</li>
 * <li>{@code "Error: ..."} strings from the execution pipeline are retryable —
 * timeouts, rate limits, tool-reported failures — except the two that cannot
 * change on a retry with the same input: rejected arguments and an unknown
 * tool, which are terminal.</li>
 * <li>An EDDI-authored {@code {"status":"NOT_EXECUTED"|"DENIED"}} envelope and
 * the loop-level refusals ({@code Budget exceeded}, an expired turn deadline)
 * mean the call never ran. They are {@link Outcome#NOT_EXECUTED} and are never
 * counted as failures, otherwise a refusal would breed further refusals.</li>
 * <li>Anything else is a success.</li>
 * </ul>
 * <p>
 * The classifier must be given the result BEFORE the tool-result guardrail
 * wraps it in a provenance envelope; see {@link ToolLoopRunner.ToolCallResult}.
 * <p>
 * Pure and static: no state, safe from any thread.
 */
final class ToolOutcomeClassifier {

    /** What happened to one tool call. */
    enum Outcome {
        SUCCESS, FAILED_RETRYABLE, FAILED_TERMINAL, NOT_EXECUTED;

        boolean isFailure() {
            return this == FAILED_RETRYABLE || this == FAILED_TERMINAL;
        }
    }

    /**
     * Results longer than this are not parsed. Failure bodies are capped at 2,000
     * characters by the http executor, so a result this large is a payload, and a
     * payload is a success.
     */
    static final int MAX_PARSED_CHARS = 16_384;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolOutcomeClassifier() {
        // utility class
    }

    /** Classifies a raw tool result; a null or blank result is a success. */
    static Outcome classify(String raw) {
        if (raw == null) {
            return Outcome.SUCCESS;
        }
        String text = raw.strip();
        if (text.isEmpty()) {
            return Outcome.SUCCESS;
        }
        if (text.charAt(0) == '{') {
            Outcome json = classifyJson(text);
            if (json != null) {
                return json;
            }
            return Outcome.SUCCESS;
        }
        return classifyText(text);
    }

    /**
     * {@code null} when the text is not a JSON object this classifier recognises.
     */
    private static Outcome classifyJson(String text) {
        if (text.length() > MAX_PARSED_CHARS) {
            return null;
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(text);
        } catch (Exception notJson) {
            return null;
        }
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode status = node.get("status");
        if (status != null && status.isTextual()
                && ("NOT_EXECUTED".equals(status.asText()) || "DENIED".equals(status.asText()))) {
            return Outcome.NOT_EXECUTED;
        }
        JsonNode httpCode = node.get("httpCode");
        if (httpCode != null && httpCode.isIntegralNumber()) {
            return classifyHttpCode(httpCode.asInt());
        }
        JsonNode error = node.get("error");
        if (error != null && error.isTextual() && !error.asText().isBlank()) {
            return Outcome.FAILED_RETRYABLE;
        }
        return null;
    }

    /**
     * A short, model-facing description of why a result was classified as a
     * failure, for the refusal text: {@code HTTP 403}, {@code timed out},
     * {@code rate limited}, or the generic {@code an error}.
     */
    static String describeFailure(String raw) {
        if (raw == null) {
            return "an error";
        }
        String text = raw.strip();
        if (!text.isEmpty() && text.charAt(0) == '{' && text.length() <= MAX_PARSED_CHARS) {
            try {
                JsonNode httpCode = MAPPER.readTree(text).get("httpCode");
                if (httpCode != null && httpCode.isIntegralNumber()) {
                    return "HTTP " + httpCode.asInt();
                }
            } catch (Exception notJson) {
                // fall through to the generic description
            }
        }
        if (text.startsWith("Error: Execution timed out")) {
            return "timed out";
        }
        if (text.startsWith("Error: Rate limit exceeded")) {
            return "rate limited";
        }
        return "an error";
    }

    /**
     * 2xx success; 408, 425, 429 and 5xx retryable; every other status terminal.
     */
    static Outcome classifyHttpCode(int code) {
        if (code >= 200 && code < 300) {
            return Outcome.SUCCESS;
        }
        if (code == 408 || code == 425 || code == 429 || code >= 500) {
            return Outcome.FAILED_RETRYABLE;
        }
        return Outcome.FAILED_TERMINAL;
    }

    private static Outcome classifyText(String text) {
        if (!isErrorText(text)) {
            return Outcome.SUCCESS;
        }
        if (text.startsWith("Error: Budget exceeded") || text.startsWith("Error: the turn deadline has passed")) {
            return Outcome.NOT_EXECUTED;
        }
        if (text.startsWith("Error: Tool '") && text.contains("' not found")) {
            return Outcome.FAILED_TERMINAL;
        }
        if (text.startsWith("Error: invalid arguments for tool")) {
            return Outcome.FAILED_TERMINAL;
        }
        // Timeouts, rate limits, tool-reported failures, internal errors, MCP
        // isError results (which reach here as an internal-error string) and
        // anything else with an unknown cause: the lenient class.
        return Outcome.FAILED_RETRYABLE;
    }

    /**
     * The failure prefixes EDDI's tools and providers use. Deliberately not a bare
     * {@code startsWith("Error")}: a scraped page titled "Error handling in Java"
     * is a successful result.
     */
    private static boolean isErrorText(String text) {
        return text.startsWith("Error:") || text.startsWith("Error calling ") || text.startsWith("Error listing ")
                || text.startsWith("Error reading ") || text.startsWith("Error executing tool");
    }
}
