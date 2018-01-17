package ai.labs.parser;

import ai.labs.expressions.Expression;
import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.lifecycle.*;
import ai.labs.memory.Data;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;
import ai.labs.output.model.QuickReply;
import ai.labs.parser.correction.*;
import ai.labs.parser.dictionaries.*;
import ai.labs.parser.internal.InputParser;
import ai.labs.parser.internal.matches.RawSolution;
import ai.labs.parser.model.IDictionary;
import ai.labs.parser.rest.model.Solution;
import ai.labs.resources.rest.regulardictionary.model.RegularDictionaryConfiguration;
import ai.labs.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.runtime.service.ServiceException;
import ai.labs.utilities.RuntimeUtilities;
import ai.labs.utilities.StringUtilities;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

import static ai.labs.parser.DictionaryUtilities.convertQuickReplies;
import static ai.labs.parser.DictionaryUtilities.extractExpressions;

/**
 * @author ginccc
 */
@Slf4j
public class InputParserTask implements ILifecycleTask {
    private IInputParser sentenceParser;
    private List<IDictionary> dictionaries;
    private List<ICorrection> corrections;
    private IResourceClientLibrary resourceClientLibrary;

    private static final String regularDictionaryURI = "eddi://ai.labs.parser.dictionaries.regular";
    private static final String damerauLevenshteinURI = "eddi://ai.labs.parser.corrections.levenshtein";
    private static final String integerDictionaryURI = "eddi://ai.labs.parser.dictionaries.integer";
    private static final String decimalDictionaryURI = "eddi://ai.labs.parser.dictionaries.decimal";
    private static final String emailDictionaryURI = "eddi://ai.labs.parser.dictionaries.email";
    private static final String punctuationDictionaryURI = "eddi://ai.labs.parser.dictionaries.punctuation";
    private static final String timeExpressionDictionaryURI = "eddi://ai.labs.parser.dictionaries.time";

    private static final String ordinalNumberDictionaryURI = "eddi://ai.labs.parser.dictionaries.ordinalNumber";
    private static final String stemmingCorrectionURI = "eddi://ai.labs.parser.corrections.stemming";
    private static final String phoneticCorrectionURI = "eddi://ai.labs.parser.corrections.phonetic";
    private static final String mergedTermsCorrectionURI = "eddi://ai.labs.parser.corrections.mergedTerms";

    private static final String KEY_EXPRESSIONS_PARSED = "expressions:parsed";
    private static final String KEY_TYPE = "type";
    private static final String KEY_CONFIG = "config";
    private static final String KEY_URI = "uri";
    private static final String KEY_DISTANCE = "distance";
    private static final String KEY_LOOKUP_IF_KNOWN = "lookupIfKnown";
    private static final String KEY_LANGUAGE = "language";
    private IExpressionProvider expressionProvider;
    private boolean appendExpressions = true;
    private boolean includeUnused = true;
    private boolean includeUnknown = true;

