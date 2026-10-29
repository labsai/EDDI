/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.agenttests;

import ai.labs.eddi.engine.agenttests.BoundedRegex.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedRegexTest {

    @Test
    @DisplayName("ordinary patterns match and miss as java.util.regex does")
    void ordinaryPatterns() {
        assertEquals(Result.MATCH, BoundedRegex.find("(?i)3 rooms", "We have 3 Rooms left"));
        assertEquals(Result.NO_MATCH, BoundedRegex.find("(?i)4 rooms", "We have 3 Rooms left"));
        assertEquals(Result.MATCH, BoundedRegex.find("\\d{4}-\\d{2}", "on 2026-10"));
        assertEquals(Result.MATCH, BoundedRegex.find("(?:ab){3}", "xxababab"));
    }

    @Test
    @DisplayName("a backtracking pattern that reads input is stopped by the deadline")
    void backtrackingThatReadsIsTimedOut() {
        assertTimeoutPreemptively(Duration.ofSeconds(5),
                // (a+)+$ no longer backtracks on current JDKs (the loop is memoized);
                // nested .* still does, polynomially in the text with a high degree.
                () -> assertEquals(Result.TIMED_OUT, BoundedRegex.find(Pattern.compile("(.*a){12}x"), "a".repeat(40) + "b", 100)));
    }

    /**
     * These spin without one {@code charAt}, so the deadline never fires: the first
     * took about 25 seconds and the others over a second each on a short text
     * before the repetition check existed.
     */
    @ParameterizedTest
    @ValueSource(strings = {"(?:(?:){99999}){99999}", "(?:(?=)){99999999}(?!)", "()(?:\\1){99999999}(?!)", "(?:(?=)){99999999,}(?!)",
            "(?:(?=)){99999999}+(?!)", "(?<n>)(?:\\k<n>){5000}", "(?:(?=){600})(?:(?=){600})", "(?:(?:(?:){11}){11}){11}", "(?i:(?:){5000})",
            "\\A{5000}", "(?:ab){1001}"})
    @DisplayName("counted repetitions that force empty work are refused, at save time and before matching")
    void emptyRepetitionIsRefused(String regex) {
        assertTrue(BoundedRegex.excessiveRepetition(regex).isPresent(), regex);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertEquals(Result.TIMED_OUT, BoundedRegex.find(regex, "abc")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"(?i)3 rooms", "\\d{4}-\\d{2}", "[a-z]{2,8}", "(?:ab){3}", "(?:a|b){0,99999}", "a{99999}",
            "\\Q(?:){99999}\\E", "[(?:){9999}]{2}", "[\\]{]{5}", "(?:\\d{3}-){2}\\d{4}", "(?<year>\\d{4})-(?<month>\\d{2})", "(?<=a)b{3}",
            "(?:(?:){10}){10}", "\\p{L}{3}", "\\x{41}{3}", "(?-x:a){3}"})
    @DisplayName("patterns whose repetitions read input, or force little, are accepted")
    void ordinaryRepetitionIsAccepted(String regex) {
        Pattern.compile(regex);
        assertTrue(BoundedRegex.excessiveRepetition(regex).isEmpty(), regex + ": " + BoundedRegex.excessiveRepetition(regex));
    }

    @Test
    @DisplayName("the comments flag is refused: it could hide a repetition from the scan")
    void commentsFlagIsRefused() {
        assertTrue(BoundedRegex.excessiveRepetition("(?x)a # [ \n(?:(?:){99999}){99999}").isPresent());
        assertTrue(BoundedRegex.excessiveRepetition("(?ix:a)").isPresent());
        assertEquals(Result.TIMED_OUT, BoundedRegex.find(Pattern.compile("a", Pattern.COMMENTS), "a", 100));
    }
}
