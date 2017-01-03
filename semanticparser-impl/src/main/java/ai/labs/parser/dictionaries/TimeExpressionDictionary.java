package ai.labs.parser.dictionaries;

import ai.labs.parser.model.FoundWord;
import ai.labs.parser.model.IDictionary;
import ai.labs.parser.model.Word;
import io.sls.core.utilities.LanguageUtilities;
import io.sls.expressions.Expression;
import io.sls.expressions.utilities.IExpressionUtilities;

import javax.inject.Inject;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * @author ginccc
 */
public class TimeExpressionDictionary implements IDictionary {
    private static final String ID = "TimeExpressionDictionary";
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
            Expression timeExp = expressionUtilities.createExpression("time", timeAsDate.getTime());
            IWord timeExpression = new Word(timeString, Collections.singletonList(timeExp), ID);
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
