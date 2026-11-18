/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.extensions.dictionaries;

import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.utilities.ExpressionLimits.ExpressionLimitExceededException;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import ai.labs.eddi.modules.nlp.model.FoundWord;
import ai.labs.eddi.modules.nlp.model.Word;
import ai.labs.eddi.utils.CharacterUtilities;

import java.util.Collections;
import java.util.List;

/**
 * Recognises an all-digit token and emits an {@code integer} expression.
 *
 * @author ginccc
 */
public class IntegerDictionary implements IDictionary {
    private static final String ID = "integer";
    private final IExpressionProvider expressionProvider;

    public IntegerDictionary(IExpressionProvider expressionProvider) {
        this.expressionProvider = expressionProvider;
    }

    @Override
    public List<IFoundWord> lookupTerm(final String value) {
        if (CharacterUtilities.isStringInteger(value)) {
            Expression integerExp;
            try {
                integerExp = expressionProvider.createExpression("integer", CharacterUtilities.toAsciiDigits(value));
            } catch (ExpressionLimitExceededException e) {
                // A number longer than the expression limit is no integer expression: the
                // token is read as the unknown word it would be otherwise, and the turn
                // does not fail.
                return IDictionary.NO_WORDS_FOUND;
            }
            IWord word = new Word(value, new Expressions(integerExp), ID);

            return Collections.singletonList(new FoundWord(word, false, 1.0));
        }

        return IDictionary.NO_WORDS_FOUND;
    }

    @Override
    public boolean lookupIfKnown() {
        return true;
    }
}
