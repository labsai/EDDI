/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.impl.ConfidenceEvaluator.EvaluationResult;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.HeuristicConfig;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Tests for the hardened {@link ConfidenceEvaluator}: JSON-parse-first
 * structured output, stray-confidence safety, config-driven heuristics, and
 * judge-model parsing.
 */
@DisplayName("ConfidenceEvaluator — Enterprise")
class ConfidenceEvaluatorEnterpriseTest {

    // ─── structured_output: JSON-parse-first ─────────────────────────

    @Test
    @DisplayName("valid wrapper — parses confidence and unwraps response")
    void wrapper_parsed() {
        var r = ConfidenceEvaluator.evaluateStructuredOutput("{\"response\": \"Paris\", \"confidence\": 0.88}", null);
        assertEquals(0.88, r.confidence(), 0.001);
        assertEquals("Paris", r.response());
    }

    @Test
    @DisplayName("wrapper inside a markdown code fence — still parsed")
    void wrapper_inCodeFence() {
        String fenced = "```json\n{\"response\": \"42\", \"confidence\": 0.7}\n```";
        var r = ConfidenceEvaluator.evaluateStructuredOutput(fenced, null);
        assertEquals(0.7, r.confidence(), 0.001);
        assertEquals("42", r.response());
    }

    @Test
    @DisplayName("stray \"confidence\" inside prose answer — NOT mistaken for the score")
    void strayConfidence_ignored() {
        // A confident, prose answer that happens to contain a JSON snippet with a
        // low confidence value. Must NOT be read as the score → heuristic applies.
        String prose = "Here is an example payload: {\"confidence\": 0.1}. This is a full, detailed explanation of the concept.";
        var r = ConfidenceEvaluator.evaluateStructuredOutput(prose, null);
        assertEquals(0.8, r.confidence(), 0.001, "prose answer should be scored heuristically, not by the stray value");
        assertEquals(prose, r.response());
    }

    @Test
    @DisplayName("confidence is clamped to [0,1]")
    void confidence_clamped() {
        assertEquals(1.0, ConfidenceEvaluator.evaluateStructuredOutput("{\"response\":\"x\",\"confidence\": 5.0}", null).confidence(), 0.001);
        assertEquals(0.0, ConfidenceEvaluator.evaluateStructuredOutput("{\"response\":\"x\",\"confidence\": -2.0}", null).confidence(), 0.001);
    }

    @Test
    @DisplayName("wrapper with nested-object response — preserved as JSON text")
    void wrapper_nestedResponse() {
        var r = ConfidenceEvaluator.evaluateStructuredOutput("{\"response\": {\"a\": 1}, \"confidence\": 0.6}", null);
        assertEquals(0.6, r.confidence(), 0.001);
        assertTrue(r.response().contains("\"a\""));
    }

    @Test
    @DisplayName("blank response — confidence 0.0")
    void blank_zero() {
        assertEquals(0.0, ConfidenceEvaluator.evaluate("structured_output", "  ", null, null).confidence(), 0.001);
    }

    // ─── heuristic: config-driven + language-agnostic ────────────────

    @Test
    @DisplayName("config-driven phrases — non-English hedging is detected")
    void heuristic_customPhrases() {
        var cfg = new HeuristicConfig();
        cfg.setLowConfidencePhrases(List.of("ich bin mir nicht sicher"));
        cfg.setHedgingScore(0.35);

        var r = ConfidenceEvaluator.evaluateHeuristic("Ich bin mir nicht sicher über dieses Thema hier.", cfg);
        assertEquals(0.35, r.confidence(), 0.001);
    }

    @Test
    @DisplayName("config-driven default score — confident non-English answer uses configured default")
    void heuristic_customDefault() {
        var cfg = new HeuristicConfig();
        cfg.setLowConfidencePhrases(List.of("ich bin mir nicht sicher"));
        cfg.setDefaultScore(0.7);

        // No configured/English phrase matches → language-agnostic default.
        var r = ConfidenceEvaluator.evaluateHeuristic("Das ist eine ausführliche und detaillierte Antwort auf die Frage.", cfg);
        assertEquals(0.7, r.confidence(), 0.001);
    }

    @Test
    @DisplayName("config-driven short threshold + short score")
    void heuristic_customShort() {
        var cfg = new HeuristicConfig();
        cfg.setShortLengthThreshold(100);
        cfg.setShortScore(0.15);

        var r = ConfidenceEvaluator.evaluateHeuristic("This is under one hundred characters so it counts as short.", cfg);
        assertEquals(0.15, r.confidence(), 0.001);
    }

    @Test
    @DisplayName("default English refusal phrase still detected when config omits refusals")
    void heuristic_defaultRefusalStillApplies() {
        var cfg = new HeuristicConfig();
        cfg.setLowConfidencePhrases(List.of("nope-nonsense-token"));
        // refusalPhrases not set → English defaults apply
        var r = ConfidenceEvaluator.evaluateHeuristic("I cannot fulfill this request under any circumstances at all here.", cfg);
        assertEquals(0.2, r.confidence(), 0.001);
    }

    // ─── judge_model parsing ─────────────────────────────────────────

    @Test
    @DisplayName("judge output with surrounding prose — confidence extracted")
    void judge_parsesFromProse() {
        ChatModel judge = mock(ChatModel.class);
        when(judge.chat(anyList())).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("Sure! Here is my rating: {\"confidence\": 0.42} — hope that helps.")).build());

        EvaluationResult r = ConfidenceEvaluator.evaluateWithJudge("some answer", judge, null);
        assertEquals(0.42, r.confidence(), 0.001);
        assertEquals("some answer", r.response(), "judge does not alter the response text");
    }

    @Test
    @DisplayName("null judge — falls back to heuristic")
    void judge_nullFallsBack() {
        EvaluationResult r = ConfidenceEvaluator.evaluateWithJudge("A confident, complete, well-formed answer to the user's question.", null, null);
        assertEquals(0.8, r.confidence(), 0.001);
    }

    // ─── extractFirstBalancedObject ──────────────────────────────────

    @Test
    @DisplayName("balanced-object extraction honors nested braces and string literals")
    void balancedObject_nested() {
        String text = "prefix {\"a\": {\"b\": \"}\"}, \"c\": 1} suffix";
        assertEquals("{\"a\": {\"b\": \"}\"}, \"c\": 1}", ConfidenceEvaluator.extractFirstBalancedObject(text));
        assertNull(ConfidenceEvaluator.extractFirstBalancedObject("no braces here"));
    }
}
