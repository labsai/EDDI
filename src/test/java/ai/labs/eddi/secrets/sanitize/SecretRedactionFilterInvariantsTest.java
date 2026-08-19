/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.sanitize;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.labs.eddi.secrets.model.SecretReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
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
 * The filter's behaviour depends on the key's name, the quote style, what the
 * value contains, how deeply it is nested and what carries it — a space of
 * combinations no example-based suite covers by hand. Every leak found while
 * building this fix was in a combination that looked covered: a delimiter
 * inside the value, the wrong quote closing it, an already-redacted prefix, an
 * escaped quote, an escaped quote followed by a comma, a vault reference with a
 * tail. Each was a cell in this matrix that nothing was checking.
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
                // An escaped quote followed by each JSON delimiter: the shape that
                // fooled a "what follows the quote" terminator rule.
                new Shape("quoteComma", "abcdefgh\"," + CANARY),
                new Shape("quoteBrace", "abcdefgh\"}" + CANARY),
                new Shape("quoteBracket", "abcdefgh\"]" + CANARY),
                new Shape("quoteSpaceComma", "abcdefgh\" ," + CANARY),
                // The value ends in a backslash, so its closing quote is preceded by
                // an escaped one — even count, still the terminator.
                new Shape("trailingBackslash", "abcdefgh" + CANARY + "\\"),
                new Shape("trailingTwoBackslashes", "abcdefgh" + CANARY + "\\\\"),
                // A vault reference with a tail is a secret wearing a pointer as a hat.
                new Shape("vaultPrefixed", "${vault:key}" + CANARY),
                new Shape("vaultTenantPrefixed", "${vault:tenant/key}" + CANARY),
                new Shape("legacyVaultPrefixed", "${eddivault:key}" + CANARY),
                new Shape("vaultSuffixed", CANARY + "${vault:key}"),
                new Shape("unterminatedVaultRef", "${vault:key" + CANARY),
                new Shape("unicode", "abcdefghüñí€" + CANARY),
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
                new Placement("deeplyNested", Placement::deeplyNested),
                new Placement("amongOtherCredentials", Placement::amongOtherCredentials));
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
        String document = json(ordered(entry("modelName", shape.value()), entry("n", 1)));

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
     * to see through the escaping, redact, and put the escaping back — and the
     * inner document has to come out of it still parseable, with its other fields
     * intact.
     * <p>
     * Four carriers: the document itself, nested once compact, nested once
     * pretty-printed (whose {@code \r\n} after the inner terminator is what broke a
     * "what follows the quote" rule — the original approval-card shape), and nested
     * TWICE, where every quote of the innermost document wears three backslashes.
     */
    @ParameterizedTest(name = "{0} = {1}, carried {2}")
    @MethodSource("everyKeyShapeAndCarrier")
    void aSecretNeverSurvivesInAnyCarrier(String key, Shape shape, Carrier carrier) {
        String document = carrier.wrap(ordered(entry(key, shape.value()), entry("modelName", INNOCENT)));

        String redacted = SecretRedactionFilter.redact(document);

        assertFalse(redacted.contains(CANARY), () -> "secret survived: " + redacted);
        String innermost = carrier.unwrap(redacted);
        assertTrue(innermost.contains(INNOCENT), () -> "nested sibling destroyed: " + innermost);
        assertEquals(redacted, SecretRedactionFilter.redact(redacted),
                () -> "redaction is not idempotent: " + redacted);
    }

    static Stream<Arguments> everyKeyShapeAndCarrier() {
        List<Arguments> cases = new ArrayList<>();
        for (String key : CREDENTIAL_KEYS) {
            for (Shape shape : valueShapes()) {
                for (Carrier carrier : Carrier.values()) {
                    cases.add(Arguments.of(key, shape, carrier));
                }
            }
        }
        return cases.stream();
    }

    static Stream<Arguments> everyCredentialKey() {
        return CREDENTIAL_KEYS.stream().map(Arguments::of);
    }

    /**
     * The {@code sk-ant-} hint an earlier rule leaves is information an approver
     * uses, and it must survive the quoted scan in every carrier — not only at the
     * top level. The pretty-printed nested body lost it in an earlier draft.
     */
    @ParameterizedTest(name = "sk-ant- hint survives when carried {0}")
    @MethodSource("everyCarrier")
    void anAlreadyRedactedPrefixSurvivesInEveryCarrier(Carrier carrier) {
        String document = carrier.wrap(ordered(
                entry("apiKey", "sk-ant-api03-CeIJ4onq59Mf_oN4mICgfgScyJO5bfxFSS3Sdvo1Zgo2F7zUfEvx"),
                entry("modelName", INNOCENT)));

        String redacted = SecretRedactionFilter.redact(document);
        String innermost = carrier.unwrap(redacted);

        assertFalse(innermost.contains("CeIJ4onq59Mf"), innermost);
        assertTrue(innermost.contains("sk-ant-<REDACTED>"),
                () -> "the kind-of-credential hint was lost: " + innermost);
        assertTrue(innermost.contains(INNOCENT), innermost);
    }

    static Stream<Arguments> everyCarrier() {
        return Stream.of(Carrier.values()).map(Arguments::of);
    }

    /** A vault reference is a pointer, not a secret, and must stay legible. */
    @ParameterizedTest(name = "{0} = a vault reference")
    @MethodSource("everyCredentialKey")
    void aVaultReferenceIsNeverRedacted(String key) {
        String reference = "${vault:default/agent1/" + key + "}";
        String document = json(ordered(entry(key, reference), entry("modelName", INNOCENT)));

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
     * quote. Everything after the opening quote IS the secret, and it is redacted
     * to the end of the input: the earlier fallback to the loose rule stopped at
     * the first space and left the rest of a multi-word secret in the output. A
     * truncated body is exactly when a leak goes unnoticed.
     */
    @ParameterizedTest(name = "truncated {0} = {1}")
    @MethodSource("everyKeyAndShape")
    void aTruncatedBodyIsRedactedToTheEnd(String key, Shape shape) {
        // Cut the serialised document off just before the value's closing quote.
        String whole = json(ordered(entry("modelName", INNOCENT), entry(key, shape.value())));
        String truncated = whole.substring(0, whole.lastIndexOf('"'));

        String redacted = SecretRedactionFilter.redact(truncated);

        assertFalse(redacted.contains(CANARY), () -> "secret survived truncation\n  in:  " + truncated + "\n  out: " + redacted);
        assertTrue(redacted.contains(INNOCENT), redacted);
    }

    static Stream<Arguments> everyKeyAndShape() {
        List<Arguments> cases = new ArrayList<>();
        for (String key : CREDENTIAL_KEYS) {
            for (Shape shape : valueShapes()) {
                cases.add(Arguments.of(key, shape));
            }
        }
        return cases.stream();
    }

    /**
     * Free text — a log line — closes a quoted value at its own quote, not at some
     * later quote on the line. An earlier draft decided the terminator by what
     * FOLLOWED it, and in free text that ate everything up to the next quoted
     * string: the response body, the host name, and the {@code sk-ant-} hint with
     * them. Over-redaction is the safe failure, but it is still a failure when it
     * is the log line someone is debugging from.
     */
    @Test
    void aFreeTextValueClosesAtItsOwnQuoteNotALaterOne() {
        assertEquals("apiKey: \"<REDACTED>\" to host \"example.com\"",
                SecretRedactionFilter.redact("apiKey: \"abcdefgh" + CANARY + "\" to host \"example.com\""));

        String logLine = "call failed with apiKey: \"sk-ant-api03-CeIJ4onq59Mf_oN4mICgfgScyJO5bfxFSS3Sdvo1Zgo2F7zUfEvx\""
                + " -- response: \"401 Unauthorized\"";
        assertEquals("call failed with apiKey: \"sk-ant-<REDACTED>\" -- response: \"401 Unauthorized\"",
                SecretRedactionFilter.redact(logLine));
    }

    /**
     * A credential name inside some OTHER field's value, itself quoting a secret —
     * {@code "note":"use token: \"…\" please"} — is redacted without eating the
     * words around it.
     */
    @Test
    void aQuotedSecretInsideAnotherFieldsValueIsRedactedInPlace() {
        String document = json(ordered(entry("note", "use token: \"abcdefgh" + CANARY + "\" please"), entry("n", 1)));

        String redacted = SecretRedactionFilter.redact(document);

        assertFalse(redacted.contains(CANARY), redacted);
        assertEquals(json(ordered(entry("note", "use token: \"<REDACTED>\" please"), entry("n", 1))), redacted);
    }

    /**
     * An apostrophe-quoted value ends at the first double quote, whatever its
     * escaping — so a Python-style repr of a password with a double quote in it is
     * cut at that quote. Pinned as a DELIBERATE limit, with the reasoning:
     * <ul>
     * <li>the rule this scan replaced stopped at a double quote too, so this is the
     * status quo on that leak, not a regression;
     * <li>letting an apostrophe value run past a double quote, inside JSON, let it
     * open in one string and close in another at a different nesting level, eating
     * the brace between — a carrier the Manager's escalation checks then skip;
     * <li>and it is what keeps redaction idempotent: a pass never removes a quote.
     * </ul>
     * An apostrophe value with no double quote in it — the overwhelmingly common
     * Python and shell shape — is redacted whole, as the next test shows.
     */
    @Test
    void anApostropheQuotedValueStopsAtADoubleQuote() {
        String redacted = SecretRedactionFilter
                .redact("{'user': 'x', 'password': 'pa\"ss-" + CANARY + "\", done', 'n': 1}");

        assertEquals("{'user': 'x', 'password': 'pa\"ss-" + CANARY + "\", done', 'n': 1}", redacted,
                "under the floor up to the quote, so left alone — the documented trade");
    }

    @Test
    void anApostropheQuotedValueWithoutADoubleQuoteIsRedactedWhole() {
        String redacted = SecretRedactionFilter
                .redact("{'user': 'x', 'password': 'pa-ss-" + CANARY + ", done', 'n': 1}");

        assertFalse(redacted.contains(CANARY), redacted);
        assertEquals("{'user': 'x', 'password': '<REDACTED>', 'n': 1}", redacted);
    }

    /**
     * The JSON case that forced the rule above: an apostrophe opened in one string
     * and a stray apostrophe in a sibling string at a DIFFERENT nesting level. Run
     * to the matching apostrophe, the value would have eaten the brace between.
     */
    @Test
    void anApostropheOpenedInOneJsonStringCannotCloseInAnother() {
        String document = "{\"a\":{\"m\":\"see token:'x\"},\"o\":\"it's fine\"}";

        String redacted = SecretRedactionFilter.redact(document);

        assertEquals(document, redacted, "nothing here is a secret, and the structure must survive");
        assertTrue(parses(redacted));
    }

    /** The length floor, at its boundary and on the non-string values under it. */
    @Test
    void theLengthFloorIsExact() {
        assertEquals("{\"password\":\"<REDACTED>\",\"n\":1}",
                SecretRedactionFilter.redact("{\"password\":\"abcdefgh\",\"n\":1}"), "eight characters is a secret");
        assertEquals("{\"password\":\"abcdefg\",\"n\":1}",
                SecretRedactionFilter.redact("{\"password\":\"abcdefg\",\"n\":1}"), "seven is left legible");
        assertEquals("{\"token\":true,\"n\":1}", SecretRedactionFilter.redact("{\"token\":true,\"n\":1}"));
        assertEquals("{\"token\":null,\"n\":1}", SecretRedactionFilter.redact("{\"token\":null,\"n\":1}"));
        assertEquals("{\"token\":\"<REDACTED>\",\"n\":1}",
                SecretRedactionFilter.redact("{\"token\":123456789012,\"n\":1}"), "a long number is a secret");
    }

    /**
     * Adversarial input, bounded time. The filter runs on attacker-influenced
     * request bodies. The regexes carry possessive quantifiers so a crafted input
     * cannot make them backtrack, and the quoted-value scan is a single forward
     * pass — an earlier draft expressed it as a regex with a quantified group,
     * which Java matches by recursion, and it overflowed the stack on the second
     * input below. Measured rather than argued about.
     * <p>
     * The bound is a preemptive timeout, not a wall-clock assertion: a linear
     * algorithm finishes each of these in single-digit milliseconds, a
     * catastrophically backtracking one would not finish in an hour, and nothing in
     * between is a plausible outcome — so the budget is set where a throttled CI
     * runner cannot reach it, and its only job is to turn a hang into a failure
     * that names the input.
     */
    @Test
    void adversarialInputDoesNotBlowUp() {
        List<String> attacks = List.of(
                "{\"apiKey\":\"" + "\\\"".repeat(20_000) + "\"}",
                "{\"apiKey\":\"" + "a".repeat(200_000) + "\"}",
                "{\"apiKey\":\"" + "${vault:".repeat(20_000) + "\"}",
                "\"apiKey\":\"".repeat(20_000),
                "{\"apiKey\":" + "[".repeat(50_000),
                "apiKey=" + "sk-ant-".repeat(20_000),
                // Backslash runs, which the depth arithmetic counts.
                "{\"apiKey\":\"" + "\\".repeat(100_000) + "\"}",
                "{\"apiKey\":\"" + "\\\\\"".repeat(30_000) + "\"}",
                // A credential field per line, many lines: the scan must not go
                // quadratic by re-reading earlier lines for each match.
                ("{\"apiKey\":\"" + "a".repeat(10) + "\"}\n").repeat(20_000),
                // Unterminated, so the scan runs to the end — once.
                "{\"apiKey\":\"" + "a b ".repeat(50_000));

        for (String attack : attacks) {
            assertTimeoutPreemptively(Duration.ofSeconds(30),
                    () -> assertDoesNotThrow(() -> SecretRedactionFilter.redact(attack)),
                    () -> "redaction did not finish on a " + attack.length() + "-char input starting "
                            + attack.substring(0, 20));
        }
    }

    /**
     * Coverage-guided fuzzing over arbitrary input. Runs as an ordinary JUnit
     * regression test in CI against the seeds beside this class; for real fuzzing
     * set {@code JAZZER_FUZZ=1} and instrument the target:
     * {@code JAZZER_FUZZ=1 ./mvnw test -Dtest=SecretRedactionFilterInvariantsTest
     * -Djazzer.instrument=ai.labs.eddi.secrets.sanitize.**}
     * <p>
     * What is promised on ANY input: the filter does not throw. What is promised on
     * a JSON carrier: the output is still JSON, and a second pass changes nothing —
     * that is the property that matters on input nobody designed, because a second
     * pass changing anything means a rule is re-matching its own output. The
     * structured target below enforces both at every nesting depth.
     * <p>
     * Idempotency is deliberately NOT promised on text that is not JSON. A fuzzer
     * found why it cannot be: in garbage, an apostrophe-quoted value can
     * legitimately close a hundred characters later and the span between can hold
     * double quotes that other, overlapping, fields depended on — so pass two sees
     * different field extents and redacts MORE. Never less: redaction only ever
     * replaces, so a second pass on anything can only over-redact. Closing that gap
     * would mean an apostrophe-quoted value can never contain a double quote, and
     * {@code {'password': 'pa"ss…'}} is a Python repr real logs carry — that would
     * be a leak on a plausible carrier, traded for tidiness on garbage.
     */
    @FuzzTest(maxDuration = "60s")
    void fuzzArbitraryInputIsSafeAndJsonStaysJson(FuzzedDataProvider data) {
        String input = data.consumeRemainingAsString();

        String once = SecretRedactionFilter.redact(input);
        assertNotNull(once);
        if (parses(input)) {
            assertTrue(parses(once),
                    () -> "redaction broke a JSON document\n  in:  " + visible(input) + "\n  out: " + visible(once));
            assertEquals(once, SecretRedactionFilter.redact(once),
                    () -> "redaction of a JSON document must be idempotent\n  in:  " + visible(input) + "\n  out: "
                            + visible(once));
        }
    }

    /**
     * Structure-aware fuzzing of the thing the filter is FOR.
     * <p>
     * The rules are gated on a credential name followed by a separator and a quote,
     * and random bytes essentially never spell that — so the arbitrary-input fuzzer
     * above plateaus on the entry branches within seconds (coverage cannot learn
     * through the JDK regex engine). Here the fuzzer chooses the STRUCTURE — which
     * name, which quote, which separator, how deeply nested, what comes after — and
     * mutates the secret bytes freely, so every execution reaches the scanner and
     * the mutations land where the bugs were: a delimiter or an escaped quote
     * inside the value, a quote of the other kind, a backslash run, a marker, a
     * reference.
     * <p>
     * The oracle is the same canary as the matrix: planted in the secret, it must
     * not survive — unless the fuzzer happened to generate a value that is EXACTLY
     * a whole vault reference or EXACTLY an already-redacted form, the two things
     * the filter deliberately leaves alone. And the result must still parse when
     * the input did.
     */
    @FuzzTest(maxDuration = "60s")
    void fuzzCredentialFieldsNeverLeak(FuzzedDataProvider data) {
        String key = data.pickValue(CREDENTIAL_KEYS.toArray(String[]::new));
        char quote = data.consumeBoolean() ? '"' : '\'';
        String separator = data.pickValue(new String[]{":", " : ", "=", " = ", ": "});
        int depth = data.consumeInt(0, 2);
        String secret = CANARY + data.consumeString(64) + (data.consumeBoolean() ? "" : CANARY);
        // Whatever follows the document is not a secret. The fuzzer learns the
        // canary literal from the comparison instrumentation and will plant it
        // here to trip the oracle; that is the oracle's problem, not the filter's.
        String tail = (data.consumeBoolean() ? data.consumeString(32) : "").replace(CANARY, "");

        // The innermost document, then escaped once per level of nesting.
        String innermost = "{" + quote + key + quote + separator + quote + secret + quote + ","
                + quote + "modelName" + quote + ":" + quote + INNOCENT + quote + "}" + tail;
        String wrapped = innermost;
        for (int i = 0; i < depth; i++) {
            wrapped = json(ordered(entry("requestBody", wrapped)));
        }
        String document = wrapped;

        String redacted = SecretRedactionFilter.redact(document);

        assertNotNull(redacted);
        assertEquals(redacted, SecretRedactionFilter.redact(redacted),
                () -> "redaction must be idempotent\n  in:  " + visible(document) + "\n  out: " + visible(redacted));
        boolean exempt = SecretReference.compiledPattern().matcher(secret).matches()
                || secret.matches("(?:sk-ant-|sk-|Bearer\\s)?<REDACTED>");
        // A secret with no backslash and no quote character of either kind inside
        // it is unambiguous, and the scanner has no excuse to miss it. A quote of
        // the CLOSING kind inside the secret ends the value early in any reading; a
        // double quote inside an apostrophe value ends it by the documented rule
        // (see anApostropheQuotedValueStopsAtADoubleQuote) — both are cuts the
        // filter makes on purpose, not leaks for this oracle to report.
        boolean unambiguous = !secret.contains("\\") && secret.indexOf(quote) < 0 && secret.indexOf('"') < 0;
        if (!exempt && secret.length() >= 8 && unambiguous) {
            assertFalse(redacted.contains(CANARY),
                    () -> "secret survived\n  in:  " + visible(document) + "\n  out: " + visible(redacted));
        }
        if (parses(document)) {
            // The honest oracle: whatever was JSON going in is JSON coming out.
            assertTrue(parses(redacted),
                    () -> "redaction broke the document\n  in:  " + visible(document) + "\n  out: " + visible(redacted));
        }
    }

    /**
     * Control characters spelled out, so a failure message shows what the fuzzer
     * actually built.
     */
    private static String visible(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                out.append("\\").append(String.format("u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean parses(String text) {
        try {
            MAPPER.readTree(text);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ── helpers ──────────────────────────────────────────────────

    /**
     * How a document reaches the filter. Each wraps the same innermost object and
     * knows how to dig it back out of the redacted result, parsing every layer — so
     * "still JSON at every depth" is asserted, not assumed.
     */
    enum Carrier {
        /** The document itself, compact. */
        PLAIN {
            @Override
            String wrap(Map<String, Object> innermost) {
                return json(innermost);
            }

            @Override
            String unwrap(String redacted) {
                return parseable(redacted, "document");
            }
        },
        /** As a string field of an outer document — a tool call's requestBody. */
        NESTED_ONCE {
            @Override
            String wrap(Map<String, Object> innermost) {
                return json(ordered(entry("requestBody", json(innermost))));
            }

            @Override
            String unwrap(String redacted) {
                return parseable(textField(parseable(redacted, "outer"), "requestBody"), "inner");
            }
        },
        /** The same, with the inner document pretty-printed before being embedded. */
        NESTED_ONCE_PRETTY {
            @Override
            String wrap(Map<String, Object> innermost) {
                String pretty = assertDoesNotThrow(
                        () -> MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(innermost));
                return json(ordered(entry("requestBody", pretty)));
            }

            @Override
            String unwrap(String redacted) {
                return parseable(textField(parseable(redacted, "outer"), "requestBody"), "inner");
            }
        },
        /**
         * Nested twice: every quote of the innermost document wears three backslashes.
         */
        NESTED_TWICE {
            @Override
            String wrap(Map<String, Object> innermost) {
                return json(ordered(entry("arguments", json(ordered(entry("requestBody", json(innermost)))))));
            }

            @Override
            String unwrap(String redacted) {
                String middle = textField(parseable(redacted, "outer"), "arguments");
                return parseable(textField(parseable(middle, "middle"), "requestBody"), "innermost");
            }
        };

        abstract String wrap(Map<String, Object> innermost);

        /** The innermost document as text, after asserting every layer parses. */
        abstract String unwrap(String redacted);

        static String parseable(String text, String layer) {
            assertDoesNotThrow(() -> MAPPER.readTree(text),
                    () -> "the " + layer + " document is no longer JSON: " + text);
            return text;
        }

        static String textField(String document, String field) {
            return assertDoesNotThrow(() -> MAPPER.readTree(document).get(field).asText(),
                    () -> "field " + field + " missing from: " + document);
        }
    }

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

        /**
         * Beside other credential fields of different kinds — a numeric token, a
         * password with a space — so one field's redaction cannot swallow or skip its
         * neighbours. The key under test goes in the middle.
         */
        static Map<String, Object> amongOtherCredentials(String key, String value) {
            return ordered(entry("otherPassword", "klmnopqr stuv"), entry(key, value),
                    entry("otherToken", 123456789012L), entry("modelName", INNOCENT));
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
