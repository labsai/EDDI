package ai.labs.parser.dictionaries;

import ai.labs.expressions.Expression;
import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.parser.model.FoundWord;
import ai.labs.parser.model.IDictionary;
import ai.labs.parser.model.Word;
import ai.labs.utilities.LanguageUtilities;
import ai.labs.utilities.RuntimeUtilities;

import javax.inject.Inject;
import java.util.Collections;
import java.util.List;

/**
 * @author ginccc
 */
public class OrdinalNumbersDictionary implements IDictionary {
    private static final String ID = "punctuation";
    private final IExpressionProvider expressionUtilities;

    @Inject
    public OrdinalNumbersDictionary(IExpressionProvider expressionUtilities) {
        this.expressionUtilities = expressionUtilities;
    }

    @Override
    public List<IWord> getWords() {
        return Collections.emptyList();
    }

    @Override
    public List<IPhrase> getPhrases() {
        return Collections.emptyList();
    }

    @Override
    public List<IFoundWord> lookupTerm(String value) {
        final String ordinalNumber = LanguageUtilities.isOrdinalNumber(value.toLowerCase());
        if (!RuntimeUtilities.isNullOrEmpty(ordinalNumber)) {
            Expression ordinalNumberExp = expressionUtilities.createExpression("ordinal_number", ordinalNumber);
            IWord word = new Word(ordinalNumber, Collections.singletonList(ordinalNumberExp), ID);
            return Collections.singletonList(new FoundWord(word, false, 1.0));
        }

        return IDictionary.NO_WORDS_FOUND;
    }

    @Override
    public String getLanguage() {
        return null;
    }

    @Override
    public boolean lookupIfKnown() {
        return false;
    }
}
