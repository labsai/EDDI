/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AgentPublicKey Tests")
class AgentPublicKeyTest {

    @Nested
    @DisplayName("isValidAt")
    class ValidityTests {

        @Test
        @DisplayName("Should be valid after validFrom with no expiry")
        void testValidNoExpiry() {
            var key = new AgentPublicKey(1, "pubkey", 1000, 0);
            assertTrue(key.isValidAt(1000));
            assertTrue(key.isValidAt(Long.MAX_VALUE));
        }

        @Test
        @DisplayName("Should not be valid before validFrom")
        void testNotValidBefore() {
            var key = new AgentPublicKey(1, "pubkey", 1000, 0);
            assertFalse(key.isValidAt(999));
        }

        @Test
        @DisplayName("Should respect validUntil")
        void testExpiry() {
            var key = new AgentPublicKey(1, "pubkey", 1000, 2000);
            assertTrue(key.isValidAt(1500));
            assertTrue(key.isValidAt(2000)); // inclusive
            assertFalse(key.isValidAt(2001));
        }
    }

    @Nested
    @DisplayName("Factory Methods")
    class FactoryTests {

        @Test
        @DisplayName("Should create current key with no expiry")
        void testCreateCurrent() {
            var key = AgentPublicKey.createCurrent(1, "pubkey");
            assertEquals(1, key.version());
            assertEquals("pubkey", key.publicKeyB64());
            assertTrue(key.validFromMs() > 0);
            assertEquals(0, key.validUntilMs());
        }

        @Test
        @DisplayName("Should create expired copy with withExpiry")
        void testWithExpiry() {
            var key = AgentPublicKey.createCurrent(1, "pubkey");
            var expired = key.withExpiry(System.currentTimeMillis() + 3600_000);

            assertEquals(key.version(), expired.version());
            assertEquals(key.publicKeyB64(), expired.publicKeyB64());
            assertTrue(expired.validUntilMs() > 0);
        }
    }

    @Nested
    @DisplayName("Record Equality")
    class EqualityTests {

        @Test
        @DisplayName("Should be equal for same values")
        void testEquality() {
            var k1 = new AgentPublicKey(1, "pk", 100, 200);
            var k2 = new AgentPublicKey(1, "pk", 100, 200);
            assertEquals(k1, k2);
            assertEquals(k1.hashCode(), k2.hashCode());
        }

        @Test
        @DisplayName("Should not be equal for different versions")
        void testInequality() {
            var k1 = new AgentPublicKey(1, "pk", 100, 200);
            var k2 = new AgentPublicKey(2, "pk", 100, 200);
            assertNotEquals(k1, k2);
        }
    }
}
