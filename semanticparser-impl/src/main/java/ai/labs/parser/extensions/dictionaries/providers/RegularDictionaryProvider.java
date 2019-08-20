package ai.labs.parser.extensions.dictionaries.providers;

import ai.labs.expressions.Expressions;
import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.lifecycle.IllegalExtensionConfigurationException;
import ai.labs.parser.extensions.dictionaries.IDictionary;
import ai.labs.parser.extensions.dictionaries.RegularDictionary;
import ai.labs.resources.rest.config.regulardictionary.model.RegularDictionaryConfiguration;
import ai.labs.resources.rest.extensions.model.ExtensionDescriptor.ConfigValue;
import ai.labs.resources.rest.extensions.model.ExtensionDescriptor.FieldType;
import ai.labs.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.runtime.service.ServiceException;
import ai.labs.utilities.RuntimeUtilities;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * @author ginccc
 */
@Slf4j
public class RegularDictionaryProvider implements IDictionaryProvider {
    public static final String ID = "ai.labs.parser.dictionaries.regular";

    private static final String KEY_URI = "uri";

    private final IResourceClientLibrary resourceClientLibrary;
    private final IExpressionProvider expressionProvider;
    private RegularDictionary regularDictionary;

    @Inject
    public RegularDictionaryProvider(IResourceClientLibrary resourceClientLibrary,
                                     IExpressionProvider expressionProvider) {
        this.resourceClientLibrary = resourceClientLibrary;
        this.expressionProvider = expressionProvider;
    }

    @Override
    public IDictionary provide() {
        return regularDictionary != null ? regularDictionary : new RegularDictionary();
    }

    @Override
    public Map<String, ConfigValue> getConfigs() {
        Map<String, ConfigValue> ret = new HashMap<>();

        ret.put(KEY_URI, new ConfigValue("Resource URI", FieldType.URI, false, null));

        return ret;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "Regular Dictionary";
    }

    @Override
    public void setConfig(Map<String, Object> config) throws IllegalExtensionConfigurationException {
        try {
            Object uriObj = config.get(KEY_URI);
            if (!RuntimeUtilities.isNullOrEmpty(uriObj) && uriObj.toString().startsWith("eddi")) {
                RegularDictionaryConfiguration regularDictionaryConfiguration =
                        fetchRegularDictionaryConfiguration(URI.create(uriObj.toString()));
                addConfigsToDictionary(regularDictionaryConfiguration);
            } else {
                throw new ServiceException("No resource URI has been defined! [RegularDictionaryConfiguration]");
            }
        } catch (ServiceException e) {
            String message = "Error while fetching RegularDictionaryConfiguration!\n" + e.getLocalizedMessage();
            throw new IllegalExtensionConfigurationException(message, e);
        }
    }

    private void addConfigsToDictionary(RegularDictionaryConfiguration regularDictionaryConfiguration) {
        regularDictionary = new RegularDictionary();
        regularDictionary.setLookupIfKnown(true);

        regularDictionaryConfiguration.getWords().forEach(wordConfig -> {
            String word = wordConfig.getWord();
            if (word != null) {
                regularDictionary.addWord(word.trim(),
                        createDefaultExpressionIfNull(word, wordConfig.getExpressions()), wordConfig.getFrequency());
            } else {
                log.warn("Value of 'word' in dictionary was null. Skipped it.");
            }
        });

        regularDictionaryConfiguration.getRegExs().forEach(regExConfig -> {
            String regEx = regExConfig.getRegEx();
            if (regEx != null) {
                regularDictionary.addRegex(regExConfig.getRegEx(),
                        createDefaultExpressionIfNull(regExConfig.getRegEx(), regExConfig.getExpressions()));
            } else {
                log.warn("Value of 'regEx' in dictionary was null. Skipped it.");
            }
        });

        regularDictionaryConfiguration.getPhrases().forEach(phraseConfig -> {
            String phrase = phraseConfig.getPhrase();
            if (phrase != null) {
                regularDictionary.addPhrase(phrase.trim(),
                        createDefaultExpressionIfNull(phrase, phraseConfig.getExpressions()));
            } else {
                log.warn("Value of 'phrase' in dictionary was null. Skipped it.");
            }
        });
    }

    private Expressions createDefaultExpressionIfNull(String value, String exp) {
        if (RuntimeUtilities.isNullOrEmpty(exp)) {
            return new Expressions(expressionProvider.createExpression("unused", value));
        }

        return expressionProvider.parseExpressions(exp);
    }

    private RegularDictionaryConfiguration fetchRegularDictionaryConfiguration(URI resourceURI)
            throws ServiceException {
        return resourceClientLibrary.getResource(resourceURI, RegularDictionaryConfiguration.class);
    }
}
