/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.sanitize;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gaps in the redaction layer found by the 2026-10 external review, each pinned
 * where it was: a credential that reached a reader as written, or an input
 * shape that made a scan quadratic.
 */
class RedactionReviewFindingsTest {

    @Nested
    @DisplayName("ConfigSecretRedactor")
    class ConfigRedactor {

        @Test
        @DisplayName("a header map stored as a JSON string (customHeaders) has its credential entries redacted, the rest kept")
        void embeddedHeaderMap() {
            String judged = ConfigSecretRedactor.judge("customHeaders", "parameters",
                    "{\"Authorization\":\"Bearer sk-live-abcdefghijklmnop\",\"X-Trace\":\"on\"}");

            assertNotNull(judged, "the literal Authorization value used to be returned unchanged");
            assertFalse(judged.contains("sk-live-abcdefghijklmnop"), judged);
            assertTrue(judged.contains("\"X-Trace\":\"on\""), judged);
        }

        @Test
        @DisplayName("an embedded map that only references the vault is left as written")
        void embeddedHeaderMapWithReference() {
            assertNull(ConfigSecretRedactor.judge("customHeaders", "parameters", "{\"Authorization\":\"Bearer ${vault:k}\"}"));
        }

        @Test
        @DisplayName("an embedded query map (customQueryParams) is judged by its keys too")
        void embeddedQueryMap() {
            String judged = ConfigSecretRedactor.judge("customQueryParams", "parameters", "{\"api_key\":\"hunter2\"}");

            assertNotNull(judged);
            assertFalse(judged.contains("hunter2"), judged);
        }

        @Test
        @DisplayName("a credential made only of symbols or non-ASCII letters, or one equal to a scheme word, is a literal")
        void literalsWithNoAsciiWord() {
            assertTrue(ConfigSecretRedactor.isLiteral("§§§§!!"));
            assertTrue(ConfigSecretRedactor.isLiteral("пароль"));
            assertTrue(ConfigSecretRedactor.isLiteral("token"));
            assertTrue(ConfigSecretRedactor.isLiteral("${vault:k}€"));
        }

        @Test
        @DisplayName("scheme words and separators beside a reference are still scaffolding")
        void scaffoldingBesideReference() {
            assertFalse(ConfigSecretRedactor.isLiteral("Bearer ${vault:k}"));
            assertFalse(ConfigSecretRedactor.isLiteral("Basic ${vault:user}:${vault:pass}"));
            assertFalse(ConfigSecretRedactor.isLiteral("{properties.apiKey}"));
        }

        @Test
        @DisplayName("a percent-encoded credential query name is judged decoded")
        void encodedQueryName() {
            String redacted = ConfigSecretRedactor.redactUrl("https://api.example.com/v1?%61pi%5Fkey=hunter2&page=2");

            assertFalse(redacted.contains("hunter2"), redacted);
            assertTrue(redacted.contains("page=2"), redacted);
        }
    }

    @Nested
    @DisplayName("UriRedactor")
    class Uri {

        @Test
        @DisplayName("cookie, signature and session names are credentials; words merely containing 'sig' are not")
        void cookieSignatureSession() {
            assertTrue(UriRedactor.isSensitiveHeaderName("Cookie"));
            assertTrue(UriRedactor.isSensitiveHeaderName("X-Amz-Signature"));
            assertTrue(UriRedactor.isSensitiveHeaderName("sig"));
            assertTrue(UriRedactor.isSensitiveHeaderName("X-Session-Id"));
            assertFalse(UriRedactor.isSensitiveHeaderName("signal"));
            assertFalse(UriRedactor.isSensitiveHeaderName("design"));
        }

        @Test
        @DisplayName("a percent-encoded credential query name is judged decoded")
        void encodedQueryName() {
            String redacted = UriRedactor.redactUri("https://api.example.com/v1?%61pi%5Fkey=hunter2");

            assertFalse(redacted.contains("hunter2"), redacted);
            assertTrue(redacted.startsWith("https://api.example.com/v1?%61pi%5Fkey="), "the name is emitted as written: " + redacted);
        }
    }

    @Nested
    @DisplayName("SecretScrubber")
    class Scrubber {

        @Test
        @DisplayName("a percent-encoded credential query name is scrubbed on export")
        void encodedQueryName() throws Exception {
            String scrubbed = new SecretScrubber(new ObjectMapper())
                    .scrubJson("{\"targetServerUrl\": \"https://api.example.com/v1?%61pi%5Fkey=hunter2&page=2\"}");

            assertFalse(scrubbed.contains("hunter2"), scrubbed);
            assertTrue(scrubbed.contains("page=2"), scrubbed);
        }

        @Test
        @DisplayName("every masked template expression is restored, in one pass, around what was redacted")
        void masksRestored() {
            String text = "{a} {b} token: sk-live-abcdefghijklmnopqrstuvwx {properties.c}";

            String redacted = SecretScrubber.redactFreeText(text);

            assertNotNull(redacted);
            assertTrue(redacted.startsWith("{a} {b} token: "), redacted);
            assertTrue(redacted.endsWith(" {properties.c}"), redacted);
            assertFalse(redacted.contains("sk-live-abcdefghijklmnopqrstuvwx"), redacted);
        }

