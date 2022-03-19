package ai.labs.eddi.modules.nlp.extensions.dictionaries.providers;


import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.DecimalDictionary;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;

import javax.inject.Inject;


/**
 * @author ginccc
 */
public class DecimalDictionaryProvider implements IDictionaryProvider {
    public static final String ID = "ai.labs.parser.dictionaries.decimal";

    private final IExpressionProvider expressionProvider;

    @Inject
    public DecimalDictionaryProvider(IExpressionProvider expressionProvider) {
        this.expressionProvider = expressionProvider;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "Decimal Dictionary";
    }

    @Override
    public IDictionary provide() {
        return new DecimalDictionary(expressionProvider);
    }
}
