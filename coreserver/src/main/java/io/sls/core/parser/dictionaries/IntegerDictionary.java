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
 * @author ginccc
 */
public class IntegerDictionary implements IDictionary {
    private static final String ID = "integer";
    private final IExpressionUtilities expressionUtilities;

    @Inject
    public IntegerDictionary(IExpressionUtilities expressionUtilities) {
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
    public IFoundWord[] lookupTerm(final String value) {
        if (CharacterUtilities.isStringInteger(value)) {
            List<Expression> expressions = Arrays.asList(expressionUtilities.createExpression("integer", value));
            IWord word = new Word(value, expressions, ID);

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
        return true;
    }
}
