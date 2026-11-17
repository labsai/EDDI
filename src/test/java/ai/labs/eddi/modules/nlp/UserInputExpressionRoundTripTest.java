/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp;

import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.ExpressionFactory;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.expressions.utilities.ExpressionLimits;
import ai.labs.eddi.modules.nlp.expressions.utilities.ExpressionLimits.ExpressionLimitExceededException;
import ai.labs.eddi.modules.nlp.expressions.utilities.ExpressionProvider;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;
import ai.labs.eddi.modules.nlp.internal.InputParser;
import ai.labs.eddi.modules.nlp.internal.matches.RawSolution;
import ai.labs.eddi.modules.nlp.model.FoundWord;
import ai.labs.eddi.modules.nlp.model.Word;
import ai.labs.eddi.modules.nlp.model.Unknown;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static ai.labs.eddi.utils.StringUtilities.joinStrings;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The parser stores the expressions it found as one string that the rules, the
 * property setter and the append-merge parse again. The text of an unknown word
 * goes into that string, so a user's parentheses must not become expression
 * nesting: a pasted list with {@code a)}, {@code (It} and {@code 3))} used to
 * push the nesting past {@code eddi.expressions.max-depth} and fail the whole
 * turn. Configuration-authored nesting is still bounded.
 */
@DisplayName("User input survives the expression string round trip")
class UserInputExpressionRoundTripTest {

    private static final String PROSE = "hello we compare a) the first option (It is cheap) and b) the second one (It is fast) while c) the third "
            + "(much) slower 3)) so d) pick (the) best 1) or 2) of them (is) done";

    private final ExpressionProvider provider = new ExpressionProvider(new ExpressionFactory());

    private static IDictionary greetingDictionary() {
        Word hello = new Word("hello", new Expressions(new Expression("greeting", new Expression("hello"))), "d");
        IDictionary dictionary = mock(IDictionary.class);
        when(dictionary.getLanguageCode()).thenReturn(null);
        when(dictionary.getPhrases()).thenReturn(List.of());
        when(dictionary.lookupIfKnown()).thenReturn(false);
        when(dictionary.lookupTerm("hello")).thenReturn(List.of(new FoundWord(hello, false, 1.0)));
        when(dictionary.lookupTerm(org.mockito.ArgumentMatchers.argThat(s -> !"hello".equals(s)))).thenReturn(IDictionary.NO_WORDS_FOUND);
        return dictionary;
    }

    /** What InputParserTask stores for a sentence, then what a rule parses back. */
    private Expressions roundTrip(String sentence) throws Exception {
        var parser = new InputParser(Collections.emptyList(), List.of(greetingDictionary()), Collections.emptyList(),
                new IInputParser.Config(true, true, true));
        List<RawSolution> solutions = parser.parse(sentence, "en", Collections.emptyList());
        Expressions found = DictionaryUtilities.extractExpressions(solutions, true, true).getFirst().getExpressions();
        return provider.parseExpressions(joinStrings(", ", found));
    }

    @Test
    @DisplayName("prose with unbalanced parentheses completes the turn and still matches the dictionary word")
    void unbalancedParenthesesDoNotFailTheTurn() throws Exception {
        Expressions parsed = assertDoesNotThrow(() -> roundTrip(PROSE.repeat(8)));

        assertTrue(parsed.stream().anyMatch(e -> "greeting".equals(e.getExpressionName())), parsed.toString());
        assertTrue(parsed.stream().anyMatch(e -> "unknown".equals(e.getExpressionName())), parsed.toString());
    }

    @Test
    @DisplayName("many opening parentheses beyond the depth limit are data, not nesting")
    void manyOpeningParenthesesAreData() throws Exception {
        String sentence = "hello " + "(It ".repeat(60) + "end";

        Expressions parsed = assertDoesNotThrow(() -> roundTrip(sentence));

        assertEquals(1, parsed.stream().filter(e -> "greeting".equals(e.getExpressionName())).count());
        assertTrue(parsed.stream().filter(e -> "unknown".equals(e.getExpressionName())).count() >= 61, parsed.toString());
    }

    @Test
    @DisplayName("an unknown word keeps its letters and loses only the structural characters")
    void unknownKeepsItsWord() {
        Expression exp = new Unknown("(It").getExpressions().getFirst();

        assertEquals("unknown(It)", exp.toString());
        assertEquals("unknown", new Unknown(")").getExpressions().getFirst().toString());
    }

    @Test
    @DisplayName("the word the user typed is still the dictionary entry's value")
    void wordValueIsUntouched() {
        assertEquals("a)", new Unknown("a)").getValue());
    }

    @Test
    @DisplayName("configuration-authored deep nesting is still refused")
    void authoredNestingStillRefused() {
        String authored = "a(".repeat(40) + "x" + ")".repeat(40);

        assertThrows(ExpressionLimitExceededException.class, () -> provider.parseExpressions(authored));
        assertThrows(ExpressionLimitExceededException.class, () -> new ExpressionLimits(32_768, 32).check(authored));
    }

    @Test
    @DisplayName("a parse failure inside a malformed expression is a warning and keeps the name")
    void malformedExpressionDegrades() {
        Expression exp = assertDoesNotThrow(() -> provider.parseExpression(")name("));

        assertFalse(exp.getExpressionName() == null);
    }
}
