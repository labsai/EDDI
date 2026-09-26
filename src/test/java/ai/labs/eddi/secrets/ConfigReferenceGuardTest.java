/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.Property.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ConfigReferenceGuard")
class ConfigReferenceGuardTest {

    private static final Map<String, Object> DATA = Map.of("conversationInfo", Map.of("agentId", "agent1", "conversationId", "conv1"),
            "properties",
            Map.of("apiKey", "${vault:agent1.conv1.apiKey}", "typed", "${vault:agent1.conv1.typed-by-user}", "foreign", "${vault:other.apiKey}"));

    /**
     * A property as {@code SecretPropertyVault.vault} stores it: the vault
     * reference, conversation scope, and the provenance marker that says this
     * process vaulted it.
     */
    private static Property autoVaulted(String name, String reference) {
        var property = new Property(name, reference, Scope.conversation);
        property.setAutoVaulted(Boolean.TRUE);
        return property;
    }

    /**
     * The same property with no marker — what a template writes from conversation
     * data, and what a conversation document written before the marker existed
     * carries. The two are indistinguishable, which is why both are refused.
     */
    private static Property unmarked(String name, String value) {
        return new Property(name, value, Scope.conversation);
    }

    private static Map<String, Property> properties(Property... properties) {
        var map = new LinkedHashMap<String, Property>();
        for (Property property : properties) {
            map.put(property.getName(), property);
        }
        return map;
    }

    private static final Map<String, Property> VAULTED = properties(autoVaulted("apiKey", "${vault:agent1.conv1.apiKey}"),
            unmarked("typed", "${vault:agent1.conv1.typed-by-user}"), unmarked("foreign", "${vault:other.apiKey}"));

    @Test
    @DisplayName("a reference the configuration wrote is allowed")
    void configured() {
        assertDoesNotThrow(
                () -> ConfigReferenceGuard.requireConfiguredReferences("Bearer ${vault:k}", "Bearer ${vault:k}", "header", DATA, VAULTED));
    }

    @Test
    @DisplayName("a reference that came from data is refused, naming the field")
    void injected() {
        var e = assertThrows(IllegalArgumentException.class, () -> ConfigReferenceGuard.requireConfiguredReferences(
                "{\"q\":\"{memory.current.input}\"}", "{\"q\":\"${vault:other-agents-key}\"}", "a request body", DATA, VAULTED));
        assertTrue(e.getMessage().startsWith("a request body contains the reference ${vault:other-agents-key}"), e.getMessage());
    }

    @Test
    @DisplayName("every credential namespace is guarded: vault, eddivault, connection, caller")
    void allCredentialNamespaces() {
        for (String reference : new String[]{"${vault:k}", "${eddivault:k}", "${connection:jira}", "${caller:token}"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> ConfigReferenceGuard.requireConfiguredReferences("{x}", reference, "field", DATA, VAULTED), reference);
        }
    }

    @Test
    @DisplayName("global variables are not guarded")
    void varsNotGuarded() {
        assertDoesNotThrow(() -> ConfigReferenceGuard.requireConfiguredReferences("{x}", "${vars:host}", "field", DATA, VAULTED));
    }

    @Test
    @DisplayName("an allowed reference does not license a different one in the same field")
    void configuredDoesNotLicenseOthers() {
        assertThrows(IllegalArgumentException.class, () -> ConfigReferenceGuard.requireConfiguredReferences("Bearer ${vault:k} {x}",
                "Bearer ${vault:k} ${vault:other}", "header", DATA, VAULTED));
    }

    @Test
    @DisplayName("the agent's own auto-vaulted property, read through the property the template names, is allowed")
    void autoVaultProperty() {
        assertDoesNotThrow(() -> ConfigReferenceGuard.requireConfiguredReferences("Bearer {properties.apiKey}", "Bearer ${vault:agent1.conv1.apiKey}",
                "header", DATA, VAULTED));
    }

    @Test
    @DisplayName("a property whose value is not its own auto-vault reference is refused")
    void propertyNotAutoVault() {
        assertThrows(IllegalArgumentException.class,
                () -> ConfigReferenceGuard.requireConfiguredReferences("{properties.foreign}", "${vault:other.apiKey}", "header", DATA, VAULTED));
        assertThrows(IllegalArgumentException.class, () -> ConfigReferenceGuard.requireConfiguredReferences("{properties.typed}",
                "${vault:agent1.conv1.typed-by-user}", "header", DATA, VAULTED));
    }

    @Test
    @DisplayName("an auto-vault reference is allowed only in a field whose template names that property")
    void autoVaultOnlyWhereNamed() {
        assertThrows(IllegalArgumentException.class, () -> ConfigReferenceGuard.requireConfiguredReferences("{\"q\":\"{memory.current.input}\"}",
                "{\"q\":\"${vault:agent1.conv1.apiKey}\"}", "a request body", DATA, VAULTED));
    }

    @Test
    @DisplayName("the auto-vault exception does not reach another tenant's secret of the same key name")
    void autoVaultTenantIsPinned() {
        // The property is genuinely auto-vaulted and holds the right key for the right
        // agent, but under a tenant this conversation is not in. Accepting it reads a
        // foreign tenant's secret, which is what an unpinned <tenant>/ prefix allowed.
        // The marker does not license it: provenance is necessary, not sufficient.
        Map<String, Property> foreignTenant = properties(autoVaulted("apiKey", "${vault:victim-tenant/agent1.conv1.apiKey}"));
        assertThrows(IllegalArgumentException.class, () -> ConfigReferenceGuard.requireConfiguredReferences("Bearer {properties.apiKey}",
                "Bearer ${vault:victim-tenant/agent1.conv1.apiKey}", "header", DATA, foreignTenant));
    }

