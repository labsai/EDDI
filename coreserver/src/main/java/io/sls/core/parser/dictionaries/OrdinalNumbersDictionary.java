package io.sls.core.parser.dictionaries;

import io.sls.core.parser.model.FoundWord;
import io.sls.core.parser.model.IDictionary;
import io.sls.core.parser.model.Word;
import io.sls.core.utilities.LanguageUtilities;
import io.sls.expressions.utilities.IExpressionUtilities;
import io.sls.utilities.RuntimeUtilities;

import javax.inject.Inject;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Created by IntelliJ IDEA.
 * User: jarisch
 * Date: 14.06.2010
 * Time: 17:21:49
 */
public class OrdinalNumbersDictionary implements IDictionary {
    public static final String ID = "punctuation";
    private final IExpressionUtilities expressionUtilities;

    @Inject
    public OrdinalNumbersDictionary(IExpressionUtilities expressionUtilities) {
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
    public IFoundWord[] lookupTerm(String value) {
        final String ordinalNumber = LanguageUtilities.isOrdinalNumber(value.toLowerCase());
        if (!RuntimeUtilities.isNullOrEmpty(ordinalNumber)) {
            IWord word = new Word(ordinalNumber, Arrays.asList(expressionUtilities.createExpression("ordinal_number", ordinalNumber)), ID);
            return new IFoundWord[]{new FoundWord(word, false, 1.0)};
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
