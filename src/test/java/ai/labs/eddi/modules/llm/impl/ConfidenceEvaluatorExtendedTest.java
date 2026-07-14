/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Extended tests for {@link ConfidenceEvaluator} — covers the judge_model
 * strategy with actual model mocking, stripJsonWrapper edge cases, and
 * structured output parsing fallbacks.
 */
class ConfidenceEvaluatorExtendedTest {

    @Nested
    @DisplayName("evaluateWithJudge — with model")
    class JudgeModel {

        @Test
        @DisplayName("should parse confidence from judge model response")
        void parsesJudgeConfidence() {
            ChatModel judgeModel = mock(ChatModel.class);
            var aiMessage = AiMessage.from("{\"confidence\": 0.85}");
            var chatResponse = ChatResponse.builder().aiMessage(aiMessage).build();
            when(judgeModel.chat(any(List.class))).thenReturn(chatResponse);

            var result = ConfidenceEvaluator.evaluateWithJudge(
                    "Paris is the capital of France", judgeModel);

            assertEquals(0.85, result.confidence(), 0.01);
            assertEquals("Paris is the capital of France", result.response());
        }

        @Test
        @DisplayName("should clamp judge confidence to 0.0-1.0")
        void clampsJudgeConfidence() {
            ChatModel judgeModel = mock(ChatModel.class);
            var aiMessage = AiMessage.from("{\"confidence\": 1.5}");
            var chatResponse = ChatResponse.builder().aiMessage(aiMessage).build();
            when(judgeModel.chat(any(List.class))).thenReturn(chatResponse);

            var result = ConfidenceEvaluator.evaluateWithJudge(
                    "Some response", judgeModel);

            assertEquals(1.0, result.confidence(), 0.01);
        }

        @Test
        @DisplayName("should fall back to heuristic when judge model returns no JSON")
        void fallsBackOnNoJson() {
            ChatModel judgeModel = mock(ChatModel.class);
            var aiMessage = AiMessage.from("I think it's pretty good");
            var chatResponse = ChatResponse.builder().aiMessage(aiMessage).build();
            when(judgeModel.chat(any(List.class))).thenReturn(chatResponse);

            var result = ConfidenceEvaluator.evaluateWithJudge(
                    "A reasonably long response that should get decent heuristic confidence", judgeModel);

            // Falls back to heuristic — long response with no hedging → 0.8
            assertEquals(0.8, result.confidence(), 0.01);
        }

        @Test
        @DisplayName("should fall back to heuristic when judge model throws")
        void fallsBackOnException() {
            ChatModel judgeModel = mock(ChatModel.class);
            when(judgeModel.chat(any(List.class))).thenThrow(new RuntimeException("API error"));

            var result = ConfidenceEvaluator.evaluateWithJudge(
                    "A reasonably long response that should get decent heuristic confidence", judgeModel);

            // Falls back to heuristic
            assertEquals(0.8, result.confidence(), 0.01);
        }

        @Test
        @DisplayName("should fall back to heuristic when judge model returns null text")
        void fallsBackOnNullText() {
            ChatModel judgeModel = mock(ChatModel.class);
            var aiMessage = mock(AiMessage.class);
            when(aiMessage.text()).thenReturn(null);
            var chatResponse = ChatResponse.builder().aiMessage(aiMessage).build();
            when(judgeModel.chat(any(List.class))).thenReturn(chatResponse);

            var result = ConfidenceEvaluator.evaluateWithJudge(
                    "A reasonably long response that should get decent heuristic confidence", judgeModel);

            assertEquals(0.8, result.confidence(), 0.01);
        }

        @Test
        @DisplayName("should keep braces balanced when a judge string contains an escaped quote")
        void handlesEscapedQuoteInBalancedObject() {
            // The first balanced {...} object contains a backslash-escaped quote inside a
            // string; the '}' that immediately follows it is INSIDE the string and must not
            // prematurely close the object.
            String judgeText = "{\"note\": \"a\\\"}b\", \"confidence\": 0.3}";

            // Direct assertion on the helper: the whole object is returned, not truncated
            // at
            // the in-string brace that follows the escaped quote.
            assertEquals(judgeText, ConfidenceEvaluator.extractFirstBalancedObject(judgeText));

            // And driven through the judge path, the confidence still parses correctly.
            ChatModel judgeModel = mock(ChatModel.class);
            var aiMessage = AiMessage.from(judgeText);
            var chatResponse = ChatResponse.builder().aiMessage(aiMessage).build();
            when(judgeModel.chat(any(List.class))).thenReturn(chatResponse);

            var result = ConfidenceEvaluator.evaluateWithJudge("Some response", judgeModel);

            assertEquals(0.3, result.confidence(), 0.01);
        }

