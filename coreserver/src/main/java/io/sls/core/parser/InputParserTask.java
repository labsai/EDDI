package io.sls.core.parser;

import io.sls.core.lifecycle.*;
import io.sls.core.parser.correction.*;
import io.sls.core.parser.dictionaries.*;
import io.sls.core.parser.internal.InputParser;
import io.sls.core.parser.internal.matches.Solution;
import io.sls.core.parser.model.IDictionary;
import io.sls.core.runtime.client.configuration.IResourceClientLibrary;
import io.sls.core.runtime.service.ServiceException;
import io.sls.expressions.Expression;
import io.sls.expressions.utilities.IExpressionUtilities;
import io.sls.memory.IConversationMemory;
import io.sls.memory.IData;
import io.sls.memory.impl.Data;
import io.sls.resources.rest.regulardictionary.model.RegularDictionaryConfiguration;
import io.sls.utilities.CharacterUtilities;
import io.sls.utilities.RuntimeUtilities;

import javax.inject.Inject;
import java.net.URI;
import java.util.*;

/**
 * @author ginccc
 */
public class InputParserTask extends AbstractLifecycleTask implements ILifecycleTask {
    private IInputParser sentenceParser;
    private List<IDictionary> dictionaries;
    private List<ICorrection> corrections;
    private IResourceClientLibrary resourceClientLibrary;
    private final String regularDictionaryURI = "core://io.sls.parser.dictionaries.regular";
    private final String damerauLevenshteinURI = "core://io.sls.parser.corrections.levenshtein";
    private final String integerDictionaryURI = "core://io.sls.parser.dictionaries.integer";
    private final String decimalDictionaryURI = "core://io.sls.parser.dictionaries.decimal";
    private final String emailDictionaryURI = "core://io.sls.parser.dictionaries.email";
    private final String punctuationDictionaryURI = "core://io.sls.parser.dictionaries.punctuation";
    private final String timeExpressionDictionaryURI = "core://io.sls.parser.dictionaries.time";

    private final String ordinalNumberDictionaryURI = "core://io.sls.parser.dictionaries.ordinalNumber";
    private final String stemmingCorrectionURI = "core://io.sls.parser.corrections.stemming";
    private final String phoneticCorrectionURI = "core://io.sls.parser.corrections.phonetic";
    private final String mergedTermsCorrectionURI = "core://io.sls.parser.corrections.mergedTerms";
    private final String KEY_TYPE = "type";
    private final String KEY_CONFIG = "config";
    private final String KEY_URI = "uri";
    private final String KEY_DISTANCE = "distance";
    private final String KEY_LOOKUP_IF_KNOWN = "lookupIfKnown";
    private final String KEY_LANGUAGE = "language";
    private IExpressionUtilities expressionUtilities;

    @Inject
    public InputParserTask(IResourceClientLibrary resourceClientLibrary,
                           IExpressionUtilities expressionUtilities) {
        this.resourceClientLibrary = resourceClientLibrary;
        this.expressionUtilities = expressionUtilities;
    }

    @Override
    public String getId() {
        return sentenceParser.getClass().toString();
    }

    @Override
    public Object getComponent() {
        return sentenceParser;
    }

    @Override
    public List<String> getComponentDependencies() {
        return Collections.emptyList();
    }

    @Override
    public List<String> getOutputDependencies() {
        return Collections.emptyList();
    }

    @Override
    public void init() {
        this.sentenceParser = new InputParser(dictionaries, corrections);
    }

    @Override
    public void executeTask(IConversationMemory memory) throws LifecycleException {
        //parse user input to meanings
        IData data = memory.getCurrentStep().getLatestData("input");
        if (data == null) {
            return;
        }
        String input = (String) data.getResult();
        List<Solution> parsedSuggestions = sentenceParser.parse(input);

        //store result in memory
        if (!parsedSuggestions.isEmpty()) {
            List<Expression> expressions = convertDictionaryEntriesToExpressions(parsedSuggestions.get(0).getDictionaryEntries());
            data = new Data("expressions:parsed", CharacterUtilities.arrayToString(expressions));
            memory.getCurrentStep().storeData(data);
        }
    }

    private List<Expression> convertDictionaryEntriesToExpressions(List<IDictionary.IFoundWord> dictionaryEntries) {
        List<Expression> expressions = new LinkedList<>();

        for (IDictionary.IDictionaryEntry dictionaryEntry : dictionaryEntries) {
            expressions.addAll(dictionaryEntry.getExpressions());
        }

        return expressions;
    }

