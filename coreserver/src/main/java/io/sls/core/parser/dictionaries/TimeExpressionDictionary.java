package io.sls.core.parser.dictionaries;

import io.sls.core.parser.model.FoundWord;
import io.sls.core.parser.model.IDictionary;
import io.sls.core.parser.model.Word;
import io.sls.core.utilities.LanguageUtilities;
import io.sls.expressions.utilities.IExpressionUtilities;

import javax.inject.Inject;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * Created by IntelliJ IDEA.
 * User: jarisch
 * Date: 14.06.2010
 * Time: 17:24:11
 */
public class TimeExpressionDictionary implements IDictionary {
    public static final String ID = "TimeExpressionDictionary";
    private final IExpressionUtilities expressionUtilities;

    @Inject
    public TimeExpressionDictionary(IExpressionUtilities expressionUtilities) {
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
        final Date timeAsDate = LanguageUtilities.isTimeExpression(value);

        if (timeAsDate != null) {
            final String timeString = timeAsDate.toString();
            IWord timeExpression = new Word(timeString, Arrays.asList(expressionUtilities.createExpression("time", timeAsDate.getTime())), ID);
            return new IFoundWord[]{new FoundWord(timeExpression, false, 1.0)};
        }

        return IDictionary.NO_WORDS_FOUND;
    }

    @Override
    public String getLanguage() {
        return "time expression";
    }

    @Override
    public boolean lookupIfKnown() {
        return true;
    }
}
