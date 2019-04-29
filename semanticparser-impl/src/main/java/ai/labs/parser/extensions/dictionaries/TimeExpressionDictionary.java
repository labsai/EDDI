package ai.labs.parser.extensions.dictionaries;

import ai.labs.expressions.Expression;
import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.parser.model.FoundWord;
import ai.labs.parser.model.Word;
import ai.labs.utilities.LanguageUtilities;

import java.util.ArrayList;
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
            Expression hourExp = expressionProvider.createExpression("hour", timeAsDate.getHours());
            Expression minuteExp = expressionProvider.createExpression("minute", timeAsDate.getMinutes());
            Expression secondExp = expressionProvider.createExpression("second", timeAsDate.getSeconds());
            IWord timeExpression = new Word(timeString, Collections.singletonList(timeExp), ID);
            IWord hourExpression = new Word(String.valueOf(timeAsDate.getHours()), Collections.singletonList(hourExp), ID);
            IWord minuteExpression = new Word(String.valueOf(timeAsDate.getMinutes()), Collections.singletonList(minuteExp), ID);
            IWord secondExpression = new Word(String.valueOf(timeAsDate.getSeconds()), Collections.singletonList(secondExp), ID);
            List<IFoundWord> foundWords = new ArrayList<>();
            foundWords.add(new FoundWord(timeExpression, false, 1.0));
            foundWords.add(new FoundWord(hourExpression, false, 1.0));
            foundWords.add(new FoundWord(minuteExpression, false, 1.0));
            foundWords.add(new FoundWord(secondExpression, false, 1.0));
            return foundWords;
        }

        return IDictionary.NO_WORDS_FOUND;
    }

    @Override
    public boolean lookupIfKnown() {
        return true;
    }
}
