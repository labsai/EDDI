package ai.labs.eddi.modules.nlp.extensions.dictionaries;

import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import ai.labs.eddi.modules.nlp.model.FoundWord;
import ai.labs.eddi.modules.nlp.model.Word;
import ai.labs.eddi.utils.CharacterUtilities;

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
