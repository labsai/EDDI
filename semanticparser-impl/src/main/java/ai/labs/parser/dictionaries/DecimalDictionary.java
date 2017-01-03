package ai.labs.parser.dictionaries;

import ai.labs.parser.model.FoundWord;
import ai.labs.parser.model.IDictionary;
import ai.labs.parser.model.Word;
import io.sls.expressions.Expression;
import io.sls.expressions.utilities.IExpressionUtilities;
import io.sls.utilities.CharacterUtilities;

import javax.inject.Inject;
import java.util.Collections;
import java.util.List;


/**
 * @author ginccc
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
            IWord word = new Word(value, Collections.singletonList(decimalExp), ID);

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
