/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.model;

import ai.labs.eddi.connections.model.ConnectionReference;
import ai.labs.eddi.secrets.SecretResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scanner must answer exactly what the regexes it replaces answered — same
 * matches, same groups — and must do it in linear time.
 */
class ReferenceScannerTest {

    private static final Pattern VAULT = Pattern.compile(SecretReference.VAULT_PATTERN);
    private static final Pattern CONNECTION = Pattern.compile(ConnectionReference.CONNECTION_PATTERN);
    /** The variable finder VaultGrantChecker used before the scanner. */
    private static final Pattern VARS = Pattern.compile("\\$\\{vars:[^}]+\\}");
    /** The tenant finder VaultGrantChecker used before the scanner. */
    private static final Pattern TENANTED = Pattern.compile("\\$\\{(?:vault|eddivault|vars):([^/}]+)/[^}]+\\}");

    private static final ReferenceScanner VAULT_SCANNER = ReferenceScanner.of("vault", "eddivault");
    private static final ReferenceScanner CONNECTION_SCANNER = ReferenceScanner.of("connection");
    private static final ReferenceScanner VARS_SCANNER = ReferenceScanner.of("vars");
    private static final ReferenceScanner TENANTED_SCANNER = ReferenceScanner.withTenant("vault", "eddivault", "vars");

    /** Every match as {@code start|end|group1|group2} — the regex's view. */
    private static List<String> regex(Pattern pattern, String value, boolean twoGroups) {
        List<String> found = new ArrayList<>();
        Matcher matcher = pattern.matcher(value);
        while (matcher.find()) {
            String tenant = matcher.group(1);
            String name = twoGroups ? matcher.group(2) : null;
            found.add(matcher.start() + "|" + matcher.end() + "|" + tenant + "|" + name + "|" + matcher.group(0));
        }
        return found;
    }

    private static List<String> scanner(ReferenceScanner scanner, String value, boolean withName) {
        List<String> found = new ArrayList<>();
        for (ReferenceScanner.Match match : scanner.findAll(value)) {
            found.add(match.start() + "|" + match.end() + "|" + match.tenant() + "|" + (withName ? match.name() : null) + "|" + match.text());
        }
        return found;
    }

    /** Asserts all four finders agree with their regexes on {@code value}. */
    private static void assertParity(String value) {
        assertEquals(regex(VAULT, value, true), scanner(VAULT_SCANNER, value, true), "vault: " + value);
        assertEquals(regex(CONNECTION, value, true), scanner(CONNECTION_SCANNER, value, true), "connection: " + value);
        assertEquals(regex(TENANTED, value, false), scanner(TENANTED_SCANNER, value, false), "tenanted: " + value);
        // The vars regex has no groups; compare the matched text and bounds only.
        List<String> varsRegex = new ArrayList<>();
        Matcher matcher = VARS.matcher(value);
        while (matcher.find()) {
            varsRegex.add(matcher.start() + "|" + matcher.end());
        }
        List<String> varsScanner = new ArrayList<>();
        for (ReferenceScanner.Match match : VARS_SCANNER.findAll(value)) {
            varsScanner.add(match.start() + "|" + match.end());
        }
        assertEquals(varsRegex, varsScanner, "vars: " + value);
    }

    @Nested
    @DisplayName("the same answers as the regexes")
    class Parity {

        @ParameterizedTest
        @ValueSource(strings = {"", "plain text", "${vault:key}", "${vault:tenant/key}", "${eddivault:key}", "${eddivault:t/k}",
                "Bearer ${vault:key} and ${vault:other/key2}", "${vault:}", "${vault:}${vault:x}", "${vault:/key}", "${vault:key/}",
                "${vault:a/b/c}", "${vault://}", "${vault:/}", "${vault:a//b}", "${vault:${vault:inner}", "${vault:x${vault:t/k}",
                "${vault:/x${vault:t/k}", "${vault:t/${vault:k}", "${vault:key", "${vault:a${vault:b${vault:c", "$${vault:k}}",
                "${{vault:k}", "${VAULT:k}", "${vaultx:k}", "${vault :k}", "${vault:line\nbreak}", "${vault:k}}}", "}{${vault:k}",
                "${connection:jira}", "${connection:acme/jira}", "${connection:a/}", "${connection:${connection:x}",
                "x ${connection:jira} y ${connection:t/n}", "${vars:model}", "${vars:space/model}", "${vars:}", "${vars:a/}",
                "${vars:${vault:t/k}}", "${vars:/x${vault:t/k}", "${connection:", "${", "$", "{}", "${vault:a/b}${connection:c/d}${vars:e/f}"})
        @DisplayName("on hand-picked edge cases")
        void handPicked(String value) {
            assertParity(value);
        }

        @Test
        @DisplayName("on random strings over the reference alphabet")
        void fuzzed() {
            String[] pieces = {"${", "vault:", "eddivault:", "connection:", "vars:", "}", "/", "a", "b", "$", "{", " ", ":"};
            Random random = new Random(20261026L);
            for (int round = 0; round < 20_000; round++) {
                StringBuilder value = new StringBuilder();
                int length = random.nextInt(14);
                for (int i = 0; i < length; i++) {
                    value.append(pieces[random.nextInt(pieces.length)]);
                }
                assertParity(value.toString());
            }
        }