    @Inject
    public InputParserTask(IResourceClientLibrary resourceClientLibrary,
                           IExpressionProvider expressionProvider) {
        this.resourceClientLibrary = resourceClientLibrary;
        this.expressionProvider = expressionProvider;
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
    public void init() {
        this.sentenceParser = new InputParser(dictionaries, corrections);
    }

    @Override
    public void executeTask(IConversationMemory memory) throws LifecycleException {
        //parse user input to meanings
        final IData<String> inputData = memory.getCurrentStep().getLatestData("input");
        if (inputData == null) {
            return;
        }

        List<IDictionary> temporaryDictionaries = prepareTemporaryDictionaries(memory);
        List<RawSolution> parsedSolutions;
        try {
            parsedSolutions = sentenceParser.parse(inputData.getResult(), temporaryDictionaries);
        } catch (InterruptedException e) {
            log.warn(e.getLocalizedMessage(), e);
            return;
        }
        storeResultInMemory(memory, parsedSolutions);
    }

    private List<IDictionary> prepareTemporaryDictionaries(IConversationMemory memory) {
        IConversationMemory.IConversationStepStack previousSteps = memory.getPreviousSteps();
        List<IDictionary> temporaryDictionaries = Collections.emptyList();
        if (previousSteps.size() > 0) {
            List<IData<List<Map<String, String>>>> data = previousSteps.get(0).getAllData("quickReplies");
            if (data != null) {
                List<QuickReply> quickReplies = extractQuickReplies(data);
                temporaryDictionaries = convertQuickReplies(quickReplies, expressionProvider);
            }
        }
        return temporaryDictionaries;
    }

    private List<QuickReply> extractQuickReplies(List<IData<List<Map<String, String>>>> quickReplyDataList) {
        List<QuickReply> ret = new LinkedList<>();
        quickReplyDataList.stream().
                filter(Objects::nonNull).
                filter(IData::isPublic).
                forEach((quickReplyData) -> {
                    List<Map<String, String>> resultList = quickReplyData.getResult();
                    ret.addAll(resultList.stream().
                            map((resultMap) -> new QuickReply(resultMap.get("value"),
                                    resultMap.get("expressions"), Boolean.parseBoolean(resultMap.get("isDefault")))).
                            collect(Collectors.toList()));

                });

        return ret;
    }

    private void storeResultInMemory(IConversationMemory memory, List<RawSolution> parsedSolutions) {
        if (!parsedSolutions.isEmpty()) {
            Solution solution = extractExpressions(parsedSolutions, includeUnused, includeUnknown).get(0);

            String expressions = solution.getExpressions();
            if (appendExpressions && !expressions.isEmpty()) {
                IData<String> latestExpressions = memory.getCurrentStep().getLatestData(KEY_EXPRESSIONS_PARSED);
                if (latestExpressions != null) {
                    expressions = StringUtilities.joinStrings(", ", latestExpressions.getResult(), expressions);
                }

                IData<String> expressionsData = new Data<>(KEY_EXPRESSIONS_PARSED, expressions);
                memory.getCurrentStep().storeData(expressionsData);
            }
        }
    }

    @Override
    public void setExtensions(Map<String, Object> extensions) throws
            UnrecognizedExtensionException, IllegalExtensionConfigurationException {
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

    @Override
    public void configure(Map<String, Object> configuration) {
        Object appendExpressions = configuration.get("appendExpressions");
        if (!RuntimeUtilities.isNullOrEmpty(appendExpressions)) {
            this.appendExpressions = Boolean.parseBoolean(appendExpressions.toString());
        }

        Object includeUnused = configuration.get("includeUnused");
        if (!RuntimeUtilities.isNullOrEmpty(includeUnused)) {
            this.includeUnused = Boolean.parseBoolean(includeUnused.toString());
        }

        Object includeUnknown = configuration.get("includeUnknown");
        if (!RuntimeUtilities.isNullOrEmpty(includeUnknown)) {
            this.includeUnknown = Boolean.parseBoolean(includeUnknown.toString());
        }
    }

    private void convertDictionaries(List<Map<String, Object>> dictionariesList)
            throws IllegalExtensionConfigurationException, UnrecognizedDictionaryException {
        IDictionary dictionary;
        for (Map<String, Object> dictionaryMap : dictionariesList) {
            dictionary = null;
            String dictionaryType = dictionaryMap.get(KEY_TYPE).toString();
            if (dictionaryType.startsWith(regularDictionaryURI)) {
                dictionary = createRegularDictionary(dictionaryMap);
            } else if (dictionaryType.startsWith(integerDictionaryURI)) {
                dictionary = new IntegerDictionary(expressionProvider);
            } else if (dictionaryType.startsWith(decimalDictionaryURI)) {
                dictionary = new DecimalDictionary(expressionProvider);
            } else if (dictionaryType.startsWith(emailDictionaryURI)) {
                dictionary = new EmailDictionary(expressionProvider);
            } else if (dictionaryType.startsWith(punctuationDictionaryURI)) {
                dictionary = new PunctuationDictionary(expressionProvider);
            } else if (dictionaryType.startsWith(ordinalNumberDictionaryURI)) {
                dictionary = new OrdinalNumbersDictionary(expressionProvider);
            } else if (dictionaryType.startsWith(timeExpressionDictionaryURI)) {
                dictionary = new TimeExpressionDictionary(expressionProvider);
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
            if (uriString.startsWith("eddi")) {
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

    private List<Expression> createDefaultExpressionIfNull(String value, String exp) {
        if (RuntimeUtilities.isNullOrEmpty(exp)) {
            return Collections.singletonList(expressionProvider.createExpression("unused", value));
        }

        return expressionProvider.parseExpressions(exp);
    }

    private class UnrecognizedDictionaryException extends UnrecognizedExtensionException {
        UnrecognizedDictionaryException(String message) {
            super(message);
        }
    }

    private class UnrecognizedCorrectionException extends UnrecognizedExtensionException {
        UnrecognizedCorrectionException(String message) {
            super(message);
        }
    }

    private class ConfigParamMissingException extends IllegalExtensionConfigurationException {
        ConfigParamMissingException(String message) {
            super(message);
        }
    }
}
