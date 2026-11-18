/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.internal;

import ai.labs.eddi.modules.nlp.IInputParser;
import ai.labs.eddi.modules.nlp.expressions.ExpressionFactory;
import ai.labs.eddi.modules.nlp.expressions.utilities.ExpressionLimits;
import ai.labs.eddi.modules.nlp.expressions.utilities.ExpressionLimits.ExpressionLimitExceededException;
import ai.labs.eddi.modules.nlp.expressions.utilities.ExpressionProvider;
import ai.labs.eddi.modules.nlp.extensions.corrections.ICorrection;
import ai.labs.eddi.modules.nlp.extensions.corrections.MergedTermsCorrection;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.DecimalDictionary;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.EmailDictionary;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.IntegerDictionary;
import ai.labs.eddi.modules.nlp.internal.matches.RawSolution;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A dictionary that turns the token itself into an expression (e-mail, integer,
 * decimal) meets a token longer than {@code eddi.expressions.max-length}: the
 * parser refuses the expression, and that must cost the token its reading, not
 * the user the turn.
 */
@DisplayName("InputParser — a token over the expression limit fails no turn")
class InputParserExpressionLimitTest {

    /** Small enough that a realistic token passes it. */
    private static final ExpressionProvider PROVIDER = new ExpressionProvider(new ExpressionFactory(), new ExpressionLimits(40, 32));

    private static final String LONG_EMAIL = "a".repeat(40) + "@example.com";
    private static final String LONG_INTEGER = "7".repeat(60);
    private static final String LONG_DECIMAL = "7".repeat(60) + ".5";

    private static List<IDictionary> dictionaries() {
        return List.of(new EmailDictionary(PROVIDER), new IntegerDictionary(PROVIDER), new DecimalDictionary(PROVIDER));
    }

    private static String onlyExpressionName(List<RawSolution> solutions) {
        RawSolution first = solutions.getFirst();
        assertEquals(1, first.getDictionaryEntries().size());
        return first.getDictionaryEntries().getFirst().getFoundWord().getExpressions().getFirst().getExpressionName();
    }

    @Test
    @DisplayName("precondition: the e-mail dictionary itself throws for such a token")
    void emailDictionaryThrows() {
        assertThrows(ExpressionLimitExceededException.class, () -> new EmailDictionary(PROVIDER).lookupTerm(LONG_EMAIL));
    }

    @Test
    @DisplayName("the over-limit token is read as unknown, for every dictionary that takes free text")
    void overLimitTokenIsUnknown() throws Exception {
        for (String token : List.of(LONG_EMAIL, LONG_INTEGER, LONG_DECIMAL)) {
            var parser = new InputParser(dictionaries());
            assertEquals("unknown", onlyExpressionName(parser.parse(token, "en", Collections.emptyList())), token);
        }
    }

    @Test
    @DisplayName("the same token within the limit is still read by its dictionary")
    void withinLimitStillMatches() throws Exception {
        var parser = new InputParser(dictionaries());
        assertEquals("email", onlyExpressionName(parser.parse("a@example.com", "en", Collections.emptyList())));
        assertEquals("integer", onlyExpressionName(parser.parse("77", "en", Collections.emptyList())));
    }

    @Test
    @DisplayName("a correction that consults the dictionaries with parts of the token is covered too")
    void mergedTermsCorrectionIsCovered() throws Exception {
        ICorrection correction = new MergedTermsCorrection();
        correction.init(dictionaries());
        // no direct dictionaries: only the correction looks the token (and its parts)
        // up
        var parser = new InputParser(Collections.emptyList(), Collections.emptyList(), List.of(correction), new IInputParser.Config());
        assertEquals("unknown", onlyExpressionName(parser.parse(LONG_EMAIL, "en", Collections.emptyList())));
    }
}