        @Test
        @DisplayName("matchesWhole agrees with Matcher.matches")
        void matchesWhole() {
            for (String value : List.of("${vault:k}", " ${vault:k}", "${vault:k} ", "${vault:k}${vault:j}", "${vault:}", "${vault:a/b}",
                    "${vault:k}}", "x")) {
                assertEquals(VAULT.matcher(value).matches(), VAULT_SCANNER.matchesWhole(value), value);
            }
        }

        @Test
        @DisplayName("replaceAll agrees with Matcher.replaceAll")
        void replaceAll() {
            for (String value : List.of("a ${vault:k} b ${vault:t/j} c", "none", "${vault:k}", "${vault:${vault:k}} tail")) {
                assertEquals(VAULT.matcher(value).replaceAll("#"), VAULT_SCANNER.replaceAll(value, match -> "#"), value);
            }
        }
    }

    @Nested
    @DisplayName("the callers keep their answers")
    class Callers {

        @Test
        @DisplayName("SecretReference.parse reads both forms and the legacy prefix")
        void secretReferenceParse() {
            assertEquals(new SecretReference("default", "k"), SecretReference.parse("x ${vault:k} y"));
            assertEquals(new SecretReference("t", "k/z"), SecretReference.parse("${eddivault:t/k/z}"));
            assertEquals(new SecretReference("default", "a/"), SecretReference.parse("${vault:a/}"));
            assertThrows(IllegalArgumentException.class, () -> SecretReference.parse("${vault:}"));
        }

        @Test
        @DisplayName("ConnectionReference keeps contains, parse and requireSole")
        void connectionReference() {
            assertTrue(ConnectionReference.contains("Bearer ${connection:jira}"));
            assertFalse(ConnectionReference.contains("${connection:}"));
            assertFalse(ConnectionReference.contains(null));
            assertEquals(new ConnectionReference("acme", "jira"), ConnectionReference.parse("${connection:acme/jira}"));
            ConnectionReference.requireSole("  ${connection:jira} ", "Header 'Authorization'");
            var refusal = assertThrows(IllegalArgumentException.class,
                    () -> ConnectionReference.requireSole("Bearer ${connection:jira}", "Header 'Authorization'"));
            assertTrue(refusal.getMessage().contains("the literal text 'Bearer'"), refusal.getMessage());
            var second = assertThrows(IllegalArgumentException.class,
                    () -> ConnectionReference.requireSole("${connection:a}${connection:b}", "Header 'Authorization'"));
            assertTrue(second.getMessage().contains("a second ${connection:…} reference"), second.getMessage());
        }

        @Test
        @DisplayName("a missing reference finds nothing")
        void nothing() {
            assertNull(VAULT_SCANNER.findFirst(null));
            assertTrue(VAULT_SCANNER.findAll(null).isEmpty());
            assertNull(VAULT_SCANNER.replaceAll(null, match -> "#"));
        }
    }

    /**
     * The regexes' {@code find()} rescanned the whole rest of the value at every
     * unterminated opener. Fifty thousand of them is about 650k characters — a
     * plausible variable or parameter value — and took the regex minutes; the
     * scanner reads it once.
     */
    @Nested
    @DisplayName("linear time on unterminated openers")
    class Pathological {

        private static final Duration BUDGET = Duration.ofSeconds(5);
        private static final int OPENERS = 50_000;

        @Test
        @DisplayName("ConnectionReference.contains")
        void connectionContains() {
            String value = "${connection:".repeat(OPENERS);
            assertTimeoutPreemptively(BUDGET, () -> assertFalse(ConnectionReference.contains(value)));
        }

        @Test
        @DisplayName("SecretResolver.requireResolved, which names the reference it refuses")
        void requireResolved() {
            String value = "${vault:".repeat(OPENERS);
            assertTimeoutPreemptively(BUDGET, () -> {
                var refusal = assertThrows(SecretResolver.UnresolvedSecretReferenceException.class,
                        () -> SecretResolver.requireResolved(Map.of("apiKey", value), "embedding model 'openai'"));
                assertTrue(refusal.getMessage().contains("references a vault reference"));
            });
        }

        @Test
        @DisplayName("the tenant finder, whose failed matches retry inside their own body")
        void tenantFinder() {
            // No slash anywhere, so no opener names a tenant. The regex read each one's
            // body to the far-away brace before failing, then retried at the next
            // nested opener and read it all again.
            String value = "${vault:a".repeat(OPENERS) + "}";
            assertTimeoutPreemptively(BUDGET, () -> assertTrue(TENANTED_SCANNER.findAll(value).isEmpty()));
        }

        @Test
        @DisplayName("a closing brace far away, after many openers, is one match")
        void farBrace() {
            String value = "${vault:".repeat(OPENERS) + "}";
            assertTimeoutPreemptively(BUDGET, () -> assertEquals(1, VAULT_SCANNER.findAll(value).size()));
        }
    }
}
