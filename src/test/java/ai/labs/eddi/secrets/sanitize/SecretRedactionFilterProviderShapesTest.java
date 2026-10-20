/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.sanitize;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L6 of the 2026-10-17 security review: the free-text filter backs logs, dead
 * letters, the audit scrub and HITL approval cards, and knew only OpenAI and
 * Anthropic keys and bearer tokens. These are the other shapes it now
 * recognises.
 */
class SecretRedactionFilterProviderShapesTest {

    // {"alg":"x"}.{"sub":"1"}.<signature>: a real token shape, kept short so
    // that no secret scanner mistakes the fixture for a leaked credential.
    private static final String JWT = "eyJhbGciOiJ4In0.eyJzdWIiOiIxIn0.fixturesig";

    static Stream<Arguments> secrets() {
        return Stream.of(
                Arguments.of("Google API key", "AIzaSyfixturefixturefixturefixturefixture", "fixturefixturefixturefixturefixture"),
                Arguments.of("xAI key", "xai-fixturefixturefixturefixture", "fixturefixturefixturefixture"),
                Arguments.of("Groq key", "gsk_fixturefixturefixturefixture", "fixturefixturefixturefixture"),
                Arguments.of("GitHub classic token", "ghp_fixturefixturefixturefixtureabcd", "fixturefixturefixturefixtureabcd"),
                Arguments.of("GitHub fine-grained token", "github_pat_11FIXTURE0123456789_fixturefixture", "11FIXTURE0123456789_fixturefixture"),
                Arguments.of("Slack bot token", "xoxb-fake-bot-token-for-this-test", "bot-token-for-this-test"),
                Arguments.of("Slack app token", "xoxa-2-fake-app-token-fixture", "fake-app-token-fixture"),
                Arguments.of("AWS access key id", "AKIAIOSFODNN7EXAMPLE", "IOSFODNN7EXAMPLE"),
                Arguments.of("bare JWT", JWT, "eyJzdWIiOiIxIn0.fixturesig"),
                Arguments.of("Basic authorization", "Authorization: Basic dXNlcjpodW50ZXIy", "dXNlcjpodW50ZXIy"),
                Arguments.of("Basic authorization, four-character credential", "Authorization: Basic YTpi", "YTpi"),
                Arguments.of("Token authorization", "authorization=token 0123456789abcdef", "0123456789abcdef"),
                Arguments.of("Azure OpenAI api-key header", "api-key: fixturefixturefixturefixture", "fixturefixturefixturefixture"),
                Arguments.of("APIM subscription key header", "Ocp-Apim-Subscription-Key: fixturefixturefixture", "fixturefixturefixture"),
                Arguments.of("MongoDB userinfo password", "mongodb+srv://eddi:hunter2pw@cluster0.example.net/eddi", "hunter2pw"),
                Arguments.of("Postgres userinfo password", "postgresql://eddi:s3cr3t@db:5432/eddi", "s3cr3t"),
                Arguments.of("a password longer than any ceiling", "postgresql://eddi:" + "p".repeat(300) + "@db:5432/eddi", "p".repeat(300)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("secrets")
    @DisplayName("each provider shape is redacted inside a log line")
    void redacted(String what, String secret, String mustVanish) {
        String result = SecretRedactionFilter.redact("calling upstream with " + secret + " now");

        assertFalse(result.contains(mustVanish), what + ": " + result);
        assertTrue(result.contains(SecretRedactionFilter.REDACTED), what + ": " + result);
        assertEquals(result, SecretRedactionFilter.redact(result), what + " is idempotent");
    }

    @Test
    @DisplayName("the host of a connection string stays legible")
    void connectionStringHostSurvives() {
        assertEquals("mongodb://eddi:<REDACTED>@db.internal:27017/eddi", SecretRedactionFilter.redact("mongodb://eddi:pw123@db.internal:27017/eddi"));
    }

    @Test
    @DisplayName("a raw @ inside the password (malformed, but logged as it came) does not end the redaction early")
    void malformedUserinfoWithRawAt() {
        assertEquals("mongodb://u:<REDACTED>@host/db", SecretRedactionFilter.redact("mongodb://u:abc@tail:pw@host/db"));
        assertEquals("{\"uri\":\"mongodb://u:<REDACTED>@host\",\"next\":\"x@y\"}",
                SecretRedactionFilter.redact("{\"uri\":\"mongodb://u:abc@tail:pw@host\",\"next\":\"x@y\"}"),
                "the carrier's closing quote ends the walk; the next field's @ is not the password's");
        assertEquals("postgresql://u:<REDACTED>@h/db and mongodb://v:<REDACTED>@k",
                SecretRedactionFilter.redact("postgresql://u:p@h/db and mongodb://v:q@k"),
                "two connection strings are two passwords");
    }

    @Test
    @DisplayName("a quote escaped inside a JSON-carried Digest value does not end the credential early")
    void digestJsonInteriorEscapedQuote() throws Exception {
        String header = "Digest username=\"al\\\"ice\", realm=\"eddi\", response=\"fixtureresponsehash\", algorithm=MD5";
        String json = new ObjectMapper().writeValueAsString(Map.of("Authorization", header, "next", "x@y"));

        String result = SecretRedactionFilter.redact(json);

        var tree = new ObjectMapper().readTree(result);
        assertFalse(result.contains("fixtureresponsehash"), result);
        assertTrue(tree.get("Authorization").asText().contains(SecretRedactionFilter.REDACTED), result);
        assertEquals("x@y", tree.get("next").asText(), result);
        assertEquals(result, SecretRedactionFilter.redact(result), "idempotent");
    }

    @ParameterizedTest
    @ValueSource(strings = {"mongodb://eddi:${vault:mongo-pw}@db/eddi", "Ocp-Apim-Subscription-Key: ${vault:apim-key}",
            "Authorization: Basic ${vault:basic}", "AKIAIOSFODNN7EXAMPLEX is not an access key id", "the AIza prefix alone",
            "xoxo-hugs-and-kisses-to-everyone", "eyJhbGciOiJIUzI1NiJ9 alone is a header, not a token"})
    @DisplayName("references and near-misses are left alone")
    void leftAlone(String text) {
        assertEquals(text, SecretRedactionFilter.redact(text));
    }

    @Test
    @DisplayName("a Digest credential loses its whole directive list, in a log line and inside a JSON carrier")
    void digestDirectives() throws Exception {
        String header = "Digest username=\"alice\", realm=\"eddi\", nonce=\"fixturenonce\", uri=\"/api\", "
                + "response=\"fixtureresponsehash\", algorithm=MD5";

        assertEquals("Authorization: Digest " + SecretRedactionFilter.REDACTED + " sent upstream",
                SecretRedactionFilter.redact("Authorization: " + header + " sent upstream"));

        String json = "{\"Authorization\":\"" + header.replace("\"", "\\\"") + "\",\"next\":\"x@y\"}";
        String result = SecretRedactionFilter.redact(json);
        var tree = new ObjectMapper().readTree(result);
        assertFalse(result.contains("fixtureresponsehash"), result);
        assertFalse(result.contains("alice"), result);
        assertTrue(tree.get("Authorization").asText().contains(SecretRedactionFilter.REDACTED), result);
        assertEquals("x@y", tree.get("next").asText(), "the next field is not part of the credential");
        assertEquals(result, SecretRedactionFilter.redact(result), "idempotent");
    }

    @Test
    @DisplayName("a bare Basic credential is redacted when it decodes to user:password; the word in prose is not")
    void bareBasic() {
        String credential = Base64.getEncoder().encodeToString("alice:fixture-password".getBytes(StandardCharsets.UTF_8));

        assertEquals("header was Basic " + SecretRedactionFilter.REDACTED + " and then",
                SecretRedactionFilter.redact("header was Basic " + credential + " and then"));
        assertEquals("Basic configuration is enough", SecretRedactionFilter.redact("Basic configuration is enough"));
        assertEquals("Basic 12345678 is a number", SecretRedactionFilter.redact("Basic 12345678 is a number"));
    }

    @Test
    @DisplayName("a JSON carrier stays one JSON document")
    void jsonStaysJson() throws Exception {
        String json = "{\"uri\":\"mongodb://u:pw12345@h/db\",\"next\":\"x@y\",\"Ocp-Apim-Subscription-Key\":\"fixturefixturefixture\","
                + "\"Authorization\":\"Basic dXNlcjpwYXNzd29yZA==\",\"k\":\"AIzaSyfixturefixturefixturefixturefixture\"}";

        String result = SecretRedactionFilter.redact(json);

        var tree = new ObjectMapper().readTree(result);
        assertEquals("x@y", tree.get("next").asText(), result);
        assertFalse(result.contains("pw12345"), result);
        assertFalse(result.contains("fixturefixturefixture"), result);
        assertFalse(result.contains("dXNlcjpwYXNzd29yZA"), result);
        assertFalse(result.contains("AIzaSy"), result);
    }

    /**
     * The same bounded-time check the existing rules carry: every new pattern is a
     * literal prefix plus one possessive class, so these finish in milliseconds; a
     * backtracking one would not finish at all.
     */
    @Test
    @DisplayName("adversarial input for the new shapes stays linear")
    void adversarialInputDoesNotBlowUp() {
        List<String> attacks = List.of("AIza".repeat(50_000), "AIza" + "a".repeat(200_000) + "!", "xai-".repeat(50_000),
                "gh" + "p_".repeat(50_000), "xoxb-".repeat(40_000), "AKIA".repeat(50_000), "eyJ".repeat(60_000),
                "eyJ" + "a".repeat(100_000) + ".eyJ" + "b".repeat(100_000), "Authorization: Basic ".repeat(20_000),
                "authorization: basic " + " ".repeat(200_000), "Subscription-Key: ".repeat(20_000),
                "mongodb://" + "u".repeat(300) + ":".repeat(50_000), "mongodb://".repeat(30_000), "mongodb://u:" + "p".repeat(200_000),
                ("postgresql://u:p" + "@".repeat(10)).repeat(10_000), "Authorization: Digest " + "a=b,".repeat(100_000),
                "Authorization: Digest a=\"" + "x".repeat(200_000), "Authorization: Digest a=\\\"" + "x".repeat(200_000),
                "Authorization: Digest a=b ".repeat(20_000), "Basic " + "A".repeat(200_000), "Basic ".repeat(50_000),
                "mongodb://u:" + "a@".repeat(100_000) + "h", "mongodb://u:".repeat(30_000), "mongodb://u:" + "@".repeat(200_000),
                "Authorization: Digest a=\\\"" + "\\\\\\\"".repeat(50_000));

        for (String attack : attacks) {
            assertTimeoutPreemptively(Duration.ofSeconds(30), () -> assertDoesNotThrow(() -> SecretRedactionFilter.redact(attack)),
                    () -> "redaction did not finish on a " + attack.length() + "-char input starting " + attack.substring(0, 20));
        }
    }
}
