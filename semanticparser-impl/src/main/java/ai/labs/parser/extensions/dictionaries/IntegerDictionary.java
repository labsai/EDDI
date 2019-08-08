package ai.labs.parser.extensions.dictionaries;

import ai.labs.expressions.Expression;
import ai.labs.expressions.Expressions;
import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.parser.model.FoundWord;
import ai.labs.parser.model.Word;
import ai.labs.utilities.CharacterUtilities;

import java.util.Collections;
import java.util.List;

/**
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
            Expression integerExp = expressionProvider.createExpression("integer", value);
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
