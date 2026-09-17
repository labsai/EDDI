/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ConfigReferenceGuard")
class ConfigReferenceGuardTest {

    private static final Map<String, Object> DATA = Map.of("conversationInfo", Map.of("agentId", "agent1"),
            "properties", Map.of("apiKey", "${vault:agent1.apiKey}", "typed", "${vault:agent1.typed-by-user}", "foreign", "${vault:other.apiKey}"));

    @Test
    @DisplayName("a reference the configuration wrote is allowed")
    void configured() {
        assertDoesNotThrow(() -> ConfigReferenceGuard.requireConfiguredReferences("Bearer ${vault:k}", "Bearer ${vault:k}", "header", DATA));
    }

    @Test
    @DisplayName("a reference that came from data is refused, naming the field")
    void injected() {
        var e = assertThrows(IllegalArgumentException.class, () -> ConfigReferenceGuard.requireConfiguredReferences(
                "{\"q\":\"{memory.current.input}\"}", "{\"q\":\"${vault:other-agents-key}\"}", "a request body", DATA));
        assertTrue(e.getMessage().startsWith("a request body contains the reference ${vault:other-agents-key}"), e.getMessage());
    }

    @Test
    @DisplayName("every credential namespace is guarded: vault, eddivault, connection, caller")
    void allCredentialNamespaces() {
        for (String reference : new String[]{"${vault:k}", "${eddivault:k}", "${connection:jira}", "${caller:token}"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> ConfigReferenceGuard.requireConfiguredReferences("{x}", reference, "field", DATA), reference);
        }
    }

    @Test
    @DisplayName("global variables are not guarded")
    void varsNotGuarded() {
        assertDoesNotThrow(() -> ConfigReferenceGuard.requireConfiguredReferences("{x}", "${vars:host}", "field", DATA));
    }

    @Test
    @DisplayName("an allowed reference does not license a different one in the same field")
    void configuredDoesNotLicenseOthers() {
        assertThrows(IllegalArgumentException.class, () -> ConfigReferenceGuard.requireConfiguredReferences("Bearer ${vault:k} {x}",
                "Bearer ${vault:k} ${vault:other}", "header", DATA));
    }

    @Test
    @DisplayName("the agent's own auto-vault reference, read through the property the template names, is allowed")
    void autoVaultProperty() {
        assertDoesNotThrow(() -> ConfigReferenceGuard.requireConfiguredReferences("Bearer {properties.apiKey}", "Bearer ${vault:agent1.apiKey}",
                "header", DATA));
    }

    @Test
    @DisplayName("a property whose value is not its own auto-vault reference is refused")
    void propertyNotAutoVault() {
        assertThrows(IllegalArgumentException.class,
                () -> ConfigReferenceGuard.requireConfiguredReferences("{properties.foreign}", "${vault:other.apiKey}", "header", DATA));
        assertThrows(IllegalArgumentException.class,
                () -> ConfigReferenceGuard.requireConfiguredReferences("{properties.typed}", "${vault:agent1.typed-by-user}", "header", DATA));
    }

    @Test
    @DisplayName("an auto-vault reference is allowed only in a field whose template names that property")
    void autoVaultOnlyWhereNamed() {
        assertThrows(IllegalArgumentException.class, () -> ConfigReferenceGuard.requireConfiguredReferences("{\"q\":\"{memory.current.input}\"}",
                "{\"q\":\"${vault:agent1.apiKey}\"}", "a request body", DATA));
    }
}
