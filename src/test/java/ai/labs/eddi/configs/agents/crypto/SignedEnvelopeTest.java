/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents.crypto;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SignedEnvelope Tests")
class SignedEnvelopeTest {

    @Nested
    @DisplayName("forSigning Factory")
    class ForSigningTests {

        @Test
        @DisplayName("Should create unsigned envelope with nonce and timestamp")
        void testForSigning() {
            SignedEnvelope envelope = SignedEnvelope.forSigning(
                    "agent-1", "agent-2", Map.of("message", "hello"));

            assertEquals("agent-1", envelope.senderId());
            assertEquals("agent-2", envelope.recipientId());
            assertEquals(Map.of("message", "hello"), envelope.payload());
            assertNotNull(envelope.nonce());
            assertFalse(envelope.nonce().isEmpty());
            assertTrue(envelope.timestampMs() > 0);
            assertNull(envelope.signature());
            assertEquals(0, envelope.keyVersion());
        }

        @Test
        @DisplayName("Should generate unique nonces")
        void testUniqueNonces() {
            SignedEnvelope e1 = SignedEnvelope.forSigning("a", "b", Map.of());
            SignedEnvelope e2 = SignedEnvelope.forSigning("a", "b", Map.of());
            assertNotEquals(e1.nonce(), e2.nonce());
        }
    }

    @Nested
    @DisplayName("withSignature")
    class WithSignatureTests {

        @Test
        @DisplayName("Should attach signature and key version")
        void testWithSignature() {
            SignedEnvelope unsigned = SignedEnvelope.forSigning("a", "b", Map.of("k", "v"));
            SignedEnvelope signed = unsigned.withSignature("sig123", 2);

            assertEquals("sig123", signed.signature());
            assertEquals(2, signed.keyVersion());
            // Other fields preserved
            assertEquals(unsigned.senderId(), signed.senderId());
            assertEquals(unsigned.nonce(), signed.nonce());
            assertEquals(unsigned.timestampMs(), signed.timestampMs());
        }
    }

    @Nested
    @DisplayName("canonicalForm")
    class CanonicalFormTests {

        @Test
        @DisplayName("Should produce canonical JSON without signature fields")
        void testCanonicalForm() throws JsonProcessingException {
            SignedEnvelope envelope = SignedEnvelope.forSigning("a", "b", Map.of("msg", "hi"));
            String canonical = envelope.canonicalForm();

            assertNotNull(canonical);
            assertFalse(canonical.contains("\"signature\""));
            assertTrue(canonical.contains("\"senderId\""));
            assertTrue(canonical.contains("\"nonce\""));
        }

        @Test
        @DisplayName("Should produce same canonical form for signed and unsigned versions")
        void testCanonicalSameBeforeAndAfter() throws JsonProcessingException {
            SignedEnvelope unsigned = SignedEnvelope.forSigning("a", "b", Map.of("x", 1));
            SignedEnvelope signed = unsigned.withSignature("somesig", 1);

            assertEquals(unsigned.canonicalForm(), signed.canonicalForm());
        }
    }
}
