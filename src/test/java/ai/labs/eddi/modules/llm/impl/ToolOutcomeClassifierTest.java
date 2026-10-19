/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.impl.ToolOutcomeClassifier.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolOutcomeClassifierTest {

    @ParameterizedTest(name = "httpCode {0} -> {1}")
    @CsvSource({"200,SUCCESS", "204,SUCCESS", "299,SUCCESS", "408,FAILED_RETRYABLE", "425,FAILED_RETRYABLE", "429,FAILED_RETRYABLE",
            "500,FAILED_RETRYABLE", "503,FAILED_RETRYABLE", "400,FAILED_TERMINAL", "401,FAILED_TERMINAL", "403,FAILED_TERMINAL",
            "404,FAILED_TERMINAL", "422,FAILED_TERMINAL", "302,FAILED_TERMINAL"})
    void httpCodes(int code, Outcome expected) {
        assertEquals(expected, ToolOutcomeClassifier.classify("{\"httpCode\":" + code + ",\"body\":\"x\"}"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"Error: Execution timed out after 5000ms for tool: x|FAILED_RETRYABLE",
            "Error: Rate limit exceeded for tool: x|FAILED_RETRYABLE",
            "Error: tool 'x' failed with an internal error (reference ab12). Do not retry it with the same arguments.|FAILED_RETRYABLE",
            "Error: invalid arguments for tool 'x': bad|FAILED_TERMINAL", "Error: Tool 'x' not found|FAILED_TERMINAL",
            "Error: Budget exceeded for conversation c|NOT_EXECUTED",
            "Error: the turn deadline has passed; tool 'x' was not run|NOT_EXECUTED",
            "Error calling A2A agent: the request could not be completed.|FAILED_RETRYABLE",
            "{\"error\": \"Connection refused\"}|FAILED_RETRYABLE", "{\"status\":\"NOT_EXECUTED\",\"reason\":\"capped\"}|NOT_EXECUTED",
            "{\"status\":\"DENIED\",\"reason\":\"no\"}|NOT_EXECUTED", "plain text answer|SUCCESS",
            "Error handling in Java is covered here|SUCCESS", "{\"temperature\": 21}|SUCCESS", "{not json|SUCCESS", "[1,2,3]|SUCCESS"})
    void shapes(String raw, Outcome expected) {
        assertEquals(expected, ToolOutcomeClassifier.classify(raw));
    }

    @Test
    void nullAndBlankAreSuccess() {
        assertEquals(Outcome.SUCCESS, ToolOutcomeClassifier.classify(null));
        assertEquals(Outcome.SUCCESS, ToolOutcomeClassifier.classify("  "));
    }

    @Test
    void oversizedPayloadIsNotParsed() {
        String big = "{\"error\":\"" + "x".repeat(ToolOutcomeClassifier.MAX_PARSED_CHARS) + "\"}";
        assertEquals(Outcome.SUCCESS, ToolOutcomeClassifier.classify(big));
    }

    @Test
    void describesFailures() {
        assertEquals("HTTP 403", ToolOutcomeClassifier.describeFailure("{\"httpCode\":403,\"body\":\"x\"}"));
        assertEquals("timed out", ToolOutcomeClassifier.describeFailure("Error: Execution timed out after 1ms for tool: x"));
        assertEquals("an error", ToolOutcomeClassifier.describeFailure("Error: boom"));
    }
}
