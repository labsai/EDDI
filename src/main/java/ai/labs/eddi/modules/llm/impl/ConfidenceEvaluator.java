/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.model.LlmConfiguration.HeuristicConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates the confidence of an LLM response using configurable strategies.
 * <p>
 * Four strategies are supported:
 * <ul>
 * <li><b>structured_output</b> — asks the LLM to return
 * {@code {"response":"...","confidence":0.0-1.0}} and parses the JSON (via a
 * real JSON parser first, regex as fallback). Falls back to heuristic if no
 * wrapper is found.</li>
 * <li><b>heuristic</b> — analyzes response text for hedging phrases, emptiness,
 * length. Phrases and thresholds are configurable via {@link HeuristicConfig};
 * when no configured phrase matches, language-agnostic signals (length +
 * JSON-structure) are used instead of a flat score.</li>
 * <li><b>judge_model</b> — uses a separate cheap model call to rate
 * confidence.</li>
 * <li><b>none</b> — always returns 1.0 (effectively disables escalation).</li>
 * </ul>
 */
class ConfidenceEvaluator {
    private static final Logger LOGGER = Logger.getLogger(ConfidenceEvaluator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // JSON extraction patterns (regex fallback only — a real JSON parse is tried
    // first)
    private static final Pattern CONFIDENCE_JSON_PATTERN = Pattern.compile("\"confidence\"\\s*:\\s*(-?\\d+\\.?\\d*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESPONSE_JSON_PATTERN = Pattern.compile("\"response\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"", Pattern.DOTALL);

    // Default hedging/uncertainty phrases (case-insensitive match) — English.
    // Overridable per deployment via HeuristicConfig for non-English agents.
    static final List<String> DEFAULT_LOW_CONFIDENCE_PHRASES = List.of("i don't know", "i do not know", "i'm not sure", "i am not sure",
            "i cannot", "i can't", "i'm unable", "i am unable", "i don't have enough information", "i do not have enough information",
            "it's unclear", "it is unclear", "unfortunately, i", "i apologize", "i'm sorry but i cannot", "as an ai", "as a language model",
            "i would need more", "could you clarify", "could you provide more");

    // Default refusal phrases — English.
    static final List<String> DEFAULT_REFUSAL_PHRASES = List.of("i'm not able to", "i cannot fulfill", "i can't help with", "i'm not allowed",
            "this is outside my", "i must decline");

    static final int DEFAULT_SHORT_LENGTH_THRESHOLD = 20;
    static final double DEFAULT_SHORT_SCORE = 0.3;
    static final double DEFAULT_REFUSAL_SCORE = 0.2;
    static final double DEFAULT_HEDGING_SCORE = 0.4;
    static final double DEFAULT_SCORE = 0.8;

    private ConfidenceEvaluator() {
        // static utility class
    }

    /**
     * Evaluate confidence based on the configured strategy (English defaults).
     */
    static EvaluationResult evaluate(String strategy, String response, ChatModel judgeModel) {
        return evaluate(strategy, response, judgeModel, null);
    }

    /**
     * Evaluate confidence based on the configured strategy.
     *
     * @param strategy
     *            the evaluation strategy name
     * @param response
     *            the LLM response text
     * @param judgeModel
     *            optional chat model for "judge_model" strategy (null for others)
     * @param heuristicConfig
     *            optional overrides for the heuristic strategy (null = defaults)
     * @return a result containing the actual response text and confidence score
     */
    static EvaluationResult evaluate(String strategy, String response, ChatModel judgeModel, HeuristicConfig heuristicConfig) {
        if (response == null || response.isBlank()) {
            return new EvaluationResult("", 0.0);
        }

        return switch (strategy != null ? strategy.toLowerCase() : "structured_output") {
            case "heuristic" -> evaluateHeuristic(response, heuristicConfig);
            case "judge_model" -> evaluateWithJudge(response, judgeModel, heuristicConfig);
            case "none" -> new EvaluationResult(response, 1.0);
            default -> evaluateStructuredOutput(response, heuristicConfig);
        };
    }

    /** Backward-compatible overload — English defaults. */
    static EvaluationResult evaluateStructuredOutput(String response) {
        return evaluateStructuredOutput(response, null);
    }

    /**
     * Parse structured output format: {@code {"response":"...", "confidence":
     * 0.85}}. Tries a real JSON parse first (so a stray {@code "confidence":}
     * inside answer content is not mistaken for the score), then a regex fallback,
     * then heuristic.
     */
    static EvaluationResult evaluateStructuredOutput(String response, HeuristicConfig heuristicConfig) {
        // Only treat the response as a confidence wrapper when it IS a single JSON
        // object (after stripping code fences). This avoids mistaking a stray
        // "confidence": value inside legitimate answer content — e.g. a code sample —
        // for the score. A correctly-following model wraps its ENTIRE response.
        String candidate = stripCodeFences(response.trim());
        if (candidate.startsWith("{") && candidate.endsWith("}")) {
            // 1. Real JSON parse (preferred).
            EvaluationResult parsed = parseWrapperObject(candidate);
            if (parsed != null) {
                return parsed;
            }
            // 2. Regex fallback for a malformed-but-object-shaped wrapper.
            try {
                Matcher confidenceMatcher = CONFIDENCE_JSON_PATTERN.matcher(candidate);
                if (confidenceMatcher.find()) {
                    double confidence = clamp(Double.parseDouble(confidenceMatcher.group(1)));
                    Matcher responseMatcher = RESPONSE_JSON_PATTERN.matcher(candidate);
                    // Use the fence-stripped candidate (not the raw response) so a fenced wrapper
                    // does not leak markdown fences into the extracted answer.
                    String actualResponse = responseMatcher.find() ? unescapeJsonString(responseMatcher.group(1)) : stripJsonWrapper(candidate);
                    return new EvaluationResult(actualResponse, confidence);
                }
            } catch (NumberFormatException e) {
                LOGGER.debug("Failed to parse confidence from structured output, falling back to heuristic");
            }
        }

        // 3. Heuristic fallback — response was not a JSON-object wrapper.
        LOGGER.debug("No structured confidence wrapper found, using heuristic fallback");
        return evaluateHeuristic(response, heuristicConfig);
    }

    /**
     * Parse a single JSON object string and, if it carries a numeric
     * {@code confidence} field, return the extracted response + clamped confidence.
     */
    private static EvaluationResult parseWrapperObject(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node != null && node.isObject() && node.has("confidence") && node.get("confidence").isNumber()) {
                double confidence = clamp(node.get("confidence").asDouble());
                JsonNode responseNode = node.get("response");
                String actualResponse;
                if (responseNode == null || responseNode.isNull()) {
                    // Wrapper without an explicit response field — return original text.
                    actualResponse = json;
                } else if (responseNode.isTextual()) {
                    actualResponse = responseNode.asText();
                } else {
                    // response is itself an object/array — preserve it as JSON text.
                    actualResponse = responseNode.toString();
                }
                return new EvaluationResult(actualResponse, confidence);
            }
        } catch (Exception e) {
            LOGGER.debugf("Not a parseable confidence wrapper: %s", e.getMessage());
        }
        return null;
    }

