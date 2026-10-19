/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Security review 2026-10-17, L16: a template expression spliced into a JSON
 * string literal of a request body is not escaped; deployment warns about it.
 */
@DisplayName("ApiCallsTask — expressions spliced into JSON string literals")
class ApiCallsTaskJsonBodyWarningTest {

    @Test
    @DisplayName("an expression inside a string literal is found")
    void findsQuotedExpressions() {
        assertEquals(List.of("{memory.current.input}"),
                ApiCallsTask.expressionsInsideJsonStrings("{\"text\": \"{memory.current.input}\", \"n\": 1}"));
        assertEquals(List.of("{properties.name}"),
                ApiCallsTask.expressionsInsideJsonStrings("{\"greeting\": \"Hello {properties.name}!\"}"));
    }

    @Test
    @DisplayName("json:serialize outside quotes, vault references and escaped quotes are not flagged")
    void safeShapesAreNotFlagged() {
        assertEquals(List.of(), ApiCallsTask.expressionsInsideJsonStrings("{\"text\": {json:serialize(memory.current.input)}}"));
        assertEquals(List.of(), ApiCallsTask.expressionsInsideJsonStrings("{\"key\": \"${vault:api-key}\"}"));
        assertEquals(List.of(), ApiCallsTask.expressionsInsideJsonStrings("{\"q\": \"a \\\"quoted\\\" word\", \"n\": {properties.n}}"));
        assertEquals(List.of(), ApiCallsTask.expressionsInsideJsonStrings("{\"json\": \"{\\\"nested\\\": 1}\"}"));
    }
}
