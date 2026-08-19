/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.sanitize;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class SecretRedactionFilterTest {

    private static final String ANTHROPIC_KEY = "sk-ant-api03-CeIJ4onq59Mf_oN4mICgfgScyJO5bfxFSS3Sdvo1Zgo2F7zUfEvx";
    private static final String REDACTED_MARKER = "<REDACTED>";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Redaction must never turn a JSON document into something that is not one.
     * <p>
     * "One document" means exactly one ROOT VALUE, the same thing
     * {@code JSON.parse} means by it and so the same thing the Manager means.
     * {@code readTree} alone stops at the end of the first value and ignores
     * whatever follows, which would call {@code {"a":1}"junk"} intact — precisely
     * the torn carrier this asserts against. The filter draws the line in the same
     * place; an oracle that drew it anywhere else would disagree with the code it
     * is checking.
     */
    private static void assertValidJson(String value) {
        assertDoesNotThrow(() -> {
            try (JsonParser parser = MAPPER.createParser(value)) {
                assertNotNull(parser.nextToken(), "no JSON value at all");
                parser.skipChildren();
                assertNull(parser.nextToken(), "trailing content after the root value");
            }
        }, () -> "redaction produced something that is no longer JSON: " + value);
    }

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

        @Test
        void aKeyWhoseNameEndsInAColonIsNotMisreadAsASeparator() {
            // Found by a fuzzer, in valid JSON: a key NAMED `token:`. The loose rule
            // read the colon inside the name as the separator and the key's closing
            // quote as the value's opening quote, then consumed the real separator
            // and the number — leaving a bare marker where the pair had been. The
            // rule before this branch broke it the same way. The value is not under
            // a recognised credential key, so it is left alone — and the document
            // stays JSON, which is the promise.
            String input = "{\"token:\":12378901,\"password\":\"${vaut:G\",\"secret\":\"<REDACTED>\"}";
            String result = SecretRedactionFilter.redact(input);

            assertEquals("{\"token:\":12378901,\"password\":\"<REDACTED>\",\"secret\":\"<REDACTED>\"}", result);
            assertValidJson(result);
        }

        @Test
        void aStringEndingInACredentialWordIsNotAKeyWithAValue() {
            // Found by a fuzzer. `"…token:"` is a whole JSON string; its CLOSING
            // quote reads as a value's opening quote, and the whitespace after it
            // reads as a 36-character value. Redacting whitespace can only destroy
            // structure — it never protects a secret — so a blank value is left
            // alone whatever the shape around it.
            String input = "\"----------------token:\"" + "\t".repeat(36);

            String result = SecretRedactionFilter.redact(input);

            assertEquals(input, result);
            assertValidJson(result);
        }

        @Test
        void anAllWhitespaceValueIsLeftAlone() {
            String input = "{\"password\":\"          \",\"n\":1}";
            assertEquals(input, SecretRedactionFilter.redact(input));
        }

        @Test
        void theKeyWithASeparatorGuardDoesNotCatchAValueStartingWithAColon() {
            // Distinguished from the case above by the key-close quote being present.
            String result = SecretRedactionFilter.redact("{\"password\": \":starts-with-colon\",\"n\":1}");

            assertEquals("{\"password\": \"<REDACTED>\",\"n\":1}", result);
        }

        @Test
        void theKeyWithASeparatorGuardDoesNotCatchACredentialAtTheStartOfAStringValue() {
            // Distinguished from the case above by no separator following the quote.
            String result = SecretRedactionFilter.redact("{\"error\":\"password:\\\"hunter2butlonger\\\" is invalid\"}");

            assertFalse(result.contains("hunter2butlonger"), result);
            assertEquals("{\"error\":\"password:\\\"<REDACTED>\\\" is invalid\"}", result);
            assertValidJson(result);
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

        @Test
        void theWrongQuoteCharacterCannotEndTheValue() {
            // Found in review. Accepting "any quote" as the terminator let an
            // apostrophe close a double-quoted value: everything after it was
            // published.
            String result = SecretRedactionFilter.redact("{\"password\":\"abcdefgh'SURVIVING-TAIL\",\"n\":1}");

            assertFalse(result.contains("SURVIVING-TAIL"), result);
            assertEquals("{\"password\":\"<REDACTED>\",\"n\":1}", result);
        }

        @Test
        void anApostropheEarlyInAValueDoesNotPutItUnderTheLengthFloor() {
            // Same root cause, worse symptom: the value was cut to "it" — two
            // characters, under the 8-char floor — so nothing matched at all and the
            // whole password went out in the clear.
            String result = SecretRedactionFilter.redact("{\"password\":\"it's-a-secret-value\",\"n\":1}");

            assertFalse(result.contains("secret-value"), result);
            assertEquals("{\"password\":\"<REDACTED>\",\"n\":1}", result);
        }

        @Test
        void aSingleQuotedValueMayContainDoubleQuotes() {
            // Free text — the apostrophe opened outside any double-quoted string, so
            // the value runs to its matching apostrophe and a double quote inside it
            // is content. (Opened INSIDE a double-quoted string it could not cross
            // that string's end; the invariant suite pins both.)
            String result = SecretRedactionFilter.redact("password='abcdefgh\"SURVIVING-TAIL'");

            assertFalse(result.contains("SURVIVING-TAIL"), result);
            assertEquals("password='<REDACTED>'", result);
        }

        @Test
        void aSingleQuotedValueWithoutADoubleQuoteIsRedactedWhole() {
            String result = SecretRedactionFilter.redact("password='abcdefgh SURVIVING-TAIL, and more'");

            assertFalse(result.contains("SURVIVING-TAIL"), result);
            assertEquals("password='<REDACTED>'", result);
        }

        @Test
        void anUnterminatedValueIsStillRedacted() {
            // A truncated body has no closing quote, so the quoted rule cannot fire.
            // The loose rule is the safety net and must still catch it — a body cut
            // short is exactly when a leak would go unnoticed.
            String result = SecretRedactionFilter.redact("{\"apiKey\": \"unterminated-secret-value");

            assertFalse(result.contains("unterminated-secret"), result);
            assertTrue(result.contains(REDACTED_MARKER), result);
        }

        @Test
        void anEscapedQuoteInsideAValueDoesNotEndItEarly() {
            // `\"` is a terminator in an escaped-JSON body and an escaped quote
            // inside the value in a plain one. The opening quote's own escaping says
            // which document this is: it has none, so a quote wearing a backslash is
            // inside the value, whatever follows it.
            String result = SecretRedactionFilter.redact("{\"password\":\"he said \\\"x\\\" SURVIVING-TAIL\",\"n\":1}");

            assertFalse(result.contains("SURVIVING-TAIL"), result);
            assertEquals("{\"password\":\"<REDACTED>\",\"n\":1}", result);
            assertValidJson(result);
        }

        @ParameterizedTest(name = "an escaped quote followed by [{0}] is still inside the value")
        @ValueSource(strings = {",", "}", "]", " ,", " }"})
        void anEscapedQuoteFollowedByADelimiterIsStillInsideTheValue(String following) {
            // Found in review. A draft that decided the terminator by what FOLLOWED
            // the quote took `\",` for the end of the value and published the rest.
            // Escaping, not context, decides — and here the escaping says "inside".
            String result = SecretRedactionFilter
                    .redact("{\"password\":\"abcdefgh\\\"" + following + "SURVIVING-TAIL\",\"n\":1}");

            assertFalse(result.contains("SURVIVING-TAIL"), result);
            assertEquals("{\"password\":\"<REDACTED>\",\"n\":1}", result);
            assertValidJson(result);
        }

        @Test
        void anEscapedJsonValueStillClosesAtItsOwnQuote() {
            // The other side of that ambiguity: the opening quote here IS escaped, so
            // the next quote with the same escaping is the terminator — and the one
            // after it belongs to the enclosing document, which must stay intact.
            String input = "{\"requestBody\": \"{\\\"llm\\\": {\\\"apiKey\\\": \\\"abcdefghijklmno\\\"}}\"}";
            String result = SecretRedactionFilter.redact(input);

            assertFalse(result.contains("abcdefghijklmno"), result);
            assertEquals("{\"requestBody\": \"{\\\"llm\\\": {\\\"apiKey\\\": \\\"<REDACTED>\\\"}}\"}", result);
            assertValidJson(result);
        }

        @Test
        void aPrettyPrintedNestedBodyKeepsItsStructureAndItsHint() {
            // The original approval-card shape: the inner document is pretty-printed
            // before being embedded, so its terminator is followed by an escaped
            // newline rather than a brace. A draft that read "what follows" ran past
            // it to the outer close, destroying the inner document and the sk-ant-
            // hint with it.
            String input = "{\"requestBody\": \"{\\n  \\\"llm\\\": {\\n    \\\"apiKey\\\": "
                    + "\\\"" + ANTHROPIC_KEY + "\\\"\\n  }\\n}\"}";
            String result = SecretRedactionFilter.redact(input);

            assertFalse(result.contains("CeIJ4onq59Mf"), result);
            assertEquals("{\"requestBody\": \"{\\n  \\\"llm\\\": {\\n    \\\"apiKey\\\": \\\"sk-ant-<REDACTED>\\\"\\n  }\\n}\"}",
                    result);
            assertValidJson(result);
        }

        @Test
        void aFreeTextValueClosesAtItsOwnQuoteNotALaterOne() {
            // A log line with two quoted strings. A draft that read "what follows"
            // found no value-ending character after the first closing quote and ran
            // on to the second, eating the host name in between.
            assertEquals("apiKey: \"<REDACTED>\" to host \"example.com\"",
                    SecretRedactionFilter.redact("apiKey: \"abcdefghij\" to host \"example.com\""));
        }
    }

    /**
     * A {@code ${vault:…}} reference is exempt because it is a pointer, not a
     * secret. The exemption has to be a WHOLE-VALUE match: a prefix check waves
     * through a secret wearing a pointer as a hat.
     */
    @Nested
    class TheVaultExemptionIsAWholeValueMatch {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "{\"password\":\"${vault:key}SURVIVING-TAIL\",\"n\":1}",
                "{\"password\":\"${vault:tenant/key}SURVIVING-TAIL\",\"n\":1}",
                "{\"password\":\"${eddivault:key}SURVIVING-TAIL\",\"n\":1}",
                "{\"password\":\"SURVIVING-TAIL${vault:key}\",\"n\":1}",
                "{\"password\":\"${vault:keySURVIVING-TAIL\",\"n\":1}"})
        void aReferenceWithATailIsASecret(String input) {
            // Found in review: the quoted scan used startsWith, and the two unquoted
            // rules stopped their value at the opening brace and so matched nothing.
            String result = SecretRedactionFilter.redact(input);

            assertFalse(result.contains("SURVIVING-TAIL"), result);
            assertEquals("{\"password\":\"<REDACTED>\",\"n\":1}", result);
        }

        @Test
        void aReferenceWithATailIsASecretInTheUnquotedRulesToo() {
            assertEquals("password: <REDACTED>",
                    SecretRedactionFilter.redact("password: ${vault:key}SURVIVING-TAIL"));
            assertEquals("{\"password\":\"<REDACTED>\"}",
                    SecretRedactionFilter.redact("{\"password\":${vault:key}SURVIVING-TAIL}"));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "{\"password\":\"${vault:key}\",\"n\":1}",
                "{\"password\":\"${vault:default/agent1/apiKey}\",\"n\":1}",
                "{\"password\":\"${eddivault:legacy-key}\",\"n\":1}",
                "password: ${vault:key}",
                "{\"password\":${vault:key}}"})
        void aWholeReferenceIsStillLeftLegible(String input) {
            assertEquals(input, SecretRedactionFilter.redact(input),
                    "the approver needs to see WHICH credential is used");
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
            // An opaque token rather than a JWT on purpose: the JWT shape trips the
            // repository's gitleaks scan whenever it lands on a newly added line,
            // and what this asserts — that the later rules leave an already-redacted
            // value alone — does not depend on the token's internal structure.
            // redact_bearerToken above still covers the JWT path.
            String input = "{\"Authorization\":\"Bearer not-a-real-token-000000000000\"}";
            assertEquals("{\"Authorization\":\"Bearer <REDACTED>\"}", SecretRedactionFilter.redact(input));
        }

        /**
         * The guard is anchored to the start of the value. Both cases below passed
         * through untouched while it asked whether the marker appeared ANYWHERE — which
         * reads as the more cautious rule and is the leakier one.
         */
        @Test
        void aValueMerelyCONTAININGARedactedFragmentIsStillRedacted() {
            String input = "{\"secret\":\"the key sk-ant-<REDACTED> is SURVIVING-TAIL\",\"n\":1}";
            String result = SecretRedactionFilter.redact(input);

            assertFalse(result.contains("SURVIVING-TAIL"), result);
            assertEquals("{\"secret\":\"<REDACTED>\",\"n\":1}", result);
        }

        /**
         * ...and the mirror of it. An earlier rule's own value class stops at a
         * delimiter, so it can leave a value only PARTLY redacted — treating a redacted
         * PREFIX as a redacted value publishes whatever follows.
         */
        @ParameterizedTest(name = "the sk-ant- rule stopped at [{0}]")
        @ValueSource(strings = {",", " ", ";", "}", "]"})
        void aPartiallyRedactedValueIsTakenOverAndReplacedWhole(String delimiter) {
            String input = "{\"apiKey\":\"sk-ant-abcdefghijklmnopqrst" + delimiter + "SURVIVING-TAIL\",\"n\":1}";
            String result = SecretRedactionFilter.redact(input);

            assertFalse(result.contains("SURVIVING-TAIL"), result);
            // The sk-ant- hint is lost here, deliberately: it is worth having, and
            // it is not worth publishing the tail to keep.
            assertEquals("{\"apiKey\":\"<REDACTED>\",\"n\":1}", result);
        }

        @Test
        void writingTheMarkerIntoYourOwnSecretIsNotABypass() {
            String result = SecretRedactionFilter.redact("{\"password\":\"my<REDACTED>SURVIVING-TAIL\",\"n\":1}");

            assertFalse(result.contains("SURVIVING-TAIL"), result);
            assertEquals("{\"password\":\"<REDACTED>\",\"n\":1}", result);
        }
    }
}
