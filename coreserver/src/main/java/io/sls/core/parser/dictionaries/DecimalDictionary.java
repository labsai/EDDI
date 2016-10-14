package io.sls.core.parser.dictionaries;

import io.sls.core.parser.model.FoundWord;
import io.sls.core.parser.model.IDictionary;
import io.sls.core.parser.model.Word;
import io.sls.expressions.Expression;
import io.sls.expressions.utilities.IExpressionUtilities;
import io.sls.utilities.CharacterUtilities;

import javax.inject.Inject;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;


/**
 * User: jarisch
 * Date: 11.11.2010
 * Time: 19:13:49
 */
public class DecimalDictionary implements IDictionary {
    private static final String ID = "decimal_dictionary";
    private final IExpressionUtilities expressionUtilities;

    @Inject
    public DecimalDictionary(IExpressionUtilities expressionUtilities) {
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
        if (CharacterUtilities.isNumber(value, true)) {
            if (value.contains(",")) {
                value = value.replace(',', '.');
            }

            Expression decimalExp = expressionUtilities.createExpression("decimal", value);
            IWord word = new Word(value, Arrays.asList(decimalExp), ID);

            return new IFoundWord[]{new FoundWord(word, false, 1.0)};
        }

        return IDictionary.NO_WORDS_FOUND;
    }

    @Override
    public String getLanguage() {
        return ID;
    }

    @Override
    public boolean lookupIfKnown() {
        return false;
    }
}
