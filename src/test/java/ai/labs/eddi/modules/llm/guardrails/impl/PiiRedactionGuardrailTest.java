/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.guardrails.impl;

import ai.labs.eddi.modules.llm.guardrails.GuardrailContext;
import ai.labs.eddi.modules.llm.guardrails.GuardrailPhase;
import ai.labs.eddi.modules.llm.guardrails.GuardrailVerdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code pii-redaction} guardrail on the shared detectors: the four
 * original kinds rewrite exactly as before ({@link LegacyPiiRedaction}), and
 * the new kinds are found without a configuration change.
 */
class PiiRedactionGuardrailTest {

    private static final String LEGACY_KINDS = "email,ssn,creditcard,phone";

    private final PiiRedactionGuardrail guardrail = new PiiRedactionGuardrail();

    private static GuardrailContext context(Map<String, String> parameters) {
        return new GuardrailContext(GuardrailPhase.INPUT, "task1", "0123456789abcdef01234567", "0123456789abcdef76543210", parameters, List.of());
    }

    private String rewrite(String text, Map<String, String> parameters) {
        GuardrailVerdict verdict = guardrail.evaluate(text, context(parameters));
        return verdict.kind() == GuardrailVerdict.Kind.ALLOW ? text : verdict.text();
    }

    @Nested
    @DisplayName("golden: the four original kinds are byte-identical to the legacy rewrite")
    class Golden {

        /** Fragments that exercise every legacy pattern and its edge cases. */
        private static final List<String> FRAGMENTS = List.of("jane.doe+x@mail.example.org", "a@b.io", "SSN 123-45-6789", "000-12-3456",
                "4111 1111 1111 1111", "4111111111111111", "4111-1111-1111-1111", "4111 1111 1111 1112", "4111 1111 1111 1111 123",
                "ref 1234 4111 1111 1111 1111", "order 4111111111111111123", "+1 555-123-4567", "(555) 123-4567", "+15551234567", "030 1234 5678",
                "0170 1234567", "version 1.22.0", "2026-10-15", "192.168.1.10", "call me", "at", "\n", "—", "12 34 56", "x@y", "5555 5555 5555 4444",
                "4012888888881881", "378282246310005", " ", ",", "(", ")", "+", "-");

        @Test
        @DisplayName("fixed corpus, default patterns and an explicit legacy list")
        void fixedCorpus() {
            for (String text : FRAGMENTS) {
                assertEquals(LegacyPiiRedaction.redact(text, PiiRedactionGuardrail.DEFAULT_REPLACEMENT),
                        rewrite(text, Map.of("patterns", LEGACY_KINDS)), text);
                assertEquals(LegacyPiiRedaction.redact(text, PiiRedactionGuardrail.DEFAULT_REPLACEMENT), rewrite(text, Map.of()), text);
            }
        }

        @Test
        @DisplayName("random fragment combinations, with the default and a custom replacement")
        void randomCombinations() {
            Random random = new Random(20261104L);
            for (int round = 0; round < 3_000; round++) {
                StringBuilder text = new StringBuilder();
                int parts = 1 + random.nextInt(6);
                for (int i = 0; i < parts; i++) {
                    text.append(FRAGMENTS.get(random.nextInt(FRAGMENTS.size())));
                    text.append(random.nextBoolean() ? " " : "");
                }
                String candidate = text.toString();
                assertEquals(LegacyPiiRedaction.redact(candidate, PiiRedactionGuardrail.DEFAULT_REPLACEMENT),
                        rewrite(candidate, Map.of("patterns", LEGACY_KINDS)), candidate);
                assertEquals(LegacyPiiRedaction.redact(candidate, "<{kind}>"),
                        rewrite(candidate, Map.of("patterns", LEGACY_KINDS, "replacement", "<{kind}>")), candidate);
            }
        }

        @Test
        @DisplayName("reason keeps the legacy kind names")
        void legacyReason() {
            assertEquals("pii:creditcard,email", guardrail.evaluate("a@b.io 4111 1111 1111 1111", context(Map.of())).reason());
        }
    }

    @Nested
    @DisplayName("new kinds")
    class NewKinds {

        @Test
        @DisplayName("an IBAN is redacted whole — the phone pattern no longer takes its digit groups")
        void iban() {
            var verdict = guardrail.evaluate("IBAN DE89 3704 0044 0532 0130 00 bitte", context(Map.of()));
            assertEquals("IBAN [REDACTED:iban] bitte", verdict.text());
            assertEquals("pii:iban", verdict.reason());
        }

        @Test
        @DisplayName("an address whose local part is an IBAN or a tax ID is redacted whole, as an e-mail")
        void emailBeforeStructuredIdentifiers() {
            assertEquals("to [REDACTED:email].", rewrite("to DE89370400440532013000@example.com.", Map.of()));
            assertEquals("to [REDACTED:email].", rewrite("to 86095742719@example.com.", Map.of("patterns", "iban,de_tax_id,email")));
        }

