package ai.labs.eddi.modules.nlp;

import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.exceptions.IllegalExtensionConfigurationException;
import ai.labs.eddi.engine.lifecycle.exceptions.PackageConfigurationException;
import ai.labs.eddi.engine.lifecycle.exceptions.UnrecognizedExtensionException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.models.ExtensionDescriptor;
import ai.labs.eddi.models.ExtensionDescriptor.ConfigValue;
import ai.labs.eddi.modules.nlp.bootstrap.ParserCorrectionExtensions;
import ai.labs.eddi.modules.nlp.bootstrap.ParserDictionaryExtensions;
import ai.labs.eddi.modules.nlp.bootstrap.ParserNormalizerExtensions;
import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import ai.labs.eddi.modules.nlp.extensions.corrections.ICorrection;
import ai.labs.eddi.modules.nlp.extensions.corrections.providers.ICorrectionProvider;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.providers.IDictionaryProvider;
import ai.labs.eddi.modules.nlp.extensions.normalizers.INormalizer;
import ai.labs.eddi.modules.nlp.extensions.normalizers.providers.INormalizerProvider;
import ai.labs.eddi.modules.nlp.internal.InputParser;
import ai.labs.eddi.modules.nlp.internal.matches.RawSolution;
import ai.labs.eddi.modules.output.model.QuickReply;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

import static ai.labs.eddi.engine.memory.ContextUtilities.retrieveContextLanguageFromLongTermMemory;
import static ai.labs.eddi.models.ExtensionDescriptor.FieldType.BOOLEAN;
import static ai.labs.eddi.modules.nlp.DictionaryUtilities.convertQuickReplies;
import static ai.labs.eddi.modules.nlp.DictionaryUtilities.extractExpressions;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;
import static ai.labs.eddi.utils.StringUtilities.joinStrings;


/**
 * @author ginccc
 */

@ApplicationScoped
public class InputParserTask implements ILifecycleTask {
    public static final String ID = "ai.labs.parser";
    private static final String CONFIG_APPEND_EXPRESSIONS = "appendExpressions";
    private static final String CONFIG_INCLUDE_UNUSED = "includeUnused";
    private static final String CONFIG_INCLUDE_UNKNOWN = "includeUnknown";
    private static final String EXTENSION_NAME_NORMALIZER = "normalizer";
    private static final String EXTENSION_NAME_DICTIONARIES = "dictionaries";
    private static final String EXTENSION_NAME_CORRECTIONS = "corrections";
    private final ObjectMapper objectMapper;

    private static final String KEY_INPUT = "input";
    private static final String KEY_INPUT_NORMALIZED = KEY_INPUT + ":normalized";
    private static final String KEY_EXPRESSIONS = "expressions";
    private static final String KEY_EXPRESSIONS_PARSED = KEY_EXPRESSIONS + ":parsed";
    private static final String KEY_INTENT = "intents";
    private static final String KEY_TYPE = "type";
    private static final String KEY_CONFIG = "config";

    private final IExpressionProvider expressionProvider;
    private final Map<String, Provider<INormalizerProvider>> normalizerProviders;
    private final Map<String, Provider<IDictionaryProvider>> dictionaryProviders;
    private final Map<String, Provider<ICorrectionProvider>> correctionProviders;

    private static final Logger log = Logger.getLogger(InputParserTask.class);

