package ai.labs.eddi.modules.nlp.extensions.dictionaries.providers;

import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.IntegerDictionary;

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