    /** Backward-compatible overload — English defaults. */
    static EvaluationResult evaluateHeuristic(String response) {
        return evaluateHeuristic(response, null);
    }

    /**
     * Analyze response text heuristically for confidence signals. Phrases and
     * thresholds come from {@code heuristicConfig} when provided (English defaults
     * otherwise). When no phrase matches, language-agnostic signals are used.
     */
    static EvaluationResult evaluateHeuristic(String response, HeuristicConfig heuristicConfig) {
        if (response == null || response.isBlank()) {
            return new EvaluationResult("", 0.0);
        }

        String lowerResponse = response.toLowerCase().trim();

        int shortThreshold = heuristicConfig != null && heuristicConfig.getShortLengthThreshold() != null
                ? heuristicConfig.getShortLengthThreshold()
                : DEFAULT_SHORT_LENGTH_THRESHOLD;
        // Scores may come from user config — clamp to [0,1] so a mis-set value cannot
        // produce an out-of-range confidence that breaks threshold gating / metrics.
        double shortScore = clamp(value(heuristicConfig != null ? heuristicConfig.getShortScore() : null, DEFAULT_SHORT_SCORE));
        double refusalScore = clamp(value(heuristicConfig != null ? heuristicConfig.getRefusalScore() : null, DEFAULT_REFUSAL_SCORE));
        double hedgingScore = clamp(value(heuristicConfig != null ? heuristicConfig.getHedgingScore() : null, DEFAULT_HEDGING_SCORE));
        double defaultScore = clamp(value(heuristicConfig != null ? heuristicConfig.getDefaultScore() : null, DEFAULT_SCORE));

        // Very short responses are likely low quality.
        if (lowerResponse.length() < shortThreshold) {
            return new EvaluationResult(response, shortScore);
        }

        List<String> refusalPhrases = heuristicConfig != null && heuristicConfig.getRefusalPhrases() != null
                ? heuristicConfig.getRefusalPhrases()
                : DEFAULT_REFUSAL_PHRASES;
        for (String phrase : refusalPhrases) {
            if (phrase != null && lowerResponse.contains(phrase.toLowerCase())) {
                return new EvaluationResult(response, refusalScore);
            }
        }

        List<String> lowConfidencePhrases = heuristicConfig != null && heuristicConfig.getLowConfidencePhrases() != null
                ? heuristicConfig.getLowConfidencePhrases()
                : DEFAULT_LOW_CONFIDENCE_PHRASES;
        for (String phrase : lowConfidencePhrases) {
            if (phrase != null && lowerResponse.contains(phrase.toLowerCase())) {
                return new EvaluationResult(response, hedgingScore);
            }
        }

        // No configured phrase matched → language-agnostic fallback. Without
        // language-specific signals we cannot tell hedging from confidence, so a
        // non-short response keeps the default score (very short responses were
        // already scored above). Deployments localize the phrase lists via
        // HeuristicConfig to sharpen this gate in non-English languages.
        return new EvaluationResult(response, defaultScore);
    }

