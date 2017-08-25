package ai.labs.parser.dictionaries;

import ai.labs.expressions.Expression;
import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.parser.model.FoundWord;
import ai.labs.parser.model.IDictionary;
import ai.labs.parser.model.Word;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;


/**
 * @author ginccc
 */
public class PunctuationDictionary implements IDictionary {
    private static final String ID = "punctuation";
    private final IExpressionProvider expressionUtilities;
    private HashMap<String, String> punctuations = new HashMap<>();

    public PunctuationDictionary(IExpressionProvider expressionUtilities) {
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
    public List<IFoundWord> lookupTerm(final String value) {
        if (punctuations.containsKey(value)) {
            Expression punctuationExp = expressionUtilities.createExpression("punctuation", punctuations.get(value));
            List<Expression> expressions = Collections.singletonList(punctuationExp);
            IDictionary.IWord word = new Word(value, expressions, ID);

            return Collections.singletonList(new FoundWord(word, false, 1.0));
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
