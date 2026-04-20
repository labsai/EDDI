package ai.labs.eddi.engine.audit;

import ai.labs.eddi.engine.audit.model.AuditEntry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AuditHmac} — HMAC key derivation, signing, and verification.
 */
@DisplayName("AuditHmac")
class AuditHmacTest {

    private static byte[] hmacKey;

    @BeforeAll
    static void deriveKey() {
        hmacKey = AuditHmac.deriveHmacKey("test-master-key-12345");
    }

    private static AuditEntry createTestEntry() {
        return new AuditEntry(
                "entry-1", "conv-1", "agent-1", 1, "user-1", "production",
                0, "ai.labs.parser", "expressions", 0, 42L,
                Map.of("userInput", "hello"),
                Map.of("output", "world"),
                null, null,
                List.of("send_message"), 0.005,
                Instant.parse("2026-01-01T00:00:00Z"), null, null);
    }

    // ==================== Key Derivation ====================

    @Nested
    @DisplayName("deriveHmacKey")
    class DeriveKeyTests {

        @Test
        @DisplayName("produces 32-byte key (256 bits)")
        void keyLength() {
            assertEquals(32, hmacKey.length);
        }

        @Test
        @DisplayName("same master key produces same HMAC key (deterministic)")
        void deterministic() {
            byte[] key2 = AuditHmac.deriveHmacKey("test-master-key-12345");
            assertArrayEquals(hmacKey, key2);
        }

        @Test
        @DisplayName("different master keys produce different HMAC keys")
        void differentKeys() {
            byte[] key2 = AuditHmac.deriveHmacKey("different-master-key");
            assertFalse(java.util.Arrays.equals(hmacKey, key2));
        }
    }

    // ==================== HMAC Computation ====================

    @Nested
    @DisplayName("computeHmac")
    class ComputeHmacTests {

        @Test
        @DisplayName("produces non-null hex string")
        void producesHex() {
            String hmac = AuditHmac.computeHmac(createTestEntry(), hmacKey);
            assertNotNull(hmac);
            assertFalse(hmac.isEmpty());
            // Hex string should be 64 chars (32 bytes)
            assertEquals(64, hmac.length());
        }

        @Test
        @DisplayName("same entry produces same HMAC (deterministic)")
        void deterministic() {
            AuditEntry entry = createTestEntry();
            String hmac1 = AuditHmac.computeHmac(entry, hmacKey);
            String hmac2 = AuditHmac.computeHmac(entry, hmacKey);
            assertEquals(hmac1, hmac2);
        }

        @Test
        @DisplayName("different entries produce different HMACs")
        void differentEntries() {
            AuditEntry entry1 = createTestEntry();
            AuditEntry entry2 = new AuditEntry(
                    "entry-2", "conv-2", "agent-1", 1, "user-1", "production",
                    0, "ai.labs.parser", "expressions", 0, 42L,
                    Map.of("userInput", "different"),
                    Map.of("output", "world"),
                    null, null,
                    List.of("send_message"), 0.005,
                    Instant.parse("2026-01-01T00:00:00Z"), null, null);
            assertNotEquals(AuditHmac.computeHmac(entry1, hmacKey),
                    AuditHmac.computeHmac(entry2, hmacKey));
        }
    }

    // ==================== HMAC Verification ====================

    @Nested
    @DisplayName("verifyHmac")
    class VerifyHmacTests {

        @Test
        @DisplayName("verifies valid HMAC")
        void validHmac() {
            AuditEntry entry = createTestEntry();
            String hmac = AuditHmac.computeHmac(entry, hmacKey);
            AuditEntry signed = entry.withHmac(hmac);
            assertTrue(AuditHmac.verifyHmac(signed, hmacKey));
        }

        @Test
        @DisplayName("rejects tampered entry")
        void tamperedEntry() {
            AuditEntry entry = createTestEntry();
            String hmac = AuditHmac.computeHmac(entry, hmacKey);
            // Tamper with the entry by changing the environment
            AuditEntry tampered = entry.withEnvironment("TAMPERED").withHmac(hmac);
            assertFalse(AuditHmac.verifyHmac(tampered, hmacKey));
        }

        @Test
        @DisplayName("rejects null HMAC")
        void nullHmac() {
            AuditEntry entry = createTestEntry();
            assertFalse(AuditHmac.verifyHmac(entry, hmacKey));
        }

        @Test
        @DisplayName("rejects wrong key")
        void wrongKey() {
            AuditEntry entry = createTestEntry();
            String hmac = AuditHmac.computeHmac(entry, hmacKey);
            AuditEntry signed = entry.withHmac(hmac);

            byte[] wrongKey = AuditHmac.deriveHmacKey("wrong-master-key");
            assertFalse(AuditHmac.verifyHmac(signed, wrongKey));
        }
    }

    // ==================== Canonical String ====================

    @Nested
    @DisplayName("buildCanonicalString")
    class CanonicalStringTests {

        @Test
        @DisplayName("includes all fields")
        void includesAllFields() {
            String canonical = AuditHmac.buildCanonicalString(createTestEntry());
            assertTrue(canonical.contains("id=entry-1"));
            assertTrue(canonical.contains("cid=conv-1"));
            assertTrue(canonical.contains("bid=agent-1"));
            assertTrue(canonical.contains("uid=user-1"));
            assertTrue(canonical.contains("env=production"));
            assertTrue(canonical.contains("tid=ai.labs.parser"));
            assertTrue(canonical.contains("actions=send_message"));
        }

        @Test
        @DisplayName("handles null fields gracefully")
        void nullFields() {
            AuditEntry entry = new AuditEntry(
                    null, null, null, null, null, null,
                    0, null, null, 0, 0L,
                    null, null, null, null, null, 0.0,
                    null, null, null);
            String canonical = AuditHmac.buildCanonicalString(entry);
            assertNotNull(canonical);
            assertTrue(canonical.contains("id="));
            assertTrue(canonical.contains("cid="));
        }

        @Test
        @DisplayName("maps are sorted for deterministic output")
        void sortedMaps() {
            AuditEntry entry1 = new AuditEntry(
                    "id", "c", "b", 1, "u", "e",
                    0, "t", "tt", 0, 0L,
                    Map.of("z", "1", "a", "2"),
                    null, null, null, null, 0.0,
                    Instant.EPOCH, null, null);
            String canonical = AuditHmac.buildCanonicalString(entry1);
            // a should come before z in sorted output
            int posA = canonical.indexOf("a=2");
            int posZ = canonical.indexOf("z=1");
            assertTrue(posA < posZ, "Map keys should be sorted alphabetically");
        }
    }
}
