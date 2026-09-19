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

    @Test
    @DisplayName("the auto-vault exception does not reach another tenant's secret of the same key name")
    void autoVaultTenantIsPinned() {
        // The property holds the right key for the right agent, but under a tenant this
        // conversation is not in. Accepting it reads a foreign tenant's secret, which
        // is
        // what an unpinned <tenant>/ prefix allowed.
        Map<String, Object> foreignTenant = Map.of("conversationInfo", Map.of("agentId", "agent1"), "properties",
                Map.of("apiKey", "${vault:victim-tenant/agent1.apiKey}"));
        assertThrows(IllegalArgumentException.class, () -> ConfigReferenceGuard.requireConfiguredReferences("Bearer {properties.apiKey}",
                "Bearer ${vault:victim-tenant/agent1.apiKey}", "header", foreignTenant));
    }

    @Test
    @DisplayName("a tenant-qualified auto-vault reference is allowed when it is the conversation's own tenant")
    void autoVaultOwnTenant() {
        Map<String, Object> ownTenant = Map.of("conversationInfo", Map.of("agentId", "agent1"), "properties",
                Map.of("tenantId", "acme", "apiKey", "${vault:acme/agent1.apiKey}"));
        assertDoesNotThrow(() -> ConfigReferenceGuard.requireConfiguredReferences("Bearer {properties.apiKey}",
                "Bearer ${vault:acme/agent1.apiKey}", "header", ownTenant));
    }
}
