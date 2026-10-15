/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.model;

import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.RegularDictionary;
import ai.labs.eddi.utils.RegexDeadline;
import ai.labs.eddi.utils.RegexDeadline.MatchDeadlineExceededException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A dictionary regex is editor-authored and runs against every token of every
 * user input with no timeout — so a pattern that backtracks exponentially held
 * the conversation thread for as long as the matcher took. A match is now
 * budgeted, and past the budget the token does not match.
 */
@DisplayName("RegEx — dictionary pattern matching has a time budget")
class RegExTest {

    /**
     * A pattern this JDK still backtracks on: JDK 9+ defuses the textbook
     * {@code (a+)+$}, but twelve chained {@code .*a} groups on a near-match take
     * seconds at 30 characters and double with every character added.
     */
    private static final String EVIL_PATTERN = "(.*a){12}";
    private static final String ALMOST_MATCHING = "a".repeat(32) + "!";

    @Test
    @DisplayName("ordinary patterns match as before")
    void ordinaryMatching() {
        var regEx = new RegEx("\\d{3,}", new Expressions());

        assertTrue(regEx.match("12345"));
        assertFalse(regEx.match("12"));
        assertFalse(regEx.match("abc"));
        assertFalse(regEx.isPartOfPhrase());
    }

    @Test
    @DisplayName("a catastrophically backtracking match gives up within its budget and is a miss")
    void catastrophicBacktrackingIsAMiss() {
        var regEx = new RegEx(EVIL_PATTERN, new Expressions());

        long started = System.nanoTime();
        boolean matched = regEx.match(ALMOST_MATCHING);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertFalse(matched);
        // The budget is 200 ms, sampled every 1024 character reads; an unbudgeted
        // run of this input takes well over ten seconds.
        assertTrue(elapsedMillis < 3_000, "match took " + elapsedMillis + " ms — the deadline is not applied");
    }

    @Test
    @DisplayName("a dictionary's patterns share one budget per lookup, so many slow patterns cost one budget, not one each")
    void patternsShareOneBudgetPerLookup() {
        var dictionary = new RegularDictionary();
        for (int i = 0; i < 5; i++) {
            dictionary.addRegex(EVIL_PATTERN, new Expressions());
        }

        long started = System.nanoTime();
        var found = dictionary.lookupTerm(ALMOST_MATCHING);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(found.isEmpty());
        // One shared 200 ms deadline; five separate budgets would be a second or more.
        assertTrue(elapsedMillis < 700, "lookup took " + elapsedMillis + " ms — the patterns are not sharing a deadline");
    }

    @Test
    @DisplayName("an invalid pattern still fails at construction, as the save-time check expects")
    void invalidPatternFailsAtConstruction() {
        assertThrows(PatternSyntaxException.class, () -> new RegEx("(unclosed", new Expressions()));
    }

    @Test
    @DisplayName("RegexDeadline: the guarded sequence aborts a match once the budget is spent, and only then")
    void deadlineGuard() {
        var evil = Pattern.compile(EVIL_PATTERN);

        assertThrows(MatchDeadlineExceededException.class,
                () -> evil.matcher(RegexDeadline.guard(ALMOST_MATCHING, TimeUnit.MILLISECONDS.toNanos(50))).matches());

        var guarded = RegexDeadline.guard("hello", TimeUnit.SECONDS.toNanos(5));
        assertTrue(Pattern.compile("h.*o").matcher(guarded).matches());
        assertEquals(5, guarded.length());
        assertEquals("ell", guarded.subSequence(1, 4).toString());
        assertEquals("hello", guarded.toString());
    }
}
