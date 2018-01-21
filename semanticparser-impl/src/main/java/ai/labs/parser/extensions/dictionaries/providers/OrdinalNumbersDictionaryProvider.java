package ai.labs.parser.extensions.dictionaries.providers;

import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.parser.extensions.dictionaries.OrdinalNumbersDictionary;
import ai.labs.parser.extensions.dictionaries.IDictionary;

import javax.inject.Inject;

/**
 * @author ginccc
 */
public class OrdinalNumbersDictionaryProvider implements IDictionaryProvider {
    public static final String ID = "ai.labs.parser.dictionaries.ordinalNumber";

    private final IExpressionProvider expressionProvider;

    @Inject
    public OrdinalNumbersDictionaryProvider(IExpressionProvider expressionProvider) {
        this.expressionProvider = expressionProvider;
    }

    @Override
    public IDictionary provide() {
        return new OrdinalNumbersDictionary(expressionProvider);
    }
}