    @Test
    @DisplayName("a client-settable tenantId property no longer qualifies a tenant-prefixed auto-vault reference")
    void tenantPropertyIsIgnored() {
        // The tenant used to come from the tenantId conversation property, which a
        // client can set through a context expression. The vault key no longer reads
        // it, so neither does the guard: only the default-tenant form is this
        // conversation's own reference.
        Map<String, Property> claimedTenant = properties(unmarked("tenantId", "acme"),
                autoVaulted("apiKey", "${vault:acme/agent1.conv1.apiKey}"));
        assertThrows(IllegalArgumentException.class, () -> ConfigReferenceGuard.requireConfiguredReferences("Bearer {properties.apiKey}",
                "Bearer ${vault:acme/agent1.conv1.apiKey}", "header", DATA, claimedTenant));
    }

    @Test
    @DisplayName("another conversation's auto-vault reference of the same agent and property is refused")
    void otherConversationsReferenceIsRefused() {
        // C2: the key used to be <agentId>.<property>, one entry for every user of the
        // agent. A marked property carrying a different conversation's key must not be
        // resolved in this one.
        Map<String, Property> foreign = properties(autoVaulted("apiKey", "${vault:agent1.conv2.apiKey}"));
        assertThrows(IllegalArgumentException.class, () -> ConfigReferenceGuard.requireConfiguredReferences("Bearer {properties.apiKey}",
                "Bearer ${vault:agent1.conv2.apiKey}", "header", DATA, foreign));
    }

    @Test
    @DisplayName("a reference under the old shared per-agent key is refused")
    void legacySharedKeyIsRefused() {
        // Stored before the per-conversation key: that entry holds whichever user's
        // value was written last, so it is not resolved any more.
        Map<String, Property> legacy = properties(autoVaulted("apiKey", "${vault:agent1.apiKey}"));
        var e = assertThrows(IllegalArgumentException.class, () -> ConfigReferenceGuard.requireConfiguredReferences(
                "Bearer {properties.apiKey}", "Bearer ${vault:agent1.apiKey}", "header", DATA, legacy));
        // Review #9: not the injection wording — the operator must not chase a phantom
        // attack.
        assertTrue(e.getMessage().contains("earlier release") && e.getMessage().contains("enter the secret again"), e.getMessage());
        assertFalse(e.getMessage().contains("came from conversation data"), e.getMessage());
    }

    @Test
    @DisplayName("an unmarked property holding the exact auto-vault reference is refused — provenance, not shape")
    void unmarkedPropertyIsRefused() {
        // The whole point of the marker. This value is character-for-character what
        // autoVaultSecret would have written for this property, under this agent and
        // this tenant, so no test of the value itself can tell it apart from the real
        // thing — and the shape test this replaced accepted it. Anything that can write
        // a conversation property can produce it: a valueString of
        // {memory.current.input} and a user who types the reference, a model reply, an
        // API response copied into a property by a post-response instruction.
        Map<String, Property> dataWritten = properties(unmarked("apiKey", "${vault:agent1.conv1.apiKey}"));
        var e = assertThrows(IllegalArgumentException.class, () -> ConfigReferenceGuard.requireConfiguredReferences("Bearer {properties.apiKey}",
                "Bearer ${vault:agent1.conv1.apiKey}", "header", DATA, dataWritten));
        assertTrue(e.getMessage().contains("${vault:agent1.conv1.apiKey}"), e.getMessage());
    }

    @Test
    @DisplayName("an explicitly unvaulted property is refused too")
    void falselyMarkedPropertyIsRefused() {
        // Only TRUE is trusted. FALSE is not "unknown, be lenient" — it is a statement
        // that this value was not vaulted, and it is treated as one.
        var property = unmarked("apiKey", "${vault:agent1.conv1.apiKey}");
        property.setAutoVaulted(Boolean.FALSE);
        assertThrows(IllegalArgumentException.class, () -> ConfigReferenceGuard.requireConfiguredReferences("Bearer {properties.apiKey}",
                "Bearer ${vault:agent1.conv1.apiKey}", "header", DATA, properties(property)));
    }

    @Test
    @DisplayName("no conversation properties at all still guards rather than skips")
    void noConversationProperties() {
        assertThrows(IllegalArgumentException.class, () -> ConfigReferenceGuard.requireConfiguredReferences("Bearer {properties.apiKey}",
                "Bearer ${vault:agent1.conv1.apiKey}", "header", DATA, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> ConfigReferenceGuard.requireConfiguredReferences("Bearer {properties.apiKey}",
                "Bearer ${vault:agent1.conv1.apiKey}", "header", DATA, null));
    }

    @Test
    @DisplayName("a ${vars:} parameter reference must be one the template wrote whole, not text inside another reference")
    void variableReferenceMatchedWhole() {
        assertDoesNotThrow(() -> ConfigReferenceGuard.requireConfiguredParameters(Map.of("modelName", "${vars:model}-{context.suffix}"),
                Map.of("modelName", "${vars:model}-large"), Set.of(), "LLM", DATA, VAULTED));
        var e = assertThrows(IllegalArgumentException.class,
                () -> ConfigReferenceGuard.requireConfiguredParameters(Map.of("modelName", "${vars:outer${vars:model}"),
                        Map.of("modelName", "${vars:model}"), Set.of(), "LLM", DATA, VAULTED));
        assertTrue(e.getMessage().contains("${vars:model}"), e.getMessage());
    }
}
