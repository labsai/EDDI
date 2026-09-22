/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.Property.Scope;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The per-write, user-attributable naming of auto-vaulted secret slots, and the
 * cleanup that the naming makes necessary.
 */
class AutoVaultedSecretsTest {

    private static final String AGENT = "6ab2d33ae58a8ae71ec8c63b";

    private ISecretProvider provider;
    private AutoVaultedSecrets secrets;

    @BeforeEach
    void setUp() {
        provider = mock(ISecretProvider.class);
        when(provider.isAvailable()).thenReturn(true);
        secrets = new AutoVaultedSecrets(provider);
    }

    private static SecretMetadata metadata(String tenant, String keyName) {
        return new SecretMetadata(tenant, keyName, null, null, null, null, null, List.of("*"));
    }

    private static Property autoVaulted(String reference) {
        var property = new Property("apiKey", reference, Scope.conversation);
        property.setAutoVaulted(Boolean.TRUE);
        return property;
    }

    @Test
    @DisplayName("a slot name is unique per write, and stays within the vault's REST key-name rule")
    void slotNamesAreUniqueAndManageable() {
        Set<String> names = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String name = AutoVaultedSecrets.newSlotName(AGENT, "user-1", "userApiKey");
            assertTrue(name.matches("[a-zA-Z0-9._\\-]{1,128}"), name);
            names.add(name);
        }
        assertEquals(200, names.size(), "every write must get its own slot");
    }

    @Test
    @DisplayName("the user id never appears in the slot name — only a hash of it")
    void userIdIsHashed() {
        String name = AutoVaultedSecrets.newSlotName(AGENT, "alice@example.com", "apiKey");

        assertFalse(name.contains("alice"), name);
        assertTrue(AutoVaultedSecrets.belongsToUser(name, "alice@example.com"));
        assertFalse(AutoVaultedSecrets.belongsToUser(name, "bob@example.com"));
    }

    @Test
    @DisplayName("a legacy shared <agentId>.<name> slot belongs to nobody — it may back other users' conversations")
    void legacySlotIsNeverOwned() {
        assertFalse(AutoVaultedSecrets.belongsToUser(AGENT + ".apiKey", "user-1"));
    }

    @Test
    @DisplayName("deleteReferencedSlot deletes the user's own current-format slot")
    void deletesOwnSlot() throws Exception {
        String reference = new SecretReference("default", AutoVaultedSecrets.newSlotName(AGENT, "user-1", "apiKey")).toReferenceString();

        assertTrue(secrets.deleteReferencedSlot(reference, "user-1"));
        verify(provider).delete(SecretReference.parse(reference));
    }

    @Test
    @DisplayName("deleteReferencedSlot never touches another user's slot, a legacy slot, or a non-reference")
    void refusesForeignLegacyAndPlainValues() throws Exception {
        String foreign = new SecretReference("default", AutoVaultedSecrets.newSlotName(AGENT, "user-2", "apiKey")).toReferenceString();

        assertFalse(secrets.deleteReferencedSlot(foreign, "user-1"));
        assertFalse(secrets.deleteReferencedSlot("${vault:" + AGENT + ".apiKey}", "user-1"));
        assertFalse(secrets.deleteReferencedSlot("plain text", "user-1"));
        assertFalse(secrets.deleteReferencedSlot(null, "user-1"));
        verify(provider, never()).delete(any());
    }

    @Test
    @DisplayName("deleteForConversation deletes only auto-vaulted properties' slots")
    void deleteForConversation() throws Exception {
        var properties = new LinkedHashMap<String, Property>();
        String own = new SecretReference("default", AutoVaultedSecrets.newSlotName(AGENT, "user-1", "apiKey")).toReferenceString();
        properties.put("apiKey", autoVaulted(own));
        // Same shape, but not produced by auto-vaulting — must be left alone
        String typedIn = new SecretReference("default", AutoVaultedSecrets.newSlotName(AGENT, "user-1", "other")).toReferenceString();
        properties.put("other", new Property("other", typedIn, Scope.conversation));

        assertEquals(1, secrets.deleteForConversation(properties, "user-1"));
        verify(provider).delete(SecretReference.parse(own));
        verify(provider, never()).delete(SecretReference.parse(typedIn));
    }

    @Test
    @DisplayName("deleteForUser sweeps the default tenant and the named tenants, deleting only this user's slots")
    void deleteForUserSweepsTenants() throws Exception {
        String mineDefault = AutoVaultedSecrets.newSlotName(AGENT, "user-1", "apiKey");
        String mineAcme = AutoVaultedSecrets.newSlotName(AGENT, "user-1", "token");
        String theirs = AutoVaultedSecrets.newSlotName(AGENT, "user-2", "apiKey");
        when(provider.listKeys("default")).thenReturn(List.of(metadata("default", mineDefault), metadata("default", theirs),
                metadata("default", AGENT + ".legacy"), metadata("default", "openai-key")));
        when(provider.listKeys("acme")).thenReturn(List.of(metadata("acme", mineAcme)));

        int deleted = secrets.deleteForUser("user-1", List.of("acme"));

        assertEquals(2, deleted);
        verify(provider).delete(new SecretReference("default", mineDefault));
        verify(provider).delete(new SecretReference("acme", mineAcme));
        verify(provider, times(2)).delete(any());
    }

    @Test
    @DisplayName("deleteForUser is a no-op when the vault is disabled")
    void deleteForUserWithoutVault() throws Exception {
        when(provider.isAvailable()).thenReturn(false);

        assertEquals(0, secrets.deleteForUser("user-1", List.of()));
        verify(provider, never()).listKeys(any());
    }

    @Test
    @DisplayName("tenantOf reads the tenant of an auto-vaulted reference, null otherwise")
    void tenantOf() {
        assertEquals("acme", AutoVaultedSecrets.tenantOf(autoVaulted("${vault:acme/" + AGENT + ".x}")));
        assertEquals("default", AutoVaultedSecrets.tenantOf(autoVaulted("${vault:" + AGENT + ".x}")));
        assertNull(AutoVaultedSecrets.tenantOf(new Property("x", "${vault:acme/k}", Scope.conversation)));
        assertNull(AutoVaultedSecrets.tenantOf(null));
    }
}
