package ai.labs.parser.extensions.dictionaries.providers;

import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.parser.extensions.dictionaries.IntegerDictionary;
import ai.labs.parser.extensions.dictionaries.IDictionary;

import javax.inject.Inject;

/**
 * @author ginccc
 */
public class IntegerDictionaryProvider implements IDictionaryProvider {
    public static final String ID = "ai.labs.parser.dictionaries.integer";

    private final IExpressionProvider expressionProvider;

    @Inject
    public IntegerDictionaryProvider(IExpressionProvider expressionProvider) {
        this.expressionProvider = expressionProvider;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "Integer Dictionary";
    }

    @Override
    public IDictionary provide() {
        return new IntegerDictionary(expressionProvider);
    }
}
