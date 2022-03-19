package ai.labs.eddi.modules.nlp.extensions.dictionaries;

import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import ai.labs.eddi.modules.nlp.model.FoundWord;
import ai.labs.eddi.modules.nlp.model.Word;

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