    /**
     * Backward-compatible overload — English defaults for the heuristic fallback.
     */
    static EvaluationResult evaluateWithJudge(String response, ChatModel judgeModel) {
        return evaluateWithJudge(response, judgeModel, null);
    }

    /**
     * Use a separate model call to judge the quality/confidence of a response.
     * Falls back to the (configurable) heuristic when no judge model is available
     * or the judge call fails.
     */
    static EvaluationResult evaluateWithJudge(String response, ChatModel judgeModel, HeuristicConfig heuristicConfig) {
        if (judgeModel == null) {
            LOGGER.warn("judge_model strategy requested but no judge model available, falling back to heuristic");
            return evaluateHeuristic(response, heuristicConfig);
        }

        try {
            String judgePrompt = String.format("Rate the following AI response on a scale of 0.0 (completely unhelpful/incorrect/evasive) "
                    + "to 1.0 (fully confident, complete, and accurate). " + "Respond with ONLY a JSON object: {\"confidence\": <score>}\n\n"
                    + "Response to evaluate:\n%s", response);

            var judgeResponse = judgeModel.chat(
                    List.of(SystemMessage.from("You are a response quality evaluator. Output only valid JSON."), UserMessage.from(judgePrompt)));

            String judgeText = judgeResponse.aiMessage() != null ? judgeResponse.aiMessage().text() : null;
            if (judgeText != null) {
                // Prefer a real JSON parse of the judge's first object.
                String balanced = extractFirstBalancedObject(judgeText);
                if (balanced != null) {
                    try {
                        JsonNode node = MAPPER.readTree(balanced);
                        if (node != null && node.has("confidence") && node.get("confidence").isNumber()) {
                            return new EvaluationResult(response, clamp(node.get("confidence").asDouble()));
                        }
                    } catch (Exception ignored) {
                        // fall through to regex
                    }
                }
                // Regex over the FULL judge text — the confidence object may not be the first
                // balanced object in the reply (e.g. a reasoning object precedes it). The judge
                // output is our own controlled prompt, so there is no stray-confidence risk.
                Matcher matcher = CONFIDENCE_JSON_PATTERN.matcher(judgeText);
                if (matcher.find()) {
                    return new EvaluationResult(response, clamp(Double.parseDouble(matcher.group(1))));
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Judge model evaluation failed: " + e.getMessage() + ", falling back to heuristic");
        }

        return evaluateHeuristic(response, heuristicConfig);
    }

    /**
     * Build the system message suffix that instructs the model to include
     * confidence. Only used for the "structured_output" strategy in legacy mode.
     */
    static String buildConfidenceInstruction() {
        return "\n\nIMPORTANT: Wrap your entire response in a JSON object with exactly two fields: "
                + "\"response\" (your actual answer as a string) and \"confidence\" (a float between 0.0 and 1.0 "
                + "indicating how confident you are in your answer). Example: "
                + "{\"response\": \"The capital of France is Paris.\", \"confidence\": 0.95}";
    }

    /**
     * Strip JSON wrapper if the response looks like a structured output response.
     */
    private static String stripJsonWrapper(String response) {
        String trimmed = response.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            Matcher matcher = RESPONSE_JSON_PATTERN.matcher(trimmed);
            if (matcher.find()) {
                return unescapeJsonString(matcher.group(1));
            }
        }
        return response;
    }

    /**
     * Unescape a raw JSON string body (regex-extracted, so not parsed by Jackson).
     * Single-pass so an escaped backslash ({@code \\}) is consumed before the
     * following character — a chained {@code replace} would corrupt e.g.
     * {@code \\n} (an escaped backslash then a literal {@code n}).
     */
    private static String unescapeJsonString(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                char next = raw.charAt(++i);
                switch (next) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case '"' -> sb.append('"');
                    case '/' -> sb.append('/');
                    case '\\' -> sb.append('\\');
                    default -> sb.append('\\').append(next);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Remove surrounding markdown code fences (```json ... ``` or ``` ... ```). */
    private static String stripCodeFences(String text) {
        String t = text.trim();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            if (firstNewline >= 0) {
                // Multi-line: drop everything up to and including the first newline (the
                // ``` fence and any language token live on that line).
                t = t.substring(firstNewline + 1);
            } else {
                // Single-line form (```{...}``` or ```json {...}```): drop the leading fence
                // marker and an optional language token.
                int idx = 3;
                while (idx < t.length() && Character.isLetter(t.charAt(idx))) {
                    idx++;
                }
                t = t.substring(idx);
            }
            if (t.endsWith("```")) {
                t = t.substring(0, t.length() - 3);
            }
            t = t.trim();
        }
        return t;
    }

    /**
     * Extract the first balanced {@code {...}} JSON object from a string, honoring
     * string literals and escapes. Returns null if none is found.
     */
    static String extractFirstBalancedObject(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static double value(Double configured, double fallback) {
        return configured != null ? configured : fallback;
    }

    /**
     * Result of confidence evaluation.
     *
     * @param response
     *            the actual response text (possibly unwrapped from JSON)
     * @param confidence
     *            confidence score 0.0–1.0
     */
    record EvaluationResult(String response, double confidence) {
    }
}