        @Test
        @DisplayName("should recover confidence via regex when the first balanced object is invalid JSON")
        void regexFallbackWhenFirstBalancedObjectIsInvalidJson() {
            // The first balanced {...} object is present but not valid JSON, so the inner
            // readTree throws and the full-text confidence regex safety net recovers the
            // score.
            ChatModel judgeModel = mock(ChatModel.class);
            var aiMessage = AiMessage.from("{bad json} {\"confidence\": 0.3}");
            var chatResponse = ChatResponse.builder().aiMessage(aiMessage).build();
            when(judgeModel.chat(any(List.class))).thenReturn(chatResponse);

            var result = ConfidenceEvaluator.evaluateWithJudge("Some response", judgeModel);

            assertEquals(0.3, result.confidence(), 0.01);
        }
    }

    @Nested
    @DisplayName("evaluateStructuredOutput — edge cases")
    class StructuredOutput {

        @Test
        @DisplayName("should strip JSON wrapper and return response content")
        void stripsJsonWrapper() {
            String json = "{\"response\": \"The answer is 42\", \"confidence\": 0.9}";
            var result = ConfidenceEvaluator.evaluateStructuredOutput(json);

            assertEquals("The answer is 42", result.response());
            assertEquals(0.9, result.confidence(), 0.01);
        }

        @Test
        @DisplayName("should handle confidence without response field - uses stripJsonWrapper")
        void confidenceWithoutResponse() {
            // Has confidence but no response field — stripJsonWrapper tries to find
            // response
            String json = "{\"confidence\": 0.7, \"text\": \"Some data\"}";
            var result = ConfidenceEvaluator.evaluateStructuredOutput(json);

            assertEquals(0.7, result.confidence(), 0.01);
            // Can't find response field, stripJsonWrapper falls through
        }

        @Test
        @DisplayName("should handle malformed JSON gracefully — falls back to heuristic")
        void malformedJson() {
            String text = "Not JSON at all, just a really long answer that should get decent confidence";
            var result = ConfidenceEvaluator.evaluateStructuredOutput(text);

            // No confidence found → heuristic fallback → long text → 0.8
            assertEquals(0.8, result.confidence(), 0.01);
        }

        @Test
        @DisplayName("should handle escaped newlines and quotes in response")
        void handlesEscapedChars() {
            String json = "{\"response\": \"Line 1\\nLine 2 with \\\"quotes\\\"\", \"confidence\": 0.95}";
            var result = ConfidenceEvaluator.evaluateStructuredOutput(json);

            assertTrue(result.response().contains("\n"));
            assertTrue(result.response().contains("\""));
            assertEquals(0.95, result.confidence(), 0.01);
        }

        @Test
        @DisplayName("should return object text unchanged via stripJsonWrapper when confidence matches but response field is absent")
        void stripJsonWrapperFallbackOnMalformedObject() {
            // Object-shaped but malformed JSON (missing comma) → Jackson parse returns
            // null.
            // The confidence regex still extracts 0.5, but there is no "response" field, so
            // evaluateStructuredOutput falls back to stripJsonWrapper — which, finding no
            // response field either, returns the object text unchanged.
            String malformed = "{\"confidence\": 0.5 \"foo\": \"bar\"}";
            var result = ConfidenceEvaluator.evaluateStructuredOutput(malformed, null);

            assertEquals(0.5, result.confidence(), 0.01);
            assertEquals(malformed, result.response());
        }
    }

    @Nested
    @DisplayName("evaluate() dispatch — edge cases")
    class Dispatch {

        @Test
        @DisplayName("should handle null strategy as default (structured_output)")
        void nullStrategy() {
            var result = ConfidenceEvaluator.evaluate(null,
                    "{\"response\": \"test response\", \"confidence\": 0.5}", null);

            assertEquals(0.5, result.confidence(), 0.01);
        }

        @Test
        @DisplayName("should handle judge_model with valid model")
        void judgeModelWithModel() {
            ChatModel judgeModel = mock(ChatModel.class);
            var aiMessage = AiMessage.from("{\"confidence\": 0.75}");
            var chatResponse = ChatResponse.builder().aiMessage(aiMessage).build();
            when(judgeModel.chat(any(List.class))).thenReturn(chatResponse);

            var result = ConfidenceEvaluator.evaluate("judge_model",
                    "Response text", judgeModel);

            assertEquals(0.75, result.confidence(), 0.01);
        }

        @Test
        @DisplayName("should handle unknown strategy as structured_output")
        void unknownStrategy() {
            var result = ConfidenceEvaluator.evaluate("custom_unknown",
                    "{\"response\": \"hello\", \"confidence\": 0.6}", null);

            assertEquals(0.6, result.confidence(), 0.01);
        }
    }
}
