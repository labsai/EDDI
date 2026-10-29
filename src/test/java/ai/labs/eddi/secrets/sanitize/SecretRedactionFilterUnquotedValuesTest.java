/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.sanitize;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

import static ai.labs.eddi.secrets.sanitize.SecretRedactionFilter.REDACTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The unquoted-value pass of {@link SecretRedactionFilter}: a forward-only scan
 * for the vault-reference prefix that replaced a regex prefix searching for the
 * closing brace afresh at every key.
 */
class SecretRedactionFilterUnquotedValuesTest {

    private static final String GUARD_PREFIX = "(?!(?:sk-ant-|sk-|Bearer\\s)?" + REDACTED;
    private static final String VAULT = "(?:\\$\\{(?:vault|eddivault):[^}]*+\\})?+";

    /** The two rules as they were, value regex included. */
    private static final List<Pattern> REFERENCE_RULES = List.of(
            Pattern.compile("(?i)(api[_-]?key|token|secret|password|authorization)((?:\\\\{7}|\\\\{3}|\\\\)?+)([\"'])(\\s*+:\\s*+)"
                    + "(?!\\\\*+[\"'])" + GUARD_PREFIX + "(?=[\\s,;}{\\]\"']|$))" + VAULT + "[^\\s,;}{\\]\"'\\\\]{8,}+"),
            Pattern.compile("(?i)(api[_-]?key|token|secret|password|authorization)((?:\\\\*+[\"'])?+\\s*+[=:]\\s*+)" + GUARD_PREFIX
                    + "(?=['\"\\\\\\s,;}{\\]]|$))" + VAULT + "[^'\"\\\\\\s,;}{\\]]{8,}+"));
    private static final List<String> REFERENCE_REPLACEMENTS = List.of("$1$2$3$4$2$3" + REDACTED + "$2$3", "$1$2" + REDACTED);

    private static String reference(String text) {
        String result = text;
        for (int i = 0; i < REFERENCE_RULES.size(); i++) {
            result = REFERENCE_RULES.get(i).matcher(result).replaceAll(REFERENCE_REPLACEMENTS.get(i));
        }
        return result;
    }

    @Test
    @DisplayName("reference-plus-tail is redacted whole; a bare reference survives")
    void vaultReferencePrefix() {
        assertEquals("password: " + REDACTED, SecretRedactionFilter.redactUnquotedValues("password: ${vault:db-key}SECRETTAIL"));
        assertEquals("password: ${vault:db-key}", SecretRedactionFilter.redactUnquotedValues("password: ${vault:db-key}"));
        assertEquals("token=" + REDACTED + " next", SecretRedactionFilter.redactUnquotedValues("token=${EDDIVAULT:k}abcdefgh next"));
        assertEquals("token: ${vault:abcdefghijk", SecretRedactionFilter.redactUnquotedValues("token: ${vault:abcdefghijk"));
    }

    @Test
    @DisplayName("the scan replaces exactly what the regex prefix it replaced did")
    void equivalentToTheRegexItReplaced() {
        String[] atoms = {"token", "Password", "api_key", ":", "=", " ", "\"", "'", "\\", "${vault:", "${EddiVault:", "${vault", "}", "abcdefgh",
                "xyz", ",", "{", REDACTED, "sk-", "Bearer ", "\n", "ı"};
        Random random = new Random(1333);
        for (int i = 0; i < 200_000; i++) {
            StringBuilder text = new StringBuilder();
            int length = 1 + random.nextInt(9);
            for (int j = 0; j < length; j++) {
                text.append(atoms[random.nextInt(atoms.length)]);
            }
            String input = text.toString();
            assertEquals(reference(input), SecretRedactionFilter.redactUnquotedValues(input), () -> "on [" + input + "]");
        }
    }

    /**
     * Many keys each opening a reference that never closes: the regex searched the
     * rest of the message for the brace once per key, quadratic in the message, so
     * 40 000 repetitions took minutes.
     */
    @Test
    @DisplayName("repeated unterminated vault references are scanned once")
    void unterminatedReferencesAreLinear() {
        String unterminated = "token:${vault:".repeat(40_000);
        String quoted = "\"token\":${vault:".repeat(40_000);
        String closedAtTheEnd = "token:${vault:".repeat(40_000) + "}";
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            assertEquals(unterminated, SecretRedactionFilter.redactUnquotedValues(unterminated));
            assertEquals(quoted, SecretRedactionFilter.redactUnquotedValues(quoted));
            assertEquals(closedAtTheEnd, SecretRedactionFilter.redactUnquotedValues(closedAtTheEnd));
            String redacted = SecretRedactionFilter.redact(unterminated + "password: hunter2hunter2");
            assertTrue(redacted.endsWith("password: " + REDACTED), "a real value after the noise is still redacted");
            assertFalse(redacted.contains("hunter2"));
        });
    }
}
