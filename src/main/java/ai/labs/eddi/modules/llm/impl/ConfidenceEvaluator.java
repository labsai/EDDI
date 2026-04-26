/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

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
 * {@code {"response":"...","confidence":0.0-1.0}} and parses the JSON. Falls
 * back to heuristic if parsing fails.</li>
 * <li><b>heuristic</b> — analyzes response text for hedging phrases, emptiness,
 * length.</li>
 * <li><b>judge_model</b> — uses a separate cheap model call to rate
 * confidence.</li>
 * <li><b>none</b> — always returns 1.0 (effectively disables escalation).</li>
 * </ul>
 */
class ConfidenceEvaluator {
    private static final Logger LOGGER = Logger.getLogger(ConfidenceEvaluator.class);

    // JSON extraction patterns
    private static final Pattern CONFIDENCE_JSON_PATTERN = Pattern.compile("\"confidence\"\\s*:\\s*(-?\\d+\\.?\\d*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESPONSE_JSON_PATTERN = Pattern.compile("\"response\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"", Pattern.DOTALL);

    // Hedging/uncertainty phrases (case-insensitive match)
    private static final String[] LOW_CONFIDENCE_PHRASES = {"i don't know", "i do not know", "i'm not sure", "i am not sure", "i cannot", "i can't",
            "i'm unable", "i am unable", "i don't have enough information", "i do not have enough information", "it's unclear", "it is unclear",
            "unfortunately, i", "i apologize", "i'm sorry but i cannot", "as an ai", "as a language model", "i would need more", "could you clarify",
            "could you provide more"};

    // Refusal phrases
    private static final String[] REFUSAL_PHRASES = {"i'm not able to", "i cannot fulfill", "i can't help with", "i'm not allowed",
            "this is outside my", "i must decline"};

    private ConfidenceEvaluator() {
        // static utility class
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
     * @return a result containing the actual response text and confidence score
     */
    static EvaluationResult evaluate(String strategy, String response, ChatModel judgeModel) {
        if (response == null || response.isBlank()) {
            return new EvaluationResult("", 0.0);
        }

        return switch (strategy != null ? strategy.toLowerCase() : "structured_output") {
            case "heuristic" -> evaluateHeuristic(response);
            case "judge_model" -> evaluateWithJudge(response, judgeModel);
            case "none" -> new EvaluationResult(response, 1.0);
            default -> evaluateStructuredOutput(response);
        };
    }

    /**
     * Parse structured output format: {@code {"response":"...", "confidence":
     * 0.85}} Falls back to heuristic if JSON parsing fails.
     */
    static EvaluationResult evaluateStructuredOutput(String response) {
        try {
            Matcher confidenceMatcher = CONFIDENCE_JSON_PATTERN.matcher(response);
            if (confidenceMatcher.find()) {
                double confidence = Double.parseDouble(confidenceMatcher.group(1));
                confidence = Math.max(0.0, Math.min(1.0, confidence)); // clamp

                // Extract the actual response text from JSON wrapper
                Matcher responseMatcher = RESPONSE_JSON_PATTERN.matcher(response);
                String actualResponse;
                if (responseMatcher.find()) {
                    actualResponse = responseMatcher.group(1).replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
                } else {
                    // Couldn't extract response field — return the whole thing
                    // but strip the JSON wrapper if it looks like one
                    actualResponse = stripJsonWrapper(response);
                }

                return new EvaluationResult(actualResponse, confidence);
            }
        } catch (NumberFormatException e) {
            LOGGER.debug("Failed to parse confidence from structured output, falling back to heuristic");
        }

        // Fallback to heuristic
        LOGGER.debug("No structured confidence found in response, using heuristic fallback");
        return evaluateHeuristic(response);
    }

    /**
     * Analyze response text heuristically for confidence signals.
     */
    static EvaluationResult evaluateHeuristic(String response) {
        if (response == null || response.isBlank()) {
            return new EvaluationResult("", 0.0);
        }

        String lowerResponse = response.toLowerCase().trim();

        // Very short responses are likely low quality
        if (lowerResponse.length() < 20) {
            return new EvaluationResult(response, 0.3);
        }

        // Check for refusal phrases
        for (String phrase : REFUSAL_PHRASES) {
            if (lowerResponse.contains(phrase)) {
                return new EvaluationResult(response, 0.2);
            }
        }

        // Check for hedging/uncertainty phrases
        for (String phrase : LOW_CONFIDENCE_PHRASES) {
            if (lowerResponse.contains(phrase)) {
                return new EvaluationResult(response, 0.4);
            }
        }

        // Decent length and no red flags → assume reasonable confidence
        return new EvaluationResult(response, 0.8);
    }

    /**
     * Use a separate model call to judge the quality/confidence of a response.
     */
    static EvaluationResult evaluateWithJudge(String response, ChatModel judgeModel) {
        if (judgeModel == null) {
            LOGGER.warn("judge_model strategy requested but no judge model available, falling back to heuristic");
            return evaluateHeuristic(response);
        }

        try {
            String judgePrompt = String.format("Rate the following AI response on a scale of 0.0 (completely unhelpful/incorrect/evasive) "
                    + "to 1.0 (fully confident, complete, and accurate). " + "Respond with ONLY a JSON object: {\"confidence\": <score>}\n\n"
                    + "Response to evaluate:\n%s", response);

            var judgeResponse = judgeModel.chat(
                    List.of(SystemMessage.from("You are a response quality evaluator. Output only valid JSON."), UserMessage.from(judgePrompt)));

            String judgeText = judgeResponse.aiMessage().text();
            if (judgeText != null) {
                Matcher matcher = CONFIDENCE_JSON_PATTERN.matcher(judgeText);
                if (matcher.find()) {
                    double confidence = Double.parseDouble(matcher.group(1));
                    confidence = Math.max(0.0, Math.min(1.0, confidence));
                    return new EvaluationResult(response, confidence);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Judge model evaluation failed: " + e.getMessage() + ", falling back to heuristic");
        }

        return evaluateHeuristic(response);
    }

    /**
     * Build the system message suffix that instructs the model to include
     * confidence. Only used for the "structured_output" strategy.
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
            // Try to extract just the useful part, skipping the confidence field
            Matcher matcher = RESPONSE_JSON_PATTERN.matcher(trimmed);
            if (matcher.find()) {
                return matcher.group(1).replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
            }
        }
        return response;
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
