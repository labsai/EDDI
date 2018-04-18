package ai.labs.parser.extensions.dictionaries.providers;

import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.parser.extensions.dictionaries.PunctuationDictionary;
import ai.labs.parser.extensions.dictionaries.IDictionary;

import javax.inject.Inject;


/**
 * @author ginccc
 */
public class PunctuationDictionaryProvider implements IDictionaryProvider {
    public static final String ID = "ai.labs.parser.dictionaries.punctuation";

    private final IExpressionProvider expressionProvider;

    @Inject
    public PunctuationDictionaryProvider(IExpressionProvider expressionProvider) {
        this.expressionProvider = expressionProvider;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "Punctuation Dictionary";
    }

    @Override
    public IDictionary provide() {
        return new PunctuationDictionary(expressionProvider);
    }
}
