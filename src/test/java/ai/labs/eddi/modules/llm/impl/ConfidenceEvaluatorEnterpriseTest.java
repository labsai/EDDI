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
    @DisplayName("fully configured heuristic — every custom score path applied")
    void heuristic_fullyConfigured() {
        var cfg = new HeuristicConfig();
        cfg.setShortLengthThreshold(10);
        cfg.setShortScore(0.11);
        cfg.setRefusalScore(0.22);
        cfg.setHedgingScore(0.33);
        cfg.setDefaultScore(0.77);
        cfg.setRefusalPhrases(List.of("refuse-token"));
        cfg.setLowConfidencePhrases(List.of("hedge-token"));

        assertEquals(0.11, ConfidenceEvaluator.evaluateHeuristic("short", cfg).confidence(), 0.001);
        assertEquals(0.22, ConfidenceEvaluator.evaluateHeuristic("this contains refuse-token somewhere in here", cfg).confidence(), 0.001);
        assertEquals(0.33, ConfidenceEvaluator.evaluateHeuristic("this contains hedge-token somewhere in here", cfg).confidence(), 0.001);
        assertEquals(0.77, ConfidenceEvaluator.evaluateHeuristic("a perfectly ordinary answer with no flagged tokens present at all", cfg)
                .confidence(), 0.001);
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

    // ─── additional edge cases ───────────────────────────────────────

    @Test
    @DisplayName("wrapper object with confidence but no response field — returns the object text")
    void wrapper_noResponseField() {
        var r = ConfidenceEvaluator.evaluateStructuredOutput("{\"confidence\": 0.4}", null);
        assertEquals(0.4, r.confidence(), 0.001);
        assertTrue(r.response().contains("confidence"));
    }

    @Test
    @DisplayName("object-shaped but malformed JSON — regex fallback extracts confidence and response")
    void objectShaped_malformed_regexFallback() {
        // Missing comma → Jackson rejects → regex fallback within the object shape.
        var r = ConfidenceEvaluator.evaluateStructuredOutput("{\"confidence\": 0.55 \"response\": \"hello\"}", null);
        assertEquals(0.55, r.confidence(), 0.001);
        assertEquals("hello", r.response());
    }

    @Test
    @DisplayName("code fence without a closing fence — still unwrapped")
    void fence_withoutClosing() {
        var r = ConfidenceEvaluator.evaluateStructuredOutput("```json\n{\"response\":\"x\",\"confidence\":0.6}", null);
        assertEquals(0.6, r.confidence(), 0.001);
        assertEquals("x", r.response());
    }

    @Test
    @DisplayName("escaped characters in the response field are unescaped")
    void wrapper_unescapes() {
        var r = ConfidenceEvaluator.evaluateStructuredOutput("{\"response\": \"line1\\nline2 \\\"q\\\"\", \"confidence\": 0.5}", null);
        assertEquals(0.5, r.confidence(), 0.001);
        assertTrue(r.response().contains("\n"));
        assertTrue(r.response().contains("\"q\""));
    }

    @Test
    @DisplayName("heuristic — null/blank returns 0.0")
    void heuristic_blank() {
        assertEquals(0.0, ConfidenceEvaluator.evaluateHeuristic(null).confidence(), 0.001);
        assertEquals(0.0, ConfidenceEvaluator.evaluateHeuristic("   ").confidence(), 0.001);
    }

    @Test
    @DisplayName("evaluate — 'none' strategy always returns 1.0")
    void evaluate_none() {
        assertEquals(1.0, ConfidenceEvaluator.evaluate("none", "anything at all", null).confidence(), 0.001);
    }

    @Test
    @DisplayName("judge output without any JSON confidence — falls back to heuristic")
    void judge_noConfidence_fallsBack() {
        ChatModel judge = mock(ChatModel.class);
        when(judge.chat(anyList())).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("I think it's pretty good honestly.")).build());
        EvaluationResult r = ConfidenceEvaluator.evaluateWithJudge("A confident, complete answer to the user's question here.", judge, null);
        assertEquals(0.8, r.confidence(), 0.001);
    }

    @Test
    @DisplayName("judge that throws — falls back to heuristic")
    void judge_throws_fallsBack() {
        ChatModel judge = mock(ChatModel.class);
        when(judge.chat(anyList())).thenThrow(new RuntimeException("judge down"));
        EvaluationResult r = ConfidenceEvaluator.evaluateWithJudge("A confident, complete answer to the user's question here.", judge, null);
        assertEquals(0.8, r.confidence(), 0.001);
    }

    @Test
    @DisplayName("buildConfidenceInstruction mentions response and confidence")
    void buildInstruction() {
        String s = ConfidenceEvaluator.buildConfidenceInstruction();
        assertTrue(s.contains("response") && s.contains("confidence"));
    }

    @Test
    @DisplayName("heuristic — out-of-range configured scores are clamped to [0,1]")
    void heuristic_clampsConfiguredScores() {
        var high = new HeuristicConfig();
        high.setDefaultScore(5.0);
        assertEquals(1.0, ConfidenceEvaluator.evaluateHeuristic("a decent, ordinary answer with no flags at all", high).confidence(), 0.001);

        var low = new HeuristicConfig();
        low.setShortLengthThreshold(1000);
        low.setShortScore(-3.0);
        assertEquals(0.0, ConfidenceEvaluator.evaluateHeuristic("short-ish", low).confidence(), 0.001);
    }

    @Test
    @DisplayName("structured_output regex fallback — escaped backslash is unescaped correctly (\\\\ -> \\)")
    void structuredOutput_unescapesBackslash() {
        // Missing comma → Jackson rejects → regex fallback uses the single-pass
        // unescaper.
        var r = ConfidenceEvaluator.evaluateStructuredOutput("{\"confidence\": 0.5 \"response\": \"c:\\\\path\"}", null);
        assertEquals(0.5, r.confidence(), 0.001);
        assertEquals("c:\\path", r.response());
        assertFalse(r.response().contains("\n"));
    }

    @Test
    @DisplayName("structured_output regex fallback — common escapes unescaped, unknown escape preserved")
    void structuredOutput_unescapesCommon() {
        // JSON text: {"confidence": 0.4 "response": "a\nb\tc\"d\re\/f\x"} (malformed →
        // regex path)
        var r = ConfidenceEvaluator.evaluateStructuredOutput("{\"confidence\": 0.4 \"response\": \"a\\nb\\tc\\\"d\\re\\/f\\x\"}", null);
        assertEquals(0.4, r.confidence(), 0.001);
        String resp = r.response();
        assertTrue(resp.contains("\n"), "newline");
        assertTrue(resp.contains("\t"), "tab");
        assertTrue(resp.contains("\""), "quote");
        assertTrue(resp.contains("\r"), "carriage return");
        assertTrue(resp.contains("/"), "slash");
        assertTrue(resp.contains("\\x"), "unknown escape preserved");
    }
}
