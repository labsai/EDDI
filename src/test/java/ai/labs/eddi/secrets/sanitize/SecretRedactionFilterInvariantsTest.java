/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.sanitize;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Invariants {@link SecretRedactionFilter} must hold across a generated corpus,
 * rather than on the handful of shapes someone thought to write down.
 * <p>
 * The rule set is four regexes whose behaviour depends on the key's name, the
 * quote style, what the value contains and what follows it — a space of
 * combinations no example-based suite covers by hand. Every leak found while
 * building this fix was in a combination that looked covered: a delimiter
 * inside the value, the wrong quote closing it, an already-redacted prefix, an
 * escaped quote. Each was a cell in this matrix that nothing was checking.
 * <p>
 * The generated documents are built with Jackson and re-parsed after redaction,
 * so escaping is correct by construction and "still JSON" is a real assertion
 * rather than a string comparison.
 */
class SecretRedactionFilterInvariantsTest {

    /**
     * Planted in every secret. Its survival anywhere in the output is a leak — it
     * is the only thing these tests need to look for.
     */
    private static final String CANARY = "CANARY-SECRET-MATERIAL";

    /** A value under a key with no credential name in it, and its own canary. */
    private static final String INNOCENT = "claude-sonnet-5-INNOCENT-VALUE";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Every name the filter recognises, in the spellings it accepts. */
    private static final List<String> CREDENTIAL_KEYS = List.of(
            "apiKey", "api_key", "api-key", "APIKEY", "ApiKey",
            "token", "secret", "clientSecret", "password", "authorization", "x-api-key");

    /**
     * Value shapes. The delimiters are the ones the loose rule's class stops at,
     * which is where the tail used to survive; the quotes are what used to end the
     * value early; the prefixed ones are what an earlier rule leaves half-done.
     */
    private static List<Shape> valueShapes() {
        return List.of(
                new Shape("plain", "abcdefgh" + CANARY),
                new Shape("comma", "abcdefgh," + CANARY),
                new Shape("space", "abcdefgh " + CANARY),
                new Shape("semicolon", "abcdefgh;" + CANARY),
                new Shape("openBrace", "abcdefgh{" + CANARY),
                new Shape("closeBrace", "abcdefgh}" + CANARY),
                new Shape("openBracket", "abcdefgh[" + CANARY),
                new Shape("closeBracket", "abcdefgh]" + CANARY),
                new Shape("apostrophe", "abcdefgh'" + CANARY),
                new Shape("doubleQuote", "abcdefgh\"" + CANARY),
                new Shape("backslash", "abcdefgh\\" + CANARY),
                new Shape("tab", "abcdefgh\t" + CANARY),
                new Shape("newline", "abcdefgh\n" + CANARY),
                new Shape("colon", "abcdefgh:" + CANARY),
                new Shape("equals", "abcdefgh=" + CANARY),
                new Shape("markerInside", "abcdefgh<REDACTED>" + CANARY),
                new Shape("markerLeading", "<REDACTED>" + CANARY),
                new Shape("skAntLeading", "sk-ant-api03-abcdefghijklmnopqrst," + CANARY),
                new Shape("skLeading", "sk-abcdefghijklmnopqrstuvwx " + CANARY),
                new Shape("bearerLeading", "Bearer abcdefghij1234567890abcdef " + CANARY),
                new Shape("veryLong", "abcdefgh" + CANARY.repeat(20)));
    }

    /** Where the credential field sits in the document. */
    private static List<Placement> placements() {
        return List.of(
                new Placement("only", Placement::only),
                new Placement("first", Placement::first),
                new Placement("middle", Placement::middle),
                new Placement("last", Placement::last),
                new Placement("nested", Placement::nested),
                new Placement("inArray", Placement::inArray),
                new Placement("deeplyNested", Placement::deeplyNested));
    }

    static Stream<Arguments> everyCombination() {
        List<Arguments> cases = new ArrayList<>();
        for (String key : CREDENTIAL_KEYS) {
            for (Shape shape : valueShapes()) {
                for (Placement placement : placements()) {
                    cases.add(Arguments.of(key, shape, placement));
                }
            }
        }
        return cases.stream();
    }

