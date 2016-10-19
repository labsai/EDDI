package io.sls.core.parser.dictionaries;

import io.sls.core.parser.model.FoundWord;
import io.sls.core.parser.model.IDictionary;
import io.sls.core.parser.model.Word;
import io.sls.expressions.Expression;
import io.sls.expressions.utilities.IExpressionUtilities;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;


/**
 * User: jarisch
 * Date: 11.11.2010
 * Time: 19:13:49
 */
public class PunctuationDictionary implements IDictionary {
    private static final String ID = "punctuation";
    private final IExpressionUtilities expressionUtilities;
    private HashMap<String, String> punctuations = new HashMap<String, String>();

    public PunctuationDictionary(IExpressionUtilities expressionUtilities) {
        this.expressionUtilities = expressionUtilities;

        punctuations.put("!", "exclamation_mark");
        punctuations.put("?", "question_mark");
        punctuations.put(".", "dot");
        punctuations.put(",", "comma");
        punctuations.put(":", "colon");
        punctuations.put(";", "semicolon");
    }

    @Override
    public List<IDictionary.IWord> getWords() {
        return Collections.emptyList();
    }

    @Override
    public List<IDictionary.IPhrase> getPhrases() {
        return Collections.emptyList();
    }

    @Override
    public IFoundWord[] lookupTerm(final String value) {
        if (punctuations.containsKey(value)) {
            List<Expression> expressions = Arrays.asList(expressionUtilities.createExpression("punctuation", punctuations.get(value)));
            IDictionary.IWord word = new Word(value, expressions, ID);

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
