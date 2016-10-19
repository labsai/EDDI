package io.sls.core.parser.dictionaries;

import io.sls.core.parser.model.FoundWord;
import io.sls.core.parser.model.IDictionary;
import io.sls.core.parser.model.Word;
import io.sls.expressions.utilities.IExpressionUtilities;

import javax.inject.Inject;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * User: jarisch
 * Date: 14.12.12
 * Time: 21:55
 */
public class EmailDictionary implements IDictionary {
    public static final String ID = "EmailDictionary";
    private final IExpressionUtilities expressionUtilities;

    private static class EmailRecognition {
        private static final Pattern email = Pattern.compile("\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,4}\\b", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    }

    @Inject
    public EmailDictionary(IExpressionUtilities expressionUtilities) {
        this.expressionUtilities = expressionUtilities;
    }

    public static boolean isEmailAddress(String value) {
        return value != null && value.contains("@") && EmailRecognition.email.matcher(value).matches();
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
            IWord emailExpression = new Word(value, Arrays.asList(expressionUtilities.createExpression("email", value)), ID);
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
