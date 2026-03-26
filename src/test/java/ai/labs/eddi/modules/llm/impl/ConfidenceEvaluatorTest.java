package ai.labs.eddi.modules.llm.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ConfidenceEvaluator} — all 4 confidence evaluation
 * strategies.
 */
class ConfidenceEvaluatorTest {

    // ==================== Structured Output Strategy ====================

    @Nested
    @DisplayName("structured_output strategy")
    class StructuredOutputTests {

        @Test
        @DisplayName("should parse valid JSON with response and confidence")
        void testValidStructuredOutput() {
            String response = "{\"response\": \"The capital of France is Paris.\", \"confidence\": 0.95}";
            var result = ConfidenceEvaluator.evaluateStructuredOutput(response);

            assertEquals("The capital of France is Paris.", result.response());
            assertEquals(0.95, result.confidence(), 0.001);
        }

        @Test
        @DisplayName("should handle confidence without response field")
        void testConfidenceOnly() {
            String response = "{\"confidence\": 0.75, \"text\": \"some other field\"}";
            var result = ConfidenceEvaluator.evaluateStructuredOutput(response);

            assertEquals(0.75, result.confidence(), 0.001);
        }

        @Test
        @DisplayName("should clamp confidence above 1.0")
        void testClampAbove() {
            String response = "{\"response\": \"Sure!\", \"confidence\": 1.5}";
            var result = ConfidenceEvaluator.evaluateStructuredOutput(response);

            assertEquals(1.0, result.confidence(), 0.001);
        }

        @Test
        @DisplayName("should clamp negative confidence to 0.0")
        void testClampBelow() {
            String response = "{\"response\": \"Hmm\", \"confidence\": -0.5}";
            var result = ConfidenceEvaluator.evaluateStructuredOutput(response);

            assertEquals(0.0, result.confidence(), 0.001);
        }

        @Test
        @DisplayName("should fall back to heuristic when no JSON found")
        void testFallbackToHeuristic() {
            String response = "This is a plain text response without any JSON formatting.";
            var result = ConfidenceEvaluator.evaluateStructuredOutput(response);

            // Should fall back to heuristic — decent length, no hedging
            assertEquals(0.8, result.confidence(), 0.001);
            assertEquals(response, result.response());
        }

        @Test
        @DisplayName("should handle escaped characters in response field")
        void testEscapedCharacters() {
            String response = "{\"response\": \"She said \\\"hello\\\" and\\nleft.\", \"confidence\": 0.9}";
            var result = ConfidenceEvaluator.evaluateStructuredOutput(response);

            assertEquals("She said \"hello\" and\nleft.", result.response());
            assertEquals(0.9, result.confidence(), 0.001);
        }
    }

    // ==================== Heuristic Strategy ====================

    @Nested
    @DisplayName("heuristic strategy")
    class HeuristicTests {

        @Test
        @DisplayName("should return 0.0 for null response")
        void testNull() {
            var result = ConfidenceEvaluator.evaluateHeuristic(null);
            assertEquals(0.0, result.confidence(), 0.001);
        }

        @Test
        @DisplayName("should return 0.0 for empty response")
        void testEmpty() {
            var result = ConfidenceEvaluator.evaluateHeuristic("");
            assertEquals(0.0, result.confidence(), 0.001);
        }

        @Test
        @DisplayName("should return 0.0 for blank response")
        void testBlank() {
            var result = ConfidenceEvaluator.evaluateHeuristic("   ");
            assertEquals(0.0, result.confidence(), 0.001);
        }

        @Test
        @DisplayName("should return 0.3 for very short response")
        void testShort() {
            var result = ConfidenceEvaluator.evaluateHeuristic("I think so");
            assertEquals(0.3, result.confidence(), 0.001);
        }

        @Test
        @DisplayName("should return 0.2 for refusal")
        void testRefusal() {
            var result = ConfidenceEvaluator.evaluateHeuristic("I'm sorry, but I'm not able to help with that specific request.");
            assertEquals(0.2, result.confidence(), 0.001);
        }

        @Test
        @DisplayName("should return 0.4 for hedging language")
        void testHedging() {
            var result = ConfidenceEvaluator.evaluateHeuristic("I'm not sure about this, but I think the answer might be 42.");
            assertEquals(0.4, result.confidence(), 0.001);
        }

        @Test
        @DisplayName("should return 0.8 for confident response")
        void testConfident() {
            var result = ConfidenceEvaluator.evaluateHeuristic("The capital of France is Paris. It has been the capital since the 10th century.");
            assertEquals(0.8, result.confidence(), 0.001);
        }

        @Test
        @DisplayName("should detect 'as an ai' hedging")
        void testAsAnAI() {
            var result = ConfidenceEvaluator.evaluateHeuristic("As an AI language model, I cannot provide medical advice on this topic.");
            assertEquals(0.4, result.confidence(), 0.001);
        }
    }

    // ==================== None Strategy ====================

    @Nested
    @DisplayName("none strategy")
    class NoneTests {

        @Test
        @DisplayName("should always return 1.0")
        void testNone() {
            var result = ConfidenceEvaluator.evaluate("none", "anything", null);
            assertEquals(1.0, result.confidence(), 0.001);
            assertEquals("anything", result.response());
        }
    }

    // ==================== evaluate() dispatch ====================

    @Nested
    @DisplayName("evaluate() strategy dispatch")
    class DispatchTests {

        @Test
        @DisplayName("should dispatch to structured_output by default")
        void testDefaultStrategy() {
            String response = "{\"response\": \"Test\", \"confidence\": 0.88}";
            var result = ConfidenceEvaluator.evaluate(null, response, null);
            assertEquals(0.88, result.confidence(), 0.001);
        }

        @Test
        @DisplayName("should dispatch to heuristic")
        void testHeuristicStrategy() {
            var result = ConfidenceEvaluator.evaluate("heuristic", "A confident answer that is definitely correct and well-reasoned.", null);
            assertEquals(0.8, result.confidence(), 0.001);
        }

        @Test
        @DisplayName("should return 0.0 for null response regardless of strategy")
        void testNullResponse() {
            var result = ConfidenceEvaluator.evaluate("structured_output", null, null);
            assertEquals(0.0, result.confidence(), 0.001);
        }

        @Test
        @DisplayName("should return 0.0 for blank response regardless of strategy")
        void testBlankResponse() {
            var result = ConfidenceEvaluator.evaluate("heuristic", "   ", null);
            assertEquals(0.0, result.confidence(), 0.001);
        }

        @Test
        @DisplayName("judge_model without model should fall back to heuristic")
        void testJudgeWithoutModel() {
            var result = ConfidenceEvaluator.evaluate("judge_model", "A confident answer that is long enough to pass heuristic checks.", null);
            // Should fall back to heuristic since no judge model provided
            assertEquals(0.8, result.confidence(), 0.001);
        }
    }

    // ==================== buildConfidenceInstruction ====================

    @Test
    @DisplayName("confidence instruction should contain JSON format guidance")
    void testConfidenceInstruction() {
        String instruction = ConfidenceEvaluator.buildConfidenceInstruction();
        assertTrue(instruction.contains("confidence"));
        assertTrue(instruction.contains("response"));
        assertTrue(instruction.contains("JSON"));
    }
}