    /**
     * The invariant that matters: a secret under a credential-named key does not
     * survive redaction, wherever it sits and whatever it contains — and the
     * document is still readable afterwards.
     */
    @ParameterizedTest(name = "{0} = {1} ({2})")
    @MethodSource("everyCombination")
    void aSecretNeverSurvives(String key, Shape shape, Placement placement) {
        String document = json(placement.build(key, shape.value()));

        String redacted = SecretRedactionFilter.redact(document);

        assertFalse(redacted.contains(CANARY),
                () -> "secret survived redaction\n  in:  " + document + "\n  out: " + redacted);
        assertDoesNotThrow(() -> MAPPER.readTree(redacted),
                () -> "redaction produced something that is no longer JSON: " + redacted);
        assertTrue(redacted.contains(INNOCENT) || !document.contains(INNOCENT),
                () -> "an unrelated field was destroyed: " + redacted);
        assertEquals(redacted, SecretRedactionFilter.redact(redacted),
                () -> "redaction is not idempotent: " + redacted);
    }

    /**
     * The same corpus with the credential name taken off the key. Without this the
     * suite above is satisfied by a filter that redacts everything, which would
     * pass every leak test and destroy every log line.
     */
    @ParameterizedTest(name = "modelName = {0}")
    @MethodSource("shapesWithoutACredentialPrefix")
    void anOrdinaryFieldIsLeftAlone(Shape shape) {
        String document = json(Map.of("modelName", shape.value(), "n", 1));

        assertEquals(document, SecretRedactionFilter.redact(document),
                "a field with no credential name must not be touched");
    }

    /**
     * The prefix rules (sk-…, Bearer …) fire on value shape alone, so a value
     * carrying one is redacted under ANY key — those shapes cannot take part in the
     * control above.
     */
    static Stream<Arguments> shapesWithoutACredentialPrefix() {
        return valueShapes().stream()
                .filter(s -> !s.value().startsWith("sk-") && !s.value().startsWith("Bearer "))
                .map(Arguments::of);
    }

    /**
     * A JSON document nested inside a string field, which is how a tool call's
     * requestBody argument arrives: every quote backslash-escaped. The filter has
     * to see through the escaping and put it back.
     */
    @ParameterizedTest(name = "escaped {0}")
    @MethodSource("everyCredentialKey")
    void anEscapedJsonBodySurvivesAsEscapedJson(String key) {
        String inner = json(Map.of(key, "abcdefgh," + CANARY, "modelName", INNOCENT));
        String document = json(Map.of("requestBody", inner));

        String redacted = SecretRedactionFilter.redact(document);

        assertFalse(redacted.contains(CANARY), () -> "secret survived: " + redacted);
        assertDoesNotThrow(() -> MAPPER.readTree(redacted), () -> "outer document broken: " + redacted);
        String innerAfter = assertDoesNotThrow(
                () -> MAPPER.readTree(redacted).get("requestBody").asText());
        assertDoesNotThrow(() -> MAPPER.readTree(innerAfter),
                () -> "the nested document was broken: " + innerAfter);
        assertTrue(innerAfter.contains(INNOCENT), () -> "nested sibling destroyed: " + innerAfter);
    }

    static Stream<Arguments> everyCredentialKey() {
        return CREDENTIAL_KEYS.stream().map(Arguments::of);
    }

    /** A vault reference is a pointer, not a secret, and must stay legible. */
    @ParameterizedTest(name = "{0} = a vault reference")
    @MethodSource("everyCredentialKey")
    void aVaultReferenceIsNeverRedacted(String key) {
        String reference = "${vault:default/agent1/" + key + "}";
        String document = json(Map.of(key, reference, "modelName", INNOCENT));

        String redacted = SecretRedactionFilter.redact(document);

        assertTrue(redacted.contains(reference),
                () -> "the approver needs to see WHICH credential is used: " + redacted);
        assertFalse(redacted.contains("<REDACTED>"),
                () -> "a correctly vault-referencing request must show no marker at all: " + redacted);
    }

    /** Non-JSON carriers: query strings and log lines. */
    @ParameterizedTest(name = "{0} in a query string")
    @MethodSource("everyCredentialKey")
    void aQueryStringValueIsRedactedWithoutInventingQuotes(String key) {
        String redacted = SecretRedactionFilter.redact("https://x/y?" + key + "=abcdefgh" + CANARY);

        assertFalse(redacted.contains(CANARY), redacted);
        assertEquals("https://x/y?" + key + "=<REDACTED>", redacted);
    }

    @ParameterizedTest(name = "{0} in a log line")
    @MethodSource("everyCredentialKey")
    void aLogLineValueIsRedactedWithoutInventingQuotes(String key) {
        String redacted = SecretRedactionFilter.redact(key + ": abcdefgh" + CANARY);

        assertFalse(redacted.contains(CANARY), redacted);
        assertEquals(key + ": <REDACTED>", redacted);
    }