    @Inject
    public InputParserTask(IExpressionProvider expressionProvider,
                           @ParserNormalizerExtensions Map<String, Provider<INormalizerProvider>> normalizerProviders,
                           @ParserDictionaryExtensions Map<String, Provider<IDictionaryProvider>> dictionaryProviders,
                           @ParserCorrectionExtensions Map<String, Provider<ICorrectionProvider>> correctionProviders,
                           ObjectMapper objectMapper) {
        this.expressionProvider = expressionProvider;
        this.normalizerProviders = normalizerProviders;
        this.dictionaryProviders = dictionaryProviders;
        this.correctionProviders = correctionProviders;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getId() {
        return "ai.labs.parser";
    }

    @Override
    public String getType() {
        return KEY_EXPRESSIONS;
    }

    @Override
    public void execute(IConversationMemory memory, Object component) {
        final IInputParser parser = (IInputParser) component;

        //parse user input to meanings
        final IData<String> inputData = memory.getCurrentStep().getLatestData(KEY_INPUT);
        if (inputData == null) {
            return;
        }

        String userLanguage = retrieveContextLanguageFromLongTermMemory(memory.getConversationProperties());

        List<IDictionary> temporaryDictionaries = prepareTemporaryDictionaries(memory);
        List<RawSolution> parsedSolutions;
        try {
            String userInput = inputData.getResult();
            String normalizedUserInput = parser.normalize(userInput, userLanguage);
            storeNormalizedResultInMemory(memory.getCurrentStep(), normalizedUserInput);
            parsedSolutions = parser.parse(normalizedUserInput, userLanguage, temporaryDictionaries);
        } catch (InterruptedException e) {
            log.warn(e.getLocalizedMessage(), e);
            return;
        }

        storeResultInMemory(memory.getCurrentStep(), parsedSolutions, parser.getConfig());
    }


    private List<IDictionary> prepareTemporaryDictionaries(IConversationMemory memory) {
        List<ConversationOutput> conversationOutputs = memory.getConversationOutputs();
        if (conversationOutputs.isEmpty() || conversationOutputs.size() < 2) {
            return Collections.emptyList();
        }

        ConversationOutput conversationOutput = conversationOutputs.get(conversationOutputs.size() - 2);
        List<IDictionary> temporaryDictionaries = Collections.emptyList();
        List<Map<String, Object>> quickRepliesOutput = convertObjectToListOfMaps(conversationOutput);
        if (quickRepliesOutput != null) {
            List<QuickReply> quickReplies = extractQuickReplies(quickRepliesOutput);
            temporaryDictionaries = convertQuickReplies(quickReplies, expressionProvider);
        }

        return temporaryDictionaries;
    }

    private List<Map<String, Object>> convertObjectToListOfMaps(ConversationOutput conversationOutput) {
        return convertObjectToListOfMaps(conversationOutput.get("quickReplies"));
    }

    private List<QuickReply> extractQuickReplies(List<Map<String, Object>> quickReplyOutputList) {
        return quickReplyOutputList.stream().
                filter(Objects::nonNull).
                map((quickReplyData) -> new QuickReply(quickReplyData.get("value").toString(),
                        quickReplyData.get("expressions").toString(), (Boolean) quickReplyData.get("default"))).
                collect(Collectors.toList());
    }

    private void storeNormalizedResultInMemory(IConversationMemory.IWritableConversationStep currentStep, String normalizedInput) {
        if (!isNullOrEmpty(normalizedInput)) {
            IData<String> expressionsData = new Data<>(KEY_INPUT_NORMALIZED, normalizedInput);
            currentStep.storeData(expressionsData);
            currentStep.addConversationOutputString(KEY_INPUT, normalizedInput);
        }
    }

    private void storeResultInMemory(IConversationMemory.IWritableConversationStep currentStep,
                                     List<RawSolution> parsedSolutions,
                                     IInputParser.Config parserConfig) {
        if (!parsedSolutions.isEmpty()) {
            Solution solution =
                    extractExpressions(parsedSolutions,
                            parserConfig.isIncludeUnused(),
                            parserConfig.isIncludeUnknown()).get(0);

            Expressions newExpressions = solution.getExpressions();
            if (parserConfig.isAppendExpressions() && !newExpressions.isEmpty()) {
                IData<String> latestExpressions = currentStep.getLatestData(KEY_EXPRESSIONS_PARSED);
                if (latestExpressions != null) {
                    Expressions currentExpressions = expressionProvider.parseExpressions(latestExpressions.getResult());
                    currentExpressions.addAll(newExpressions);
                    newExpressions = currentExpressions.stream().distinct().collect(Collectors.toCollection(Expressions::new));
                }

                String expressionString = joinStrings(", ", newExpressions);
                IData<String> expressionsData = new Data<>(KEY_EXPRESSIONS_PARSED, expressionString);
                currentStep.storeData(expressionsData);
                currentStep.addConversationOutputString(KEY_EXPRESSIONS, expressionString);

                List<String> intents = newExpressions.stream().
                        map(Expression::getExpressionName).
                        distinct().collect(Collectors.toList());

                Data<List<String>> intentData = new Data<>(KEY_INTENT, intents);
                currentStep.storeData(intentData);
                currentStep.addConversationOutputList(KEY_INTENT, intents);
            }
        }
    }

    @Override
    public Object configure(Map<String, Object> configuration, Map<String, Object> extensions)
            throws PackageConfigurationException,
            IllegalExtensionConfigurationException,
            UnrecognizedExtensionException {

        var config = new IInputParser.Config();

        Object appendExpressions = configuration.get(CONFIG_APPEND_EXPRESSIONS);
        if (!isNullOrEmpty(appendExpressions)) {
            config.setAppendExpressions(Boolean.parseBoolean(appendExpressions.toString()));
        }

        Object includeUnused = configuration.get(CONFIG_INCLUDE_UNUSED);
        if (!isNullOrEmpty(includeUnused)) {
            config.setIncludeUnused(Boolean.parseBoolean(includeUnused.toString()));
        }

        Object includeUnknown = configuration.get(CONFIG_INCLUDE_UNKNOWN);
        if (!isNullOrEmpty(includeUnknown)) {
            config.setIncludeUnknown(Boolean.parseBoolean(includeUnknown.toString()));
        }

        List<INormalizer> normalizers = new LinkedList<>();
        List<IDictionary> dictionaries = new LinkedList<>();
        List<ICorrection> corrections = new LinkedList<>();

        if (!isNullOrEmpty(extensions)) {
            var normalizerList = convertObjectToListOfMaps(extensions.get(EXTENSION_NAME_NORMALIZER));
            if (normalizerList != null) {
                normalizers = convertNormalizers(normalizerList);
            }

            var dictionariesList = convertObjectToListOfMaps(extensions.get(EXTENSION_NAME_DICTIONARIES));
            if (dictionariesList != null) {
                dictionaries = convertDictionaries(dictionariesList);
            }

            var correctionsList = convertObjectToListOfMaps(extensions.get(EXTENSION_NAME_CORRECTIONS));
            if (correctionsList != null) {
                corrections = convertCorrections(correctionsList, dictionaries);
            }
        }

        return new InputParser(normalizers, dictionaries, corrections, config);
    }


    private List<Map<String, Object>> convertObjectToListOfMaps(Object extension) {
        return objectMapper.convertValue(extension, new TypeReference<>() {});
    }

    @Override
    public ExtensionDescriptor getExtensionDescriptor() {
        ExtensionDescriptor extensionDescriptor = new ExtensionDescriptor(ID);
        extensionDescriptor.setDisplayName("Input Parser");

        normalizerProviders.keySet().forEach(type -> {
            ExtensionDescriptor normalizerDescriptor = new ExtensionDescriptor(type);
            Provider<INormalizerProvider> normalizerProvider = normalizerProviders.get(type);
            INormalizerProvider provider = normalizerProvider.get();
            normalizerDescriptor.setDisplayName(provider.getDisplayName());
            normalizerDescriptor.setConfigs(provider.getConfigs());
            extensionDescriptor.addExtension(EXTENSION_NAME_NORMALIZER, normalizerDescriptor);
        });

        dictionaryProviders.keySet().forEach(type -> {
            ExtensionDescriptor dictionaryDescriptor = new ExtensionDescriptor(type);
            Provider<IDictionaryProvider> dictionaryProvider = dictionaryProviders.get(type);
            IDictionaryProvider provider = dictionaryProvider.get();
            dictionaryDescriptor.setDisplayName(provider.getDisplayName());
            dictionaryDescriptor.setConfigs(provider.getConfigs());
            extensionDescriptor.addExtension(EXTENSION_NAME_DICTIONARIES, dictionaryDescriptor);
        });

        correctionProviders.keySet().forEach(type -> {
            ExtensionDescriptor correctionsDescriptor = new ExtensionDescriptor(type);
            Provider<ICorrectionProvider> correctionProvider = correctionProviders.get(type);
            ICorrectionProvider provider = correctionProvider.get();
            correctionsDescriptor.setDisplayName(provider.getDisplayName());
            correctionsDescriptor.setConfigs(provider.getConfigs());
            extensionDescriptor.addExtension(EXTENSION_NAME_CORRECTIONS, correctionsDescriptor);
        });

        Map<String, ConfigValue> extensionConfigs = new HashMap<>();
        extensionConfigs.put(CONFIG_APPEND_EXPRESSIONS, new ConfigValue("Append Expressions", BOOLEAN, true, true));
        extensionConfigs.put(CONFIG_INCLUDE_UNUSED, new ConfigValue("Include Unused Expressions", BOOLEAN, true, true));
        extensionConfigs.put(CONFIG_INCLUDE_UNKNOWN, new ConfigValue("Include Unknown Expressions", BOOLEAN, true, true));
        extensionDescriptor.setConfigs(extensionConfigs);

        return extensionDescriptor;
    }

    private List<INormalizer> convertNormalizers(List<Map<String, Object>> normalizerList)
            throws UnrecognizedExtensionException, IllegalExtensionConfigurationException {
        List<INormalizer> normalizers = new LinkedList<>();
        for (Map<String, Object> normalizerMap : normalizerList) {
            String normalizerType = getResourceType(normalizerMap);
            Provider<INormalizerProvider> normalizerProvider = normalizerProviders.get(normalizerType);
            if (normalizerProvider != null) {
                INormalizerProvider normalizer = normalizerProvider.get();
                Object configObject = normalizerMap.get(KEY_CONFIG);
                var providedNormalizer =
                        configObject instanceof Map ?
                                normalizer.provide(convertObjectToMap(configObject)) :
                                normalizer.provide(Collections.emptyMap());

                normalizers.add(providedNormalizer);
            } else {
                String message = "Normalizer type could not be recognized by Parser [type=%s]";
                message = String.format(message, normalizerType);
                throw new UnrecognizedExtensionException(message);
            }
        }

        return normalizers;
    }

    private List<IDictionary> convertDictionaries(List<Map<String, Object>> dictionariesList)
            throws UnrecognizedExtensionException, IllegalExtensionConfigurationException {
        List<IDictionary> dictionaries = new LinkedList<>();
        for (Map<String, Object> dictionaryMap : dictionariesList) {
            String dictionaryType = getResourceType(dictionaryMap);
            Provider<IDictionaryProvider> dictionaryProvider = dictionaryProviders.get(dictionaryType);
            if (dictionaryProvider != null) {
                IDictionaryProvider dictionary = dictionaryProvider.get();
                Object configObject = dictionaryMap.get(KEY_CONFIG);
                var providedDictionary =
                        configObject instanceof Map ?
                                dictionary.provide(convertObjectToMap(configObject)) :
                                dictionary.provide(Collections.emptyMap());

                dictionaries.add(providedDictionary);
            } else {
                String message = "Dictionary type could not be recognized by Parser [type=%s]";
                message = String.format(message, dictionaryType);
                throw new UnrecognizedExtensionException(message);
            }
        }

        return dictionaries;
    }

    private List<ICorrection> convertCorrections(List<Map<String, Object>> correctionList, List<IDictionary> dictionaries)
            throws UnrecognizedExtensionException, IllegalExtensionConfigurationException {

        List<ICorrection> corrections = new LinkedList<>();
        for (Map<String, Object> correctionMap : correctionList) {
            String correctionType = getResourceType(correctionMap);
            Provider<ICorrectionProvider> correctionProviderCreator = correctionProviders.get(correctionType);
            if (correctionProviderCreator != null) {
                ICorrectionProvider correctionProvider = correctionProviderCreator.get();
                Object configObject = correctionMap.get(KEY_CONFIG);
                var correction =
                        configObject instanceof Map ?
                                correctionProvider.provide(convertObjectToMap(configObject)) :
                                correctionProvider.provide(Collections.emptyMap());

                correction.init(dictionaries);
                corrections.add(correction);
            } else {
                String message = "Correction type could not be recognized by Parser [type=%s]";
                message = String.format(message, correctionType);
                throw new UnrecognizedExtensionException(message);
            }
        }

        return corrections;
    }

    private Map<String, Object> convertObjectToMap(Object configObject) {
        return objectMapper.convertValue(configObject, new TypeReference<>() {});
    }

    private static String getResourceType(Map<String, Object> resourceMap) {
        URI normalizerUri = URI.create(resourceMap.get(KEY_TYPE).toString());
        return normalizerUri.getHost();
    }
}
