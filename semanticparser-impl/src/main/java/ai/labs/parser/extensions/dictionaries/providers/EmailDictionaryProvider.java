package ai.labs.parser.extensions.dictionaries.providers;

import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.parser.extensions.dictionaries.EmailDictionary;
import ai.labs.parser.extensions.dictionaries.IDictionary;

import javax.inject.Inject;

/**
 * @author ginccc
 */
public class EmailDictionaryProvider implements IDictionaryProvider {
    public static final String ID = "ai.labs.parser.dictionaries.email";

    private final IExpressionProvider expressionProvider;

    @Inject
    public EmailDictionaryProvider(IExpressionProvider expressionProvider) {
        this.expressionProvider = expressionProvider;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "Email Dictionary";
    }

    @Override
    public IDictionary provide() {
        return new EmailDictionary(expressionProvider);
    }
}
