/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class SecretReferenceTest {

    @Test
    void parse_shortForm_defaultTenant() {
        var ref = SecretReference.parse("${eddivault:openaiKey}");

        assertEquals("default", ref.tenantId());
        assertEquals("openaiKey", ref.keyName());
    }

    @Test
    void parse_fullForm_explicitTenant() {
        var ref = SecretReference.parse("${eddivault:myTenant/openaiKey}");

        assertEquals("myTenant", ref.tenantId());
        assertEquals("openaiKey", ref.keyName());
    }

    @Test
    void toReferenceString_defaultTenant_shortForm() {
        var ref = new SecretReference("default", "apiKey");
        assertEquals("${eddivault:apiKey}", ref.toReferenceString());
    }

    @Test
    void toReferenceString_customTenant_fullForm() {
        var ref = new SecretReference("acmeCorp", "apiKey");
        assertEquals("${eddivault:acmeCorp/apiKey}", ref.toReferenceString());
    }

    @Test
    void roundtrip_shortForm() {
        var ref = new SecretReference("default", "myKey");
        String refStr = ref.toReferenceString();
        assertEquals("${eddivault:myKey}", refStr);

        var parsed = SecretReference.parse(refStr);
        assertEquals(ref, parsed);
    }

    @Test
    void roundtrip_fullForm() {
        var ref = new SecretReference("tenant1", "apiKey");
        String refStr = ref.toReferenceString();
        assertEquals("${eddivault:tenant1/apiKey}", refStr);

        var parsed = SecretReference.parse(refStr);
        assertEquals(ref, parsed);
    }

    @Test
    void parse_autoVaultedKey_withAgentPrefix() {
        // Auto-vaulted keys have agentId.keyName format
        var ref = SecretReference.parse("${eddivault:69c687.userApiKey}");

        assertEquals("default", ref.tenantId());
        assertEquals("69c687.userApiKey", ref.keyName());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-vault-ref", "${vault:foo/bar}", "${eddivault:}"})
    void parse_invalidReference_throws(String input) {
        assertThrows(IllegalArgumentException.class, () -> SecretReference.parse(input));
    }

    @Test
    void isVaultReference_positive() {
        assertTrue(SecretReference.isVaultReference("${eddivault:key}"));
        assertTrue(SecretReference.isVaultReference("${eddivault:t/k}"));
        assertTrue(SecretReference.isVaultReference("Bearer ${eddivault:openaiKey} extra"));
    }

    @Test
    void isVaultReference_negative() {
        assertFalse(SecretReference.isVaultReference("plain text"));
        assertFalse(SecretReference.isVaultReference(""));
        assertFalse(SecretReference.isVaultReference(null));
        assertFalse(SecretReference.isVaultReference("${vault:different}"));
    }
}
