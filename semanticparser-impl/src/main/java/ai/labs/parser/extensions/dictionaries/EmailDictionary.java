package ai.labs.parser.extensions.dictionaries;

import ai.labs.expressions.Expression;
import ai.labs.expressions.Expressions;
import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.parser.model.FoundWord;
import ai.labs.parser.model.Word;

import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * @author ginccc
 */
public class EmailDictionary implements IDictionary {
    private static final String ID = "EmailDictionary";
    private final IExpressionProvider expressionProvider;
    private static final Pattern emailPattern =
            Pattern.compile("\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,4}\\b",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    public EmailDictionary(IExpressionProvider expressionProvider) {
        this.expressionProvider = expressionProvider;
    }

    private static boolean isEmailAddress(String value) {
        return value != null && value.contains("@") && emailPattern.matcher(value).matches();
    }

    @Override
    public List<IFoundWord> lookupTerm(String value) {
        if (isEmailAddress(value)) {
            Expression emailExp = expressionProvider.createExpression("email", value);
            IWord emailExpression = new Word(value, new Expressions(emailExp), ID);
            return Collections.singletonList(new FoundWord(emailExpression, false, 1.0));
        }

        return IDictionary.NO_WORDS_FOUND;
    }

    @Override
    public boolean lookupIfKnown() {
        return false;
    }
}
