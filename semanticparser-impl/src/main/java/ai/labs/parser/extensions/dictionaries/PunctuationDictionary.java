package ai.labs.parser.extensions.dictionaries;

import ai.labs.expressions.Expression;
import ai.labs.expressions.Expressions;
import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.parser.model.FoundWord;
import ai.labs.parser.model.Word;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;


/**
 * @author ginccc
 */
public class PunctuationDictionary implements IDictionary {
    private static final String ID = "punctuation";
    private static final HashMap<String, String> punctuations = new HashMap<>();

    private final IExpressionProvider expressionProvider;

    static {
        punctuations.put("!", "exclamation_mark");
        punctuations.put("?", "question_mark");
        punctuations.put(".", "dot");
        punctuations.put(",", "comma");
        punctuations.put(":", "colon");
        punctuations.put(";", "semicolon");
    }

    public PunctuationDictionary(IExpressionProvider expressionProvider) {
        this.expressionProvider = expressionProvider;
    }

    @Override
    public List<IFoundWord> lookupTerm(final String value) {
        if (punctuations.containsKey(value)) {
            Expression punctuationExpression = createPunctuationExpression(value);
            Expressions expressions = new Expressions(punctuationExpression);
            IDictionary.IWord word = new Word(value, expressions, ID);

            return Collections.singletonList(new FoundWord(word, false, 1.0));
        }

        return IDictionary.NO_WORDS_FOUND;
    }

    private Expression createPunctuationExpression(String value) {
        return expressionProvider.createExpression("punctuation", punctuations.get(value));
    }

    @Override
    public boolean lookupIfKnown() {
        return true;
    }
}
