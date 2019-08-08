package ai.labs.parser.extensions.dictionaries;

import ai.labs.expressions.Expression;
import ai.labs.expressions.Expressions;
import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.parser.model.FoundWord;
import ai.labs.parser.model.Word;
import ai.labs.utilities.LanguageUtilities;

import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * @author ginccc
 */
public class TimeExpressionDictionary implements IDictionary {
    private static final String ID = "TimeExpressionDictionary";
    private final IExpressionProvider expressionProvider;

    public TimeExpressionDictionary(IExpressionProvider expressionProvider) {
        this.expressionProvider = expressionProvider;
    }

    @Override
    public List<IFoundWord> lookupTerm(String value) {
        final Date timeAsDate = LanguageUtilities.isTimeExpression(value);

        if (timeAsDate != null) {
            final String timeString = timeAsDate.toString();
            Expression timeExp = expressionProvider.createExpression("time", timeAsDate.getTime());
            IWord timeExpression = new Word(timeString, new Expressions(timeExp), ID);
            return Collections.singletonList(new FoundWord(timeExpression, false, 1.0));
        }

        return IDictionary.NO_WORDS_FOUND;
    }

    @Override
    public boolean lookupIfKnown() {
        return true;
    }
}
