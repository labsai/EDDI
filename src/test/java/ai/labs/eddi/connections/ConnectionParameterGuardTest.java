/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The value of this guard is entirely in what it says when it refuses. Sending
 * the reference on as literal text produces a 401 from the provider that names
 * nothing and points nowhere, so the tests assert the wording — which parameter
 * is at fault, and which form to use instead — not merely that something was
 * thrown.
 */
class ConnectionParameterGuardTest {

    @Test
    @DisplayName("a provider that supplied no parameters at all has nothing to refuse")
    void acceptsNullMap() {
        assertDoesNotThrow(() -> ConnectionParameterGuard.rejectConnectionReferences(null));
    }

    @Test
    @DisplayName("an empty parameter map has nothing to refuse")
    void acceptsEmptyMap() {
        Map<String, Object> parameters = Map.of();
        assertDoesNotThrow(() -> ConnectionParameterGuard.rejectConnectionReferences(parameters));
    }

    @Test
    @DisplayName("ordinary parameter values pass through untouched")
    void acceptsPlainValues() {
        Map<String, Object> parameters = Map.of("modelName", "claude-opus-4", "temperature", "0.2", "baseUrl", "https://api.example.com");
        assertDoesNotThrow(() -> ConnectionParameterGuard.rejectConnectionReferences(parameters));
    }

    @Test
    @DisplayName("a vault reference is the supported form and is left alone")
    void acceptsVaultReference() {
        Map<String, Object> parameters = Map.of("apiKey", "${vault:anthropic-api-key}", "orgId", "${vault:anthropic-org}");
        assertDoesNotThrow(() -> ConnectionParameterGuard.rejectConnectionReferences(parameters));
    }

    @Test
    @DisplayName("values that are not strings cannot carry a reference and are skipped")
    void skipsNonStringValues() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("maxTokens", 4096);
        parameters.put("temperature", 0.2d);
        parameters.put("logRequests", Boolean.TRUE);
        parameters.put("stopSequences", List.of("</done>"));
        parameters.put("seed", null);

        assertDoesNotThrow(() -> ConnectionParameterGuard.rejectConnectionReferences(parameters));
    }

    @Test
    @DisplayName("a reference nested inside a non-string value is not inspected")
    void doesNotInspectNestedValues() {
        // Provider parameter maps are flat, so only the top level is scanned. Recorded
        // here so that a later move to structured parameters is a deliberate decision
        // rather than a silent hole.
        Map<String, Object> parameters = Map.of("headers", List.of("${connection:jira}"));
        assertDoesNotThrow(() -> ConnectionParameterGuard.rejectConnectionReferences(parameters));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "${connection:jira}",
            "${connection:acme/jira}",
            "Bearer ${connection:jira}",
            "${connection:jira} ${connection:confluence}"})
    @DisplayName("any parameter value carrying a connection reference is refused")
    void refusesConnectionReference(String value) {
        Map<String, Object> parameters = Map.of("apiKey", value);

        var thrown = assertThrows(IllegalArgumentException.class, () -> ConnectionParameterGuard.rejectConnectionReferences(parameters));
        assertTrue(thrown.getMessage().contains("Parameter 'apiKey'"), thrown.getMessage());
    }

    @Test
    @DisplayName("the refusal explains why a header cannot stand in for a bare credential")
    void refusalExplainsTheMismatch() {
        Map<String, Object> parameters = Map.of("apiKey", "${connection:anthropic}");

        var thrown = assertThrows(IllegalArgumentException.class, () -> ConnectionParameterGuard.rejectConnectionReferences(parameters));
        assertTrue(thrown.getMessage().contains("bare credential"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("${vault:"), thrown.getMessage());
    }

    @Test
    @DisplayName("the refusal names the offending parameter rather than the first one scanned")
    void refusalNamesTheOffendingParameter() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("modelName", "claude-opus-4");
        parameters.put("maxTokens", 4096);
        parameters.put("apiKey", "${connection:anthropic}");

        var thrown = assertThrows(IllegalArgumentException.class, () -> ConnectionParameterGuard.rejectConnectionReferences(parameters));
        assertTrue(thrown.getMessage().contains("Parameter 'apiKey'"), thrown.getMessage());
        assertFalse(thrown.getMessage().contains("modelName"), thrown.getMessage());
    }
}
