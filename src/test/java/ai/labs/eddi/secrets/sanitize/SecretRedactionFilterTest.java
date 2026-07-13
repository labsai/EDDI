/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.sanitize;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SecretRedactionFilterTest {

    @Test
    void redact_openaiKey() {
        String input = "Using key sk-abcdefghij1234567890abcdef to call API";
        String result = SecretRedactionFilter.redact(input);

        assertFalse(result.contains("abcdefghij1234567890abcdef"));
        assertTrue(result.contains("sk-<REDACTED>"));
    }

    @Test
    void redact_anthropicKey() {
        String input = "key=sk-ant-api03-longKeyValue1234567890";
        String result = SecretRedactionFilter.redact(input);

        assertFalse(result.contains("longKeyValue1234567890"));
        assertTrue(result.contains("sk-ant-<REDACTED>"));
    }

    @Test
    void redact_bearerToken() {
        String input = "Authorization: Bearer eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.signature";
        String result = SecretRedactionFilter.redact(input);

        assertFalse(result.contains("eyJhbGci"));
        assertTrue(result.contains("Bearer <REDACTED>"));
    }

    @Test
    void redact_bearerOpaqueToken() {
        // The Bearer rule now redacts opaque (non-JWT, dot-less) tokens too, as long
        // as they are long enough (>= 20 chars) to not be a benign word.
        String input = "Authorization: Bearer abcdefghij1234567890abcdef";
        String result = SecretRedactionFilter.redact(input);

        assertFalse(result.contains("abcdefghij1234567890abcdef"));
        assertTrue(result.contains("Bearer <REDACTED>"));
    }

    @Test
    void redact_bearerShortToken_unchanged() {
        // Below the 20-char minimum → left intact so benign short words survive.
        String input = "Bearer short";
        assertEquals(input, SecretRedactionFilter.redact(input));
    }

    @Test
    void redact_vaultReference() {
        String input = "Resolved secret: ${vault:default/agent1/apiKey}";
        String result = SecretRedactionFilter.redact(input);

        assertTrue(result.contains("${vault:<REDACTED>}"));
        assertFalse(result.contains("default/agent1/apiKey"));
    }

    @Test
    void redact_nullAndEmpty() {
        assertNull(SecretRedactionFilter.redact(null));
        assertEquals("", SecretRedactionFilter.redact(""));
    }

    @Test
    void redact_safeMessages() {
        String safe = "User said hello, Agent responded with greeting";
        assertEquals(safe, SecretRedactionFilter.redact(safe));
    }
}