        @Test
        void taxIdAndSocialSecurity() {
            assertEquals("Steuer-ID [REDACTED:de_tax_id].", rewrite("Steuer-ID 86095742719.", Map.of("countries", "DE")));
            assertEquals("RVNR [REDACTED:de_social_security]", rewrite("RVNR 15 070649 C 103", Map.of("countries", "de")));
        }

        @Test
        void idCardAndBirthDate() {
            assertEquals("Ausweisnummer [REDACTED:de_id_card]", rewrite("Ausweisnummer L01X00T471", Map.of("countries", "DE")));
            assertEquals("geb. [REDACTED:date_of_birth] in Wien", rewrite("geb. 12.03.1980 in Wien", Map.of("patterns", "date_of_birth")));
        }

        @Test
        @DisplayName("an entity name works as a patterns alias, and selects only that kind")
        void entityAlias() {
            assertEquals("[REDACTED:iban] a@b.io", rewrite("GB82 WEST 1234 5698 7654 32 a@b.io", Map.of("patterns", "IBAN")));
            assertEquals("[REDACTED:creditcard]", rewrite("4111 1111 1111 1111", Map.of("patterns", "CREDIT_CARD")));
        }

        @Test
        @DisplayName("national identifiers are opt-in: by country, by kind, or both")
        void countries() {
            String text = "Steuer-ID 86095742719, CF RSSMRA85T10A562S, a@b.io";
            assertEquals("Steuer-ID 86095742719, CF RSSMRA85T10A562S, [REDACTED:email]", rewrite(text, Map.of()));
            assertEquals("Steuer-ID [REDACTED:de_tax_id], CF RSSMRA85T10A562S, [REDACTED:email]", rewrite(text, Map.of("countries", "DE")));
            assertEquals("Steuer-ID 86095742719, CF [REDACTED:it_fiscal_code], a@b.io", rewrite(text, Map.of("patterns", "it_fiscal_code")));
            assertEquals("Steuer-ID [REDACTED:de_tax_id], CF [REDACTED:it_fiscal_code], a@b.io",
                    rewrite(text, Map.of("patterns", "IT_FISCAL_CODE", "countries", "DE")));
        }

        @ParameterizedTest
        @DisplayName("texts that merely look similar stay untouched")
        @ValueSource(strings = {"Termin am 12.03.1980", "DE89 3704 0044 0532 0130 01", "order 86095742718", "code L01X00T471",
                "Konto 1234 5678 9012"})
        void noFalsePositives(String text) {
            var verdict = guardrail.evaluate(text, context(Map.of("patterns", "iban,de_tax_id,de_id_card,date_of_birth")));
            assertSame(GuardrailVerdict.Kind.ALLOW, verdict.kind(), text);
        }
    }

    @Nested
    @DisplayName("configuration")
    class Configuration {

        @Test
        void unknownKind_isAWarning() {
            List<String> warnings = guardrail.warnings(Map.of("patterns", "email,emial"));
            assertEquals(1, warnings.size());
            assertTrue(warnings.getFirst().contains("emial"), warnings.getFirst());
            assertTrue(guardrail.warnings(Map.of("patterns", "email,IBAN,de_tax_id")).isEmpty());
            assertTrue(guardrail.warnings(Map.of()).isEmpty());
        }

        @Test
        void unknownCountry_isAWarning() {
            List<String> warnings = guardrail.warnings(Map.of("countries", "DE, XX"));
            assertEquals(1, warnings.size());
            assertTrue(warnings.getFirst().contains("XX"), warnings.getFirst());
            assertTrue(guardrail.warnings(Map.of("countries", "de,FR,gb")).isEmpty());
        }

        @Test
        void defaultIsTheStandardSet() {
            assertEquals(List.of("email", "iban", "ssn", "creditcard", "phone"),
                    PiiRedactionGuardrail.enabledEntities(null, null).stream().map(PiiRedactionGuardrail::kindOf).toList());
        }

        @Test
        void kindsListsEveryKind() {
            List<String> kinds = PiiRedactionGuardrail.kinds();
            assertEquals(40, kinds.size());
            assertEquals(List.of("email", "iban"), kinds.subList(0, 2));
            assertEquals(List.of("creditcard", "phone"), kinds.subList(kinds.size() - 2, kinds.size()));
            assertTrue(kinds.containsAll(List.of("ssn", "de_tax_id", "fr_nir", "in_aadhaar", "ip_address", "passport_mrz")), kinds.toString());
        }

        @Test
        void reasonNeverContainsTheText() {
            var verdict = guardrail.evaluate("DE89 3704 0044 0532 0130 00", context(Map.of()));
            assertFalse(verdict.reason().contains("3704"));
        }
    }
}