        @Test
        @DisplayName("mask-shaped input with a digit run too long for an int is left as it was, not a crash")
        void oversizedMaskIndexInInput() {
            String forged = "" + "9".repeat(40) + "";
            String text = forged + " token: sk-live-abcdefghijklmnopqrstuvwx";

            String redacted = assertDoesNotThrow(() -> SecretScrubber.redactFreeText(text));

            assertNotNull(redacted);
            assertTrue(redacted.startsWith(forged), redacted);
            assertFalse(redacted.contains("sk-live-abcdefghijklmnopqrstuvwx"), redacted);
        }

        @Test
        @DisplayName("many template expressions and many unterminated vault openers finish in linear time")
        void adversarialInputsAreLinear() {
            String expressions = "{a} ".repeat(250_000) + "token: sk-live-abcdefghijklmnopqrstuvwx";
            String openers = "${vault:".repeat(150_000) + " sk-live-abcdefghijklmnopqrstuvwx";
            SecretScrubber scrubber = new SecretScrubber(new ObjectMapper());

            assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
                assertNotNull(SecretScrubber.redactFreeText(expressions));
                assertDoesNotThrow(() -> scrubber.scrubJson(new ObjectMapper().writeValueAsString(Map.of("apiKey", openers))));
            });
        }
    }

    @Nested
    @DisplayName("SecretRedactionFilter")
    class Filter {

        @Test
        @DisplayName("a connection string with an empty username still has its password redacted")
        void emptyUsername() {
            String redacted = SecretRedactionFilter.redact("connecting to redis://:hunter2secret@cache.internal:6379");

            assertFalse(redacted.contains("hunter2secret"), redacted);
            assertTrue(redacted.contains("@cache.internal:6379"), redacted);
        }

        @Test
        @DisplayName("a comma inside a connection-string password does not leave its tail legible")
        void commaInPassword() {
            String redacted = SecretRedactionFilter.redact("mongodb://eddi:pass,word-tail@db1:27017,db2:27017/eddi");

            assertFalse(redacted.contains("word-tail"), redacted);
            assertTrue(redacted.contains("@db1:27017,db2:27017/eddi"), redacted);
        }

        @Test
        @DisplayName("Digest directives with whitespace around '=' are still redacted")
        void digestWithWhitespace() {
            String redacted = SecretRedactionFilter
                    .redact("Authorization: Digest username = \"alice\", response = \"6629fae49393a05397450978507c4ef1\"");

            assertFalse(redacted.contains("6629fae49393a05397450978507c4ef1"), redacted);
        }

        @Test
        @DisplayName("a run of 'password:${vault:' with no closing brace is linear, not quadratic")
        void unterminatedReferenceRunIsLinear() {
            String attack = "password:${vault:".repeat(60_000);

            assertTimeoutPreemptively(Duration.ofSeconds(20), () -> assertDoesNotThrow(() -> SecretRedactionFilter.redact(attack)));
        }

        @Test
        @DisplayName("a reference followed by a tail is still redacted as a whole")
        void referencePlusTailStillRedacted() {
            assertEquals("password: " + SecretRedactionFilter.REDACTED, SecretRedactionFilter.redact("password: ${vault:key}SECRETTAIL"));
        }
    }

    /** The 2026-11 agent-findings pass over the secrets and connections area. */
    @Nested
    @DisplayName("agent findings, 2026-11")
    class AgentFindings {

        @Test
        @DisplayName("a numeric credential in an embedded header map is redacted, not skipped as a non-string")
        void numericEmbeddedCredential() {
            String judged = ConfigSecretRedactor.judge("customHeaders", "parameters", "{\"X-Api-Key\":12345678,\"X-Trace\":1}");

            assertNotNull(judged, "the request builder sends the number as the header value");
            assertFalse(judged.contains("12345678"), judged);
            assertTrue(judged.contains("\"X-Trace\":1"), judged);
        }

        @Test
        @DisplayName("a short literal under a credential name in a JSON body is masked, whatever its length")
        void shortCredentialInJsonBody() {
            String judged = ConfigSecretRedactor.judge("body", "request",
                    "{\"user\":\"alice\",\"password\":\"hunter2\",\"auth\":{\"token\":\"abc\"},\"ref\":\"${vault:k}\",\"tpl\":\"{properties.x}\"}");

            assertNotNull(judged);
            assertFalse(judged.contains("hunter2"), judged);
            assertFalse(judged.contains("\"abc\""), judged);
            assertTrue(judged.contains("\"user\":\"alice\""), judged);
            assertTrue(judged.contains("${vault:k}") && judged.contains("{properties.x}"), judged);
        }

        @Test
        @DisplayName("a body that is more than one JSON document is never cut down to the first")
        void bodyWithTrailingContentIsNotTruncated() {
            String judged = ConfigSecretRedactor.judge("body", "request", "{\"password\":\"hunter2\"} {\"b\":1}");

            assertTrue(judged == null || judged.contains("{\"b\":1}"), judged);
        }

        @Test
        @DisplayName("a long non-JSON message keeps an apostrophe password whole across an embedded double quote")
        void longNonJsonApostropheValue() {
            String message = "x".repeat((1 << 20) + 16) + " password='pa\"ssword-tail-secret' done";

            String redacted = SecretRedactionFilter.redact(message);

            assertFalse(redacted.contains("ssword-tail-secret"), "the size bound may only apply to text that could be JSON");
            assertTrue(redacted.endsWith(" done"), redacted.substring(redacted.length() - 40));
        }
    }
}