    @Override
    public void setExtensions(Map<String, Object> extensions) throws UnrecognizedExtensionException, IllegalExtensionConfigurationException {
        List<Map<String, Object>> dictionariesList = (List<Map<String, Object>>) extensions.get("dictionaries");
        dictionaries = new LinkedList<>();
        if (dictionariesList != null) {
            convertDictionaries(dictionariesList);
        }

        corrections = new LinkedList<>();
        List<Map<String, Object>> correctionsList = (List<Map<String, Object>>) extensions.get("corrections");
        if (correctionsList != null) {
            convertCorrections(correctionsList);
        }
    }

    private void convertDictionaries(List<Map<String, Object>> dictionariesList) throws IllegalExtensionConfigurationException, UnrecognizedDictionaryException {
        IDictionary dictionary;
        for (Map<String, Object> dictionaryMap : dictionariesList) {
            dictionary = null;
            String dictionaryType = dictionaryMap.get(KEY_TYPE).toString();
            if (dictionaryType.startsWith(regularDictionaryURI)) {
                dictionary = createRegularDictionary(dictionaryMap);
            } else if (dictionaryType.startsWith(integerDictionaryURI)) {
                dictionary = new IntegerDictionary(expressionUtilities);
            } else if (dictionaryType.startsWith(decimalDictionaryURI)) {
                dictionary = new DecimalDictionary(expressionUtilities);
            } else if (dictionaryType.startsWith(emailDictionaryURI)) {
                dictionary = new EmailDictionary(expressionUtilities);
            } else if (dictionaryType.startsWith(punctuationDictionaryURI)) {
                dictionary = new PunctuationDictionary(expressionUtilities);
            } else if (dictionaryType.startsWith(ordinalNumberDictionaryURI)) {
                dictionary = new OrdinalNumbersDictionary(expressionUtilities);
            } else if (dictionaryType.startsWith(timeExpressionDictionaryURI)) {
                dictionary = new TimeExpressionDictionary(expressionUtilities);
            }

            if (dictionary == null) {
                String message = "Dictionary type could not be recognized by Parser [type=%s]";
                message = String.format(message, dictionaryType);
                throw new UnrecognizedDictionaryException(message);
            }

            dictionaries.add(dictionary);
        }
    }

    private IDictionary createRegularDictionary(Map<String, Object> dictionaryMap) throws IllegalExtensionConfigurationException {
        IDictionary dictionary;
        checkIfConfigExists(dictionaryMap, regularDictionaryURI);
        Object config = dictionaryMap.get(KEY_CONFIG);
        checkIfMap(config, KEY_CONFIG, regularDictionaryURI);

        try {
            Map<String, Object> configMap = (Map<String, Object>) config;
            String uriString = configMap.get(KEY_URI).toString();
            if (uriString.startsWith("resource")) {
                URI regDictURI = URI.create(uriString);
                RegularDictionaryConfiguration regularDictionaryConfiguration = fetchRegularDictionaryConfiguration(regDictURI);
                dictionary = convert(regularDictionaryConfiguration);
                return dictionary;
            } else {
                throw new ServiceException("No resource URI has been defined! [RegularDictionaryConfiguration]");
            }
        } catch (ServiceException e) {
            String message = "Error while fetching RegularDictionaryConfiguration!\n" + e.getLocalizedMessage();
            throw new IllegalExtensionConfigurationException(message, e);
        }
    }

    private RegularDictionary convert(RegularDictionaryConfiguration regularDictionaryConfiguration) {
        RegularDictionary regularDictionary = new RegularDictionary(regularDictionaryConfiguration.getLanguage(), true);
        for (RegularDictionaryConfiguration.WordConfiguration wordConfiguration : regularDictionaryConfiguration.getWords()) {
            String word = wordConfiguration.getWord();
            regularDictionary.addWord(word, createDefaultExpressionIfNull(word, wordConfiguration.getExp()), wordConfiguration.getFrequency());
        }

        for (RegularDictionaryConfiguration.PhraseConfiguration phraseConfiguration : regularDictionaryConfiguration.getPhrases()) {
            String phrase = phraseConfiguration.getPhrase();
            regularDictionary.addPhrase(phrase, createDefaultExpressionIfNull(phrase, phraseConfiguration.getExp()));
        }

        return regularDictionary;
    }

    private RegularDictionaryConfiguration fetchRegularDictionaryConfiguration(URI resourceURI) throws ServiceException {
        return resourceClientLibrary.getResource(resourceURI, RegularDictionaryConfiguration.class);
    }