    /**
     * A body cut short mid-value — the preview cap does this — has no closing
     * quote, so the quoted rule cannot fire and the loose rule has to catch it. A
     * truncated body is exactly when a leak goes unnoticed.
     */
    @ParameterizedTest(name = "truncated after {0}")
    @MethodSource("everyCredentialKey")
    void aTruncatedBodyIsStillRedacted(String key) {
        String redacted = SecretRedactionFilter.redact("{\"" + key + "\": \"abcdefgh" + CANARY);

        assertFalse(redacted.contains(CANARY), redacted);
    }

    /**
     * Adversarial input, bounded time. The filter runs on attacker-influenced
     * request bodies, and the rule set carries possessive quantifiers precisely so
     * that a crafted input cannot make it backtrack — one lazy quantifier is the
     * exception, so it gets measured rather than argued about.
     */
    @Test
    void adversarialInputDoesNotBlowUp() {
        List<String> attacks = List.of(
                "{\"apiKey\":\"" + "\\\"".repeat(20_000) + "\"}",
                "{\"apiKey\":\"" + "a".repeat(200_000) + "\"}",
                "{\"apiKey\":\"" + "${vault:".repeat(20_000) + "\"}",
                "\"apiKey\":\"".repeat(20_000),
                "{\"apiKey\":" + "[".repeat(50_000),
                "apiKey=" + "sk-ant-".repeat(20_000));

        for (String attack : attacks) {
            long started = System.nanoTime();
            assertDoesNotThrow(() -> SecretRedactionFilter.redact(attack));
            long millis = (System.nanoTime() - started) / 1_000_000;
            assertTrue(millis < 5_000,
                    () -> "redaction took " + millis + "ms on a " + attack.length() + "-char input");
        }
    }

    /**
     * Coverage-guided fuzzing over arbitrary input. Runs as an ordinary JUnit test
     * against the saved corpus in CI; run with the Jazzer agent for real fuzzing:
     * {@code mvn test -Dtest=SecretRedactionFilterInvariantsTest
     * -Djazzer.instrument=ai.labs.eddi.secrets.sanitize.SecretRedactionFilter}
     * <p>
     * Idempotency is the property worth asserting on input nobody designed: a
     * second pass changing anything means a rule is re-matching its own output,
     * which is how a redacted prefix gets stripped or a marker gets nested.
     */
    @FuzzTest(maxDuration = "60s")
    void fuzzRedactIsSafeAndIdempotent(FuzzedDataProvider data) {
        String input = data.consumeRemainingAsString();

        String once = SecretRedactionFilter.redact(input);
        assertNotNull(once);
        assertEquals(once, SecretRedactionFilter.redact(once),
                "redaction must be idempotent");
    }

    // ── helpers ──────────────────────────────────────────────────

    /** A value shape, named so a failure says which one broke. */
    record Shape(String name, String value) {
        @Override
        public String toString() {
            return name;
        }
    }

    /** Where in a document the credential field sits. */
    record Placement(String name, Builder builder) {
        interface Builder {
            Map<String, Object> build(String key, String value);
        }

        Map<String, Object> build(String key, String value) {
            return builder.build(key, value);
        }

        @Override
        public String toString() {
            return name;
        }

        static Map<String, Object> only(String key, String value) {
            return ordered(entry(key, value));
        }

        static Map<String, Object> first(String key, String value) {
            return ordered(entry(key, value), entry("modelName", INNOCENT), entry("n", 1));
        }

        static Map<String, Object> middle(String key, String value) {
            return ordered(entry("modelName", INNOCENT), entry(key, value), entry("n", 1));
        }

        static Map<String, Object> last(String key, String value) {
            return ordered(entry("modelName", INNOCENT), entry("n", 1), entry(key, value));
        }

        static Map<String, Object> nested(String key, String value) {
            return ordered(entry("llm", ordered(entry(key, value), entry("modelName", INNOCENT))),
                    entry("n", 1));
        }

        static Map<String, Object> inArray(String key, String value) {
            return ordered(entry("items", List.of(ordered(entry(key, value)), ordered(entry("modelName", INNOCENT)))),
                    entry("n", 1));
        }

        static Map<String, Object> deeplyNested(String key, String value) {
            return ordered(entry("a", ordered(entry("b", ordered(entry("c",
                    ordered(entry(key, value), entry("modelName", INNOCENT))))))));
        }
    }

    private static Map.Entry<String, Object> entry(String key, Object value) {
        return Map.entry(key, value);
    }

    @SafeVarargs
    private static Map<String, Object> ordered(Map.Entry<String, Object>... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : entries) {
            map.put(e.getKey(), e.getValue());
        }
        return map;
    }

    /** Serialise with Jackson so escaping is correct by construction. */
    private static String json(Object value) {
        return assertDoesNotThrow(() -> MAPPER.writeValueAsString(value));
    }
}
