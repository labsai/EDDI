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
public class DecimalDictionary implements IDictionary {
    private static final String ID = "decimal_dictionary";
    private final IExpressionProvider expressionProvider;

    public DecimalDictionary(IExpressionProvider expressionProvider) {
        this.expressionProvider = expressionProvider;
    }

    @Override
    public List<IFoundWord> lookupTerm(String value) {
        if (CharacterUtilities.isNumber(value, true)) {
            if (value.contains(",")) {
                value = value.replace(',', '.');
            }

            Expression decimalExp = expressionProvider.createExpression("decimal", value);
            IWord word = new Word(value, new Expressions(decimalExp), ID);

            return Collections.singletonList(new FoundWord(word, false, 1.0));
        }

        return IDictionary.NO_WORDS_FOUND;
    }

    @Override
    public boolean lookupIfKnown() {
        return false;
    }
}