    private void convertCorrections(List<Map<String, Object>> correctionsList) throws UnrecognizedCorrectionException, IllegalExtensionConfigurationException {
        ICorrection correction;
        for (Map<String, Object> correctionMap : correctionsList) {
            correction = null;
            String correctionType = correctionMap.get(KEY_TYPE).toString();
            if (correctionType.startsWith(damerauLevenshteinURI)) {
                correction = createDamerauLevenshteinCorrection(correctionMap);
            } else if (correctionType.startsWith(stemmingCorrectionURI)) {
                correction = createStemmingCorrection(correctionMap);
            } else if (correctionType.startsWith(phoneticCorrectionURI)) {
                correction = createPhoneticCorrection(correctionMap);
            } else if (correctionType.startsWith(mergedTermsCorrectionURI)) {
                correction = new MergedTermsCorrection();
            }

            if (correction == null) {
                String message = "Correction type could not be recognized by Parser [type=%s]";
                message = String.format(message, correctionType);
                throw new UnrecognizedCorrectionException(message);
            }

            correction.init(dictionaries);
            corrections.add(correction);
        }
    }

    private ICorrection createDamerauLevenshteinCorrection(Map<String, Object> correctionMap) throws IllegalExtensionConfigurationException {
        checkIfConfigExists(correctionMap, damerauLevenshteinURI);
        Object configObj = correctionMap.get(KEY_CONFIG);
        checkIfMap(configObj, KEY_CONFIG, damerauLevenshteinURI);
        Map<String, Object> config = (Map<String, Object>) configObj;

        Object distanceObj = config.get(KEY_DISTANCE);
        Integer distance;
        if (distanceObj == null) {
            distance = 2;
        } else {
            distance = Integer.valueOf((String) distanceObj);
        }

        Boolean lookupIfKnown = extractLookupIfKnownParam(config);

        return new DamerauLevenshteinCorrection(distance, lookupIfKnown);
    }

    private ICorrection createStemmingCorrection(Map<String, Object> correctionMap) throws IllegalExtensionConfigurationException {
        checkIfConfigExists(correctionMap, stemmingCorrectionURI);
        Object configObj = correctionMap.get(KEY_CONFIG);
        checkIfMap(configObj, KEY_CONFIG, stemmingCorrectionURI);
        Map<String, Object> config = (Map<String, Object>) configObj;

        Object languageObj = config.get(KEY_LANGUAGE);
        String language;
        if (languageObj == null) {
            throw new ConfigParamMissingException("Param 'language' is not defined. [StemmingCorrection]");
        } else {
            language = (String) languageObj;
        }

        Boolean lookupIfKnown = extractLookupIfKnownParam(config);

        return new StemmingCorrection(language, lookupIfKnown);
    }

    private ICorrection createPhoneticCorrection(Map<String, Object> correctionMap) throws IllegalExtensionConfigurationException {
        checkIfConfigExists(correctionMap, phoneticCorrectionURI);
        Object configObj = correctionMap.get(KEY_CONFIG);
        checkIfMap(configObj, KEY_CONFIG, phoneticCorrectionURI);
        Map<String, Object> config = (Map<String, Object>) configObj;

        Boolean lookupIfKnown = extractLookupIfKnownParam(config);

        return new PhoneticCorrection(lookupIfKnown);
    }

    private Boolean extractLookupIfKnownParam(Map<String, Object> config) {
        Object lookupIfKnownObj = config.get(KEY_LOOKUP_IF_KNOWN);
        Boolean lookupIfKnown;
        if (lookupIfKnownObj == null) {
            lookupIfKnown = false;
        } else {
            lookupIfKnown = Boolean.valueOf((String) lookupIfKnownObj);
        }
        return lookupIfKnown;
    }


    private void checkIfConfigExists(Map<String, Object> map, String type) throws IllegalExtensionConfigurationException {
        if (!map.containsKey(KEY_CONFIG)) {
            String message = "Key: 'config' does not exist! [%s]";
            message = String.format(message, type);
            throw new IllegalExtensionConfigurationException(message);
        }
    }

    private void checkIfMap(Object obj, String key, String type) throws IllegalExtensionConfigurationException {
        if (!(obj instanceof Map)) {
            String message = "%s has to be a map [%s]";
            message = String.format(message, key, type);
            throw new IllegalExtensionConfigurationException(message);
        }
    }

    @Override
    public void configure(Map<String, Object> configuration) throws PackageConfigurationException {
        //no configurations for sentenceParser
    }

    private List<Expression> createDefaultExpressionIfNull(String value, String exp) {
        if (RuntimeUtilities.isNullOrEmpty(exp)) {
            return Arrays.asList(expressionUtilities.createExpression("unused", value));
        }

        return expressionUtilities.parseExpressions(exp);
    }

    private class UnrecognizedDictionaryException extends UnrecognizedExtensionException {
        public UnrecognizedDictionaryException(String message) {
            super(message);
        }
    }

    private class UnrecognizedCorrectionException extends UnrecognizedExtensionException {
        public UnrecognizedCorrectionException(String message) {
            super(message);
        }
    }

    private class ConfigParamMissingException extends IllegalExtensionConfigurationException {
        public ConfigParamMissingException(String message) {
            super(message);
        }
    }
}
