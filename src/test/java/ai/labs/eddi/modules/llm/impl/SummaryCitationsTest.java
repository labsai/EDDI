/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.impl.SummaryCitations.OnValidationFailure;
import ai.labs.eddi.modules.llm.impl.SummaryCitations.Range;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummaryCitationsTest {

    @Nested
    @DisplayName("numbers")
    class Numbers {

        @Test
        void separatorsDecimalsSignAndPercentAreOneToken() {
            assertEquals(Set.of("1,000", "1.000", "3.14", "-5", "10%", "1,000.50"),
                    SummaryCitations.numbers("1,000 and 1.000, pi is 3.14; low -5 at 10% with 1,000.50."));
        }

        @Test
        void aHyphenAfterALetterOrDigitIsNotASign() {
            assertEquals(Set.of("4", "7", "1234", "2026", "11", "06"), SummaryCitations.numbers("range 4-7, order A-1234, date 2026-11-06"));
        }

        @Test
        void trailingPunctuationIsNotPartOfTheNumber() {
            assertEquals(Set.of("1,000", "42"), SummaryCitations.numbers("It cost 1,000. Then 42,"));
        }

        @Test
        void citationsStepReferencesAndListMarkersAreNotFactNumbers() {
            String text = "1. The user moved the meeting [turns 4-7, 9] as said in turn 12 and steps 2 to 3\n2) budget 500 [turn 5]";
            assertEquals(Set.of("500"), SummaryCitations.factNumbers(text));
        }
    }

    @Nested
    @DisplayName("citations")
    class Citations {

        @Test
        void parsesSinglesRangesListsAndSynonyms() {
            List<Range> ranges = SummaryCitations.citations("a [turn 5] b [turns 4-7, 9] c [steps 2 to 3; 8] d [Step 7–6]", null);
            assertEquals(List.of(new Range(5, 5), new Range(4, 7), new Range(9, 9), new Range(2, 3), new Range(8, 8), new Range(6, 7)), ranges);
        }

        @Test
        void aBracketThatIsNotAStepListIsReported() {
            List<String> malformed = new ArrayList<>();
            assertTrue(SummaryCitations.citations("see [turns four to seven]", malformed).isEmpty());
            assertEquals(1, malformed.size());
        }

        @Test
        void plainBracketsAreNotCitations() {
            List<String> malformed = new ArrayList<>();
            assertTrue(SummaryCitations.citations("[note] [1] [x 4-7]", malformed).isEmpty());
            assertTrue(malformed.isEmpty());
        }
    }

    @Nested
    @DisplayName("conversation summary check")
    class ConversationSummary {

        private final Map<Integer, String> steps = Map.of(3, "My budget is 1,000 EUR\nNoted.", 4, "Pi is 3.14 and it was -5 degrees\nOk.", 5,
                "Discount 10%\nApplied.");

        private SummaryCitations.Result check(String summary) {
            return SummaryCitations.checkConversationSummary(summary, 3, 6, steps, null);
        }

        @Test
        void aFaithfulSummaryPasses() {
            var result = check("""
                    ## Budget
                    - Budget is 1,000 EUR [turn 3]
                    - Pi 3.14, temperature -5 [turns 4-5]
                    - Discount of 10% [turns 3-5]
                    """);
            assertTrue(result.valid(), result.problems().toString());
        }

        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {"- Budget is 1.000 EUR [turn 3]|1.000", "- Budget is 1000 EUR [turn 3]|1000",
                "- Temperature 5 [turn 4]|5", "- Discount 10 [turn 5]|10", "- Pi 3.1 [turn 4]|3.1"})
        void aNumberNotVerbatimInTheCitedTurnsFails(String summary, String number) {
            var result = check(summary);
            assertFalse(result.valid());
            assertTrue(result.problems().getFirst().contains("number " + number + " "), result.problems().toString());
        }

        @Test
        void aNumberFromAnUncitedTurnFails() {
            // 10% is in turn 5, but the point cites turn 3 only
            assertFalse(check("- Budget 1,000 and discount 10% [turn 3]").valid());
        }

        @Test
        void aCitationOutsideTheSummarizedRangeFails() {
            var below = check("- Budget [turn 2]");
            var above = check("- Budget [turns 5-6]");
            assertFalse(below.valid());
            assertFalse(above.valid());
            assertTrue(above.problems().getFirst().contains("outside"), above.problems().toString());
        }

        @Test
        void aPointWithoutCitationFails() {
            var result = check("- The user is friendly");
            assertFalse(result.valid());
            assertTrue(result.problems().getFirst().startsWith("no citation"));
        }

        @Test
        void aStandaloneLineNeedsACitationOnlyForItsNumbers() {
            assertTrue(check("The user is friendly.\n**Budget:**\n- 1,000 [turn 3]").valid());
            assertFalse(check("Budget was 1,000.").valid());
        }

        @Test
        void aContinuationLineSharesThePointsCitations() {
            assertTrue(check("- The budget\n  is 1,000 EUR [turn 3]").valid());
        }

        @Test
        void citationIndicesAndStepReferencesAreNotCheckedAsNumbers() {
            assertTrue(check("- In turn 4 the user said pi is 3.14 [turns 3-4, 5]").valid());
        }

        @Test
        void anEmptySummaryFails() {
            assertFalse(check("").valid());
            assertFalse(check("  \n").valid());
        }

        @Test
        void unclearInSourceNeedsNoNumbers() {
            assertTrue(check("- Deadline: unclear in source [turn 4]").valid());
        }

        @Test
        void aMalformedCitationFails() {
            assertFalse(check("- Budget 1,000 [turns three]").valid());
        }

        @Nested
        @DisplayName("previous summary")
        class PreviousSummary {

            private static final String PREVIOUS = "- Flight on 2026-12-01 at 9:45 [turns 1-2]";

            @Test
            void aCarriedPointCitingEarlierTurnsIsCheckedAgainstThePreviousSummary() {
                var result = SummaryCitations.checkConversationSummary("- Flight on 2026-12-01 at 9:45 [turns 1-2]\n- Budget 1,000 [turn 3]",
                        3, 6, steps, PREVIOUS);
                assertTrue(result.valid(), result.problems().toString());
            }

            @Test
            void aCarriedPointWithANumberThePreviousSummaryDoesNotHoldFails() {
                assertFalse(SummaryCitations.checkConversationSummary("- Flight at 9:50 [turn 1]", 3, 6, steps, PREVIOUS).valid());
            }

            @Test
            void thePreviousSummarysOwnCitationIndicesDoNotBackANumber() {
                // "2" appears in PREVIOUS only as a citation index
                assertFalse(SummaryCitations.checkConversationSummary("- Two seats: 2 [turn 1]", 3, 6, steps, "- Seats booked [turns 1-2]")
                        .valid());
            }

            @Test
            void withoutAPreviousSummaryEarlierTurnsAreOutOfRange() {
                assertFalse(SummaryCitations.checkConversationSummary("- Flight [turn 1]", 3, 6, steps, null).valid());
            }

            @Test
            void aLineCitingBothRangesMayUseNumbersFromEither() {
                assertTrue(SummaryCitations.checkConversationSummary("- Flight 9:45, budget 1,000 [turns 1, 3]", 3, 6, steps, PREVIOUS).valid());
            }
        }
    }

    @Nested
    @DisplayName("consolidated entry check")
    class ConsolidatedEntry {

        private final List<String> sources = List.of("budget: 1,000 EUR", "seats: 2", "temperature: -5");

        @Test
        void citedNumbersPass() {
            assertTrue(SummaryCitations.checkConsolidatedEntry("trip", "2 seats, budget 1,000 EUR", List.of(0, 1), sources).valid());
        }

        @Test
        void anUncitedNumberFails() {
            assertFalse(SummaryCitations.checkConsolidatedEntry("trip", "2 seats at -5", List.of(1), sources).valid());
        }

        @Test
        void noSourcesFails() {
            assertFalse(SummaryCitations.checkConsolidatedEntry("trip", "a trip", List.of(), sources).valid());
            assertFalse(SummaryCitations.checkConsolidatedEntry("trip", "a trip", null, sources).valid());
        }

        @Test
        void aSourceThatDoesNotExistFails() {
            var result = SummaryCitations.checkConsolidatedEntry("trip", "2 seats", List.of(1, 3), sources);
            assertFalse(result.valid());
            assertTrue(result.problems().getFirst().contains("does not exist"));
        }
    }

    @Test
    void policyParsesLenientlyWithADefault() {
        assertEquals(OnValidationFailure.retry_once, OnValidationFailure.parse(" RETRY_ONCE ", OnValidationFailure.keep));
        assertEquals(OnValidationFailure.keep, OnValidationFailure.parse(null, OnValidationFailure.keep));
        assertEquals(OnValidationFailure.fallback_to_previous, OnValidationFailure.parse("bogus", OnValidationFailure.fallback_to_previous));
    }

    @Test
    void countTagsSourceAndOutcome() {
        var registry = new SimpleMeterRegistry();
        SummaryCitations.count(registry, SummaryCitations.SOURCE_DREAM, SummaryCitations.OUTCOME_FALLBACK);
        SummaryCitations.count(null, SummaryCitations.SOURCE_DREAM, SummaryCitations.OUTCOME_FALLBACK);
        assertEquals(1.0, registry.get("eddi.summary.citation.checks").tags("source", "dream", "outcome", "fallback").counter().count());
    }

    @Test
    void describeListsAtMostTenProblems() {
        var problems = new ArrayList<String>();
        for (int i = 0; i < 12; i++) {
            problems.add("p" + i);
        }
        String described = new SummaryCitations.Result(problems).describe();
        assertTrue(described.contains("p9"));
        assertFalse(described.contains("p10"));
        assertTrue(described.contains("2 more"));
    }
}
