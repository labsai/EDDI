package ai.labs.parser.dictionaries;

import ai.labs.expressions.Expression;
import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.parser.model.FoundWord;
import ai.labs.parser.model.IDictionary;
import ai.labs.parser.model.Word;

import javax.inject.Inject;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * @author ginccc
 */
public class EmailDictionary implements IDictionary {
    private static final String ID = "EmailDictionary";
    private final IExpressionProvider expressionUtilities;
    private static final Pattern emailPattern =
            Pattern.compile("\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,4}\\b",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Inject
    public EmailDictionary(IExpressionProvider expressionUtilities) {
        this.expressionUtilities = expressionUtilities;
    }

    private static boolean isEmailAddress(String value) {
        return value != null && value.contains("@") && emailPattern.matcher(value).matches();
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
        if (isEmailAddress(value)) {
            Expression emailExp = expressionUtilities.createExpression("email", value);
            IWord emailExpression = new Word(value, Collections.singletonList(emailExp), ID);
            return new IFoundWord[]{new FoundWord(emailExpression, false, 1.0)};
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
