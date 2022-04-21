package ai.labs.eddi.modules.nlp.extensions.dictionaries;

import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import ai.labs.eddi.modules.nlp.model.FoundWord;
import ai.labs.eddi.modules.nlp.model.Word;
import ai.labs.eddi.utils.LanguageUtilities;

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
