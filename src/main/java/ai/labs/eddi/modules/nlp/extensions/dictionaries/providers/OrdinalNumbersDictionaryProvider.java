package ai.labs.eddi.modules.nlp.extensions.dictionaries.providers;


import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.OrdinalNumbersDictionary;

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
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "Ordinal Numbers Dictionary";
    }

    @Override
    public IDictionary provide() {
        return new OrdinalNumbersDictionary(expressionProvider);
    }
}
