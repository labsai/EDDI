package ai.labs.eddi.modules.nlp.extensions.dictionaries.providers;

import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.TimeExpressionDictionary;
import io.quarkus.runtime.Startup;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

/**
 * @author ginccc
 */
@Startup
@ApplicationScoped
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
