/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for EncryptedSecret — constructors, storage key, and round-trip.
 */
class EncryptedSecretTest {

    @Test
    @DisplayName("no-arg constructor — all fields null")
    void noArgConstructor() {
        var secret = new EncryptedSecret();
        assertNull(secret.getId());
        assertNull(secret.getTenantId());
        assertNull(secret.getKeyName());
        assertNull(secret.getEncryptedValue());
        assertNull(secret.getIv());
        assertNull(secret.getDekId());
        assertNull(secret.getChecksum());
        assertNull(secret.getDescription());
        assertNull(secret.getAllowedAgents());
        assertNull(secret.getCreatedAt());
        assertNull(secret.getLastAccessedAt());
        assertNull(secret.getLastRotatedAt());
    }

    @Test
    @DisplayName("all-args constructor populates every field")
    void allArgsConstructor() {
        var now = Instant.now();
        var secret = new EncryptedSecret(
                "id-1", "tenant-A", "api-key",
                "encrypted-val", "iv-base64", "dek-42",
                "sha256hex", "My API key",
                List.of("agent-1", "agent-2"),
                now, now, now);

        assertEquals("id-1", secret.getId());
        assertEquals("tenant-A", secret.getTenantId());
        assertEquals("api-key", secret.getKeyName());
        assertEquals("encrypted-val", secret.getEncryptedValue());
        assertEquals("iv-base64", secret.getIv());
        assertEquals("dek-42", secret.getDekId());
        assertEquals("sha256hex", secret.getChecksum());
        assertEquals("My API key", secret.getDescription());
        assertEquals(2, secret.getAllowedAgents().size());
        assertEquals(now, secret.getCreatedAt());
        assertEquals(now, secret.getLastAccessedAt());
        assertEquals(now, secret.getLastRotatedAt());
    }

    @Test
    @DisplayName("storageKey returns tenantId/keyName")
    void storageKey() {
        var secret = new EncryptedSecret();
        secret.setTenantId("t1");
        secret.setKeyName("openai-key");

        assertEquals("t1/openai-key", secret.storageKey());
    }

    @Test
    @DisplayName("round-trip all setters")
    void roundTrip() {
        var secret = new EncryptedSecret();
        var t1 = Instant.parse("2025-01-01T00:00:00Z");
        var t2 = Instant.parse("2025-06-01T00:00:00Z");

        secret.setId("id-99");
        secret.setTenantId("tenant-B");
        secret.setKeyName("secret-key");
        secret.setEncryptedValue("enc-data");
        secret.setIv("iv-data");
        secret.setDekId("dek-7");
        secret.setChecksum("abcdef");
        secret.setDescription("Test secret");
        secret.setAllowedAgents(List.of("*"));
        secret.setCreatedAt(t1);
        secret.setLastAccessedAt(t2);
        secret.setLastRotatedAt(t2);

        assertEquals("id-99", secret.getId());
        assertEquals("tenant-B", secret.getTenantId());
        assertEquals("secret-key", secret.getKeyName());
        assertEquals("enc-data", secret.getEncryptedValue());
        assertEquals("iv-data", secret.getIv());
        assertEquals("dek-7", secret.getDekId());
        assertEquals("abcdef", secret.getChecksum());
        assertEquals("Test secret", secret.getDescription());
        assertEquals(List.of("*"), secret.getAllowedAgents());
        assertEquals(t1, secret.getCreatedAt());
        assertEquals(t2, secret.getLastAccessedAt());
        assertEquals(t2, secret.getLastRotatedAt());
    }

    @Test
    @DisplayName("storageKey with null tenantId")
    void storageKey_nullTenant() {
        var secret = new EncryptedSecret();
        secret.setKeyName("key");
        assertEquals("null/key", secret.storageKey());
    }
}
