package ai.labs.parser.extensions.dictionaries.providers;

import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.parser.extensions.dictionaries.TimeExpressionDictionary;
import ai.labs.parser.extensions.dictionaries.IDictionary;

import javax.inject.Inject;

/**
 * @author ginccc
 */
public class TimeExpressionDictionaryProvider implements IDictionaryProvider {
    public static final String ID = "ai.labs.parser.dictionaries.time";

    private final IExpressionProvider expressionProvider;

    @Inject
    public TimeExpressionDictionaryProvider(IExpressionProvider expressionProvider) {
        this.expressionProvider = expressionProvider;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "Time Expression Dictionary";
    }

    @Override
    public IDictionary provide() {
        return new TimeExpressionDictionary(expressionProvider);
    }
}
