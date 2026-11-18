/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.extensions.dictionaries;

import ai.labs.eddi.modules.nlp.expressions.ExpressionFactory;
import ai.labs.eddi.modules.nlp.expressions.utilities.ExpressionProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Digits of other scripts are numbers: the integer and decimal dictionaries
 * still produce their expression, with ASCII digits inside it.
 */
@DisplayName("integer and decimal dictionaries read digits of every script")
class NumberDictionariesUnicodeDigitsTest {

    private final ExpressionProvider provider = new ExpressionProvider(new ExpressionFactory());

    @Test
    @DisplayName("fullwidth, Arabic-Indic, Devanagari and Thai integers become integer(12)")
    void integers() {
        var dictionary = new IntegerDictionary(provider);
        for (String digits : new String[]{"１２", "١٢", "१२", "๑๒", "12"}) {
            var found = dictionary.lookupTerm(digits);
            assertFalse(found.isEmpty(), digits);
            assertEquals("integer(12)", found.getFirst().getFoundWord().getExpressions().getFirst().toString(), digits);
        }
    }

    @Test
    @DisplayName("an Arabic-Indic decimal becomes decimal(1.5)")
    void decimals() {
        var found = new DecimalDictionary(provider).lookupTerm("١,٥");

        assertFalse(found.isEmpty());
        assertEquals("decimal(1.5)", found.getFirst().getFoundWord().getExpressions().getFirst().toString());
    }
}
