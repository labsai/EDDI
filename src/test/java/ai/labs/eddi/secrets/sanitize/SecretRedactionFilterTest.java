/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.sanitize;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class SecretRedactionFilterTest {

    private static final String ANTHROPIC_KEY = "sk-ant-api03-CeIJ4onq59Mf_oN4mICgfgScyJO5bfxFSS3Sdvo1Zgo2F7zUfEvx";

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

    /**
     * Redacting a JSON body must leave JSON behind.
     * <p>
     * The rule these replace matched the key's closing quote, the colon AND the
     * value's opening quote, then threw all three away — so
     * {@code {"apiKey":"sk-…"}} came back as {@code {"apiKey=<REDACTED>"}}: a bare
     * string where a key/value pair was. Every reader of a redacted body parses it,
     * and both of the Manager's failed silently on that — the approval diff fell
     * back to comparing raw text and reported the whole stored document as deleted,
     * and the capability-grant checks sit behind a {@code JSON.parse}, so a request
     * that embedded a credential AND granted dynamic agent creation warned about
     * the credential alone.
     */
    @Nested
    class RedactedJsonStaysJson {

        @Test
        void aStringValueKeepsItsQuotesAndItsSiblings() {
            String input = "{\"modelName\":\"claude-sonnet-5\",\"apiKey\":\"" + ANTHROPIC_KEY + "\",\"threshold\":9}";
            String result = SecretRedactionFilter.redact(input);

            assertFalse(result.contains("CeIJ4onq59Mf"), result);
            assertEquals("{\"modelName\":\"claude-sonnet-5\",\"apiKey\":\"sk-ant-<REDACTED>\",\"threshold\":9}", result);
            assertValidJson(result);
        }

        @Test
        void aNonStringValueGetsTheKeysQuoteStyleSoTheDocumentStillParses() {
            // Nothing to preserve here — the value had no quotes — so the marker
            // brings its own, or the document ends up with a bare <REDACTED> token.
            String result = SecretRedactionFilter.redact("{\"token\":123456789012,\"n\":1}");

            assertFalse(result.contains("123456789012"), result);
            assertEquals("{\"token\":\"<REDACTED>\",\"n\":1}", result);
            assertValidJson(result);
        }

        @Test
        void aNestedValueStillParses() {
            String result = SecretRedactionFilter.redact("{\"llm\":{\"apiKey\":\"abcdefghij\"},\"n\":1}");

            assertEquals("{\"llm\":{\"apiKey\":\"<REDACTED>\"},\"n\":1}", result);
            assertValidJson(result);
        }

        @Test
        void anEscapedJsonBodyStaysEscapedJson() {
            // A tool call whose requestBody argument is itself a JSON document: every
            // quote arrives backslashed, and the backslashes have to come back out.
            String input = "{\"requestBody\": \"{\\\"llm\\\": {\\\"apiKey\\\": \\\"" + ANTHROPIC_KEY + "\\\"}}\"}";
            String result = SecretRedactionFilter.redact(input);

            assertFalse(result.contains("CeIJ4onq59Mf"), result);
            assertTrue(result.contains("\\\"apiKey\\\": \\\"sk-ant-<REDACTED>\\\""), result);
            assertValidJson(result);
        }

        @Test
        void severalCredentialFieldsAllParse() {
            String result = SecretRedactionFilter
                    .redact("{\"apiKey\":\"abcdefghij\",\"password\":\"klmnopqrst\",\"n\":1}");

            assertEquals("{\"apiKey\":\"<REDACTED>\",\"password\":\"<REDACTED>\",\"n\":1}", result);
            assertValidJson(result);
        }

        @Test
        void redactingTwiceChangesNothingTheSecondTime() {
            String once = SecretRedactionFilter.redact("{\"apiKey\":\"" + ANTHROPIC_KEY + "\",\"n\":1}");
            assertEquals(once, SecretRedactionFilter.redact(once));
        }

        @Test
        void aQueryStringIsNotGivenQuotesItNeverHad() {
            // The JSON rules must not fire outside JSON: there is no key quote here,
            // so quoting the marker would corrupt the URI.
            assertEquals("https://x/y?api_key=<REDACTED>",
                    SecretRedactionFilter.redact("https://x/y?api_key=abcdefghijklmnop"));
        }

        @Test
        void aTrailingQueryParamIsSwallowedRatherThanRiskingALeak() {
            // Pinning a deliberate non-fix. '&' is NOT in the value class, so the
            // scan runs past it and the rest of the query goes with the secret.
            // Adding it would cut short every secret CONTAINING an '&' in every
            // other context and leak the tail — over-redacting a raw URL that
            // happens to sit in a log line is the safer trade. Real request URIs
            // never reach here whole: RequestRedactor scans each query parameter's
            // value on its own, where there is no '&' left to meet.
            assertEquals("https://x/y?api_key=<REDACTED>",
                    SecretRedactionFilter.redact("https://x/y?api_key=abcdefghijklmnop&z=1"));
        }

        @Test
        void aPlainLogLineIsNotGivenQuotesEither() {
            assertEquals("password: <REDACTED>", SecretRedactionFilter.redact("password: hunter2butlonger"));
        }

        private void assertValidJson(String value) {
            assertDoesNotThrow(() -> new ObjectMapper().readTree(value),
                    () -> "redaction produced something that is no longer JSON: " + value);
        }
    }

    /**
     * The value class stops at ',', whitespace, ';', '{', '}' and ']', so a secret
     * containing any of them was redacted only up to that character and the tail
     * survived into the "redacted" output. A quoted value ends at its closing
     * quote, not at the first delimiter.
     */
    @Nested
    class ASecretIsRedactedInFull {

        @ParameterizedTest(name = "a secret cut short at [{0}]")
        @ValueSource(strings = {",", " ", ";", "{", "}", "]"})
        void aSecretIsNotCutShortAtADelimiter(String delimiter) {
            String secret = "abcdefgh" + delimiter + "SURVIVING-TAIL";
            String result = SecretRedactionFilter.redact("{\"password\":\"" + secret + "\",\"n\":1}");

            assertFalse(result.contains("SURVIVING-TAIL"), result);
            assertEquals("{\"password\":\"<REDACTED>\",\"n\":1}", result);
        }

        @Test
        void aShortValueIsStillLeftAlone() {
            // The 8-char floor is what keeps benign values legible; running to the
            // closing quote must not quietly lower it.
            String input = "{\"password\":\"short\",\"n\":1}";
            assertEquals(input, SecretRedactionFilter.redact(input));
        }

        @Test
        void aNeighbouringFieldIsNotSwallowed() {
            // The value class cannot cross a quote, so a short value must fail to
            // match rather than reaching into the next field for its 8 characters.
            String input = "{\"password\":\"abc\",\"note\":\"nothing secret here\"}";
            assertEquals(input, SecretRedactionFilter.redact(input));
        }
    }

    /**
     * An earlier rule's replacement says WHAT KIND of credential was found —
     * {@code sk-ant-<REDACTED>}, {@code Bearer <REDACTED>} — and that is
     * information an approver uses. The later rules must not strip it back off,
     * which the old generic rule did to every named field.
     */
    @Nested
    class AlreadyRedactedValuesKeepTheirPrefix {

        @Test
        void anAnthropicKeyKeepsItsPrefixInsideAJsonField() {
            String result = SecretRedactionFilter.redact("{\"apiKey\":\"" + ANTHROPIC_KEY + "\"}");
            assertEquals("{\"apiKey\":\"sk-ant-<REDACTED>\"}", result);
        }

        @Test
        void aBearerTokenKeepsItsPrefixInsideAJsonField() {
            String input = "{\"Authorization\":\"Bearer eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.signature\"}";
            assertEquals("{\"Authorization\":\"Bearer <REDACTED>\"}", SecretRedactionFilter.redact(input));
        }
    }
}
