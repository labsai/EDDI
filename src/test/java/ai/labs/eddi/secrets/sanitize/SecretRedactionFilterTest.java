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

    /**
     * Regression: real Anthropic keys carry underscores, and a character class
     * without {@code _} stopped matching at the first one — a full key inside a
     * gated tool call's arguments sailed through "redacted" and rendered clear-text
     * on the approval card.
     */
    @Test
    void redact_anthropicKeyWithUnderscores() {
        String input = "sk-ant-api03-CeIJ4onq59Mf_oN4mICgfgScyJO5bfxFSS3Sdvo1Zgo2F7zUfEvx_ljLzk8GHUWqILDGl8og-C3SuewAA";
        String result = SecretRedactionFilter.redact(input);

        assertFalse(result.contains("CeIJ4onq59Mf"));
        assertFalse(result.contains("ljLzk8GHUWq"));
    }

    @Test
    void redact_openaiProjectKeyWithUnderscoresAndHyphens() {
        String input = "sk-proj-Ab_cd-EFGH1234567890abcdefghij";
        String result = SecretRedactionFilter.redact(input);

        assertFalse(result.contains("EFGH1234567890"));
    }

    /**
     * Regression: the exact shape of the approval-card leak — a tool call whose
     * {@code requestBody} argument is itself a JSON document, so every quote around
     * {@code apiKey} arrives backslash-escaped. The generic rule's separator never
     * matched through the escaping, and the key survived into the "redacted"
     * arguments the approver reads.
     */
    @Test
    void redact_apiKeyInsideEscapedJsonRequestBody() {
        String input = "{\"requestBody\": \"{\\n  \\\"llm\\\": {\\n    \\\"apiKey\\\": "
                + "\\\"sk-ant-api03-CeIJ4onq59Mf_oN4mICgfgScyJO5bfxFSS3Sdvo1Zgo2F7zUfEvx\\\"\\n  }\\n}\"}";
        String result = SecretRedactionFilter.redact(input);

        assertFalse(result.contains("CeIJ4onq59Mf"));
        assertTrue(result.contains("<REDACTED>"));
    }

    @Test
    void redact_secretFieldInsideEscapedJson_evenWithoutKnownPrefix() {
        String input = "\\\"password\\\": \\\"hunter2butlonger\\\"";
        String result = SecretRedactionFilter.redact(input);

        assertFalse(result.contains("hunter2butlonger"));
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

    /**
     * A vault reference is a POINTER to a secret, not a secret — and the correct,
     * encouraged alternative to writing one down. It is left legible on purpose:
     * masking it hid which credential a request uses (exactly what an approver must
     * judge), and made every correct request display a {@code <REDACTED>} marker —
     * the very signal that is supposed to mean "a secret literal was embedded
     * here".
     */
    @Test
    void redact_vaultReference_leftLegible() {
        String input = "Uses ${vault:default/agent1/apiKey} for auth";
        assertEquals(input, SecretRedactionFilter.redact(input));
    }

    @Test
    void redact_vaultReferenceInsideJson_leftLegible() {
        String input = "{\"llm\": {\"apiKey\": \"${vault:anthropic-api-key}\"}}";
        String result = SecretRedactionFilter.redact(input);

        assertTrue(result.contains("${vault:anthropic-api-key}"),
                "the approver needs to see WHICH credential the request uses");
        assertFalse(result.contains("<REDACTED>"),
                "a correctly vault-referencing request must not display a redaction marker at all");
    }

    @Test
    void redact_legacyVaultReference_leftLegible() {
        String input = "Uses ${eddivault:legacy-key} for auth";
        assertEquals(input, SecretRedactionFilter.redact(input));
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
