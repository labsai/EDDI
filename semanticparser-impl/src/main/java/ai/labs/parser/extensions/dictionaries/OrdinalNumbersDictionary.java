package ai.labs.parser.extensions.dictionaries;

import ai.labs.expressions.Expression;
import ai.labs.expressions.Expressions;
import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.parser.model.FoundWord;
import ai.labs.parser.model.Word;
import ai.labs.utilities.LanguageUtilities;
import ai.labs.utilities.RuntimeUtilities;

import java.util.Collections;
import java.util.List;

/**
 * @author ginccc
 */
public class OrdinalNumbersDictionary implements IDictionary {
    private static final String ID = "punctuation";
    private final IExpressionProvider expressionProvider;

    public OrdinalNumbersDictionary(IExpressionProvider expressionProvider) {
        this.expressionProvider = expressionProvider;
    }

    @Override
    public List<IFoundWord> lookupTerm(String value) {
        final String ordinalNumber = LanguageUtilities.isOrdinalNumber(value.toLowerCase());
        if (!RuntimeUtilities.isNullOrEmpty(ordinalNumber)) {
            Expression ordinalNumberExp = expressionProvider.createExpression("ordinal_number", ordinalNumber);
            IWord word = new Word(ordinalNumber, new Expressions(ordinalNumberExp), ID);
            return Collections.singletonList(new FoundWord(word, false, 1.0));
        }

        return IDictionary.NO_WORDS_FOUND;
    }

    @Override
    public boolean lookupIfKnown() {
        return false;
    }
}
