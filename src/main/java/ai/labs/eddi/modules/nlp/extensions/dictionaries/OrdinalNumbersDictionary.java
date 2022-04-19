package ai.labs.eddi.modules.nlp.extensions.dictionaries;

import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import ai.labs.eddi.modules.nlp.model.FoundWord;
import ai.labs.eddi.modules.nlp.model.Word;
import ai.labs.eddi.utils.LanguageUtilities;
import ai.labs.eddi.utils.RuntimeUtilities;

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
