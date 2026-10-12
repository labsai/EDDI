/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp;

import ai.labs.eddi.engine.lifecycle.ResourceUris;
import ai.labs.eddi.configs.parser.model.ParserConfiguration;
import ai.labs.eddi.configs.workflows.model.ExtensionDescriptor;
import ai.labs.eddi.configs.workflows.model.ExtensionDescriptor.ConfigValue;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.TaskId;
import ai.labs.eddi.engine.lifecycle.exceptions.IllegalExtensionConfigurationException;
import ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException;
import ai.labs.eddi.engine.lifecycle.exceptions.UnrecognizedExtensionException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
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
import ai.labs.eddi.modules.nlp.internal.Limits;
import ai.labs.eddi.modules.nlp.internal.matches.RawSolution;
import ai.labs.eddi.modules.output.model.QuickReply;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static ai.labs.eddi.configs.workflows.model.ExtensionDescriptor.FieldType.BOOLEAN;
import static ai.labs.eddi.configs.workflows.model.ExtensionDescriptor.FieldType.INT;
import static ai.labs.eddi.engine.memory.ContextUtilities.retrieveContextLanguageFromLongTermMemory;
import static ai.labs.eddi.engine.memory.MemoryKeys.EXPRESSIONS_MATCHES;
import static ai.labs.eddi.engine.memory.MemoryKeys.EXPRESSIONS_PARSED;
import static ai.labs.eddi.engine.memory.MemoryKeys.INPUT;
import static ai.labs.eddi.engine.memory.MemoryKeys.INPUT_NORMALIZED;
import static ai.labs.eddi.engine.memory.MemoryKeys.INTENTS;
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
    public static final TaskId TASK_ID = new TaskId(ID);

    private static final String CONFIG_APPEND_EXPRESSIONS = "appendExpressions";
    private static final String CONFIG_INCLUDE_UNUSED = "includeUnused";
    private static final String CONFIG_INCLUDE_UNKNOWN = "includeUnknown";
    private static final String CONFIG_MAX_INPUT_TOKENS = "maxInputTokens";
    private static final String CONFIG_MAX_SUGGESTIONS = "maxSuggestions";
    private static final String CONFIG_MAX_SOLUTIONS = "maxSolutions";
    private static final String EXTENSION_NAME_NORMALIZER = "normalizer";
    private static final String EXTENSION_NAME_DICTIONARIES = "dictionaries";
    private static final String EXTENSION_NAME_CORRECTIONS = "corrections";
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-zA-Z0-9 ]+");
    private final ObjectMapper objectMapper;

    private static final String KEY_EXPRESSIONS = "expressions";
    private static final String KEY_TYPE = "type";
    private static final String KEY_CONFIG = "config";
    private static final String KEY_URI = "uri";
    private static final String KEY_QUICK_REPLIES = "quickReplies";
    private static final String KEY_VALUE = "value";
    private static final String KEY_IS_DEFAULT = "isDefault";
    private static final String TEMPLATED_TWIN_PRE = ":preTemplated";
    private static final String TEMPLATED_TWIN_POST = ":postTemplated";

    private final IExpressionProvider expressionProvider;
    private final Map<String, Provider<INormalizerProvider>> normalizerProviders;
    private final Map<String, Provider<IDictionaryProvider>> dictionaryProviders;
    private final Map<String, Provider<ICorrectionProvider>> correctionProviders;
    private final IResourceClientLibrary resourceClientLibrary;

    private static final Logger log = Logger.getLogger(InputParserTask.class);

    @Inject
    public InputParserTask(IExpressionProvider expressionProvider,
            @ParserNormalizerExtensions Map<String, Provider<INormalizerProvider>> normalizerProviders,
            @ParserDictionaryExtensions Map<String, Provider<IDictionaryProvider>> dictionaryProviders,
            @ParserCorrectionExtensions Map<String, Provider<ICorrectionProvider>> correctionProviders, ObjectMapper objectMapper,
            IResourceClientLibrary resourceClientLibrary) {
        this.expressionProvider = expressionProvider;
        this.resourceClientLibrary = resourceClientLibrary;
        this.normalizerProviders = normalizerProviders;
        this.dictionaryProviders = dictionaryProviders;
        this.correctionProviders = correctionProviders;
        this.objectMapper = objectMapper;
    }

    @Override
    public TaskId getId() {
        return TASK_ID;
    }

    @Override
    public String getType() {
        return KEY_EXPRESSIONS;
    }

    @Override
    public void execute(IConversationMemory memory, Object component) {
        final IInputParser parser = (IInputParser) component;

        // parse user input to meanings
        final IData<String> inputData = memory.getCurrentStep().getLatestData(INPUT);
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
            // B2: the pipeline's graceful-stop signal IS the thread's interrupt flag —
            // LifecycleManager re-checks Thread.currentThread().isInterrupted() before
            // every task. Catching the exception consumes the signal, so restore the
            // flag before returning normally; otherwise the remaining tasks of an
            // interrupted turn keep running.
            Thread.currentThread().interrupt();
            log.warn(e.getLocalizedMessage(), e);
            return;
        }

        storeResultInMemory(memory.getCurrentStep(), parsedSolutions, parser.getConfig());
    }

    /**
     * The quick replies the agent offered on the previous step become a temporary
     * dictionary for this one, so clicking one yields its expressions.
     * <p>
     * They are read from the previous step's {@code quickReplies:*} <em>data</em>,
     * not from its conversation output, because only the data tells who wrote them:
     * the output generation task stores a data entry for quick replies of the
     * agent's output set and for those a {@code postResponse} built, but quick
     * replies a client injected through {@code context} reach the output only — for
     * display. They used to be read from the output like the others, so a client
     * could offer itself a quick reply with any expression (or a value whose
     * generated expression was whatever it liked) and have it parsed into that
     * expression on the next turn — an action on every agent with
     * {@code expressionsAsActions}.
     */
    private List<IDictionary> prepareTemporaryDictionaries(IConversationMemory memory) {
        var previousSteps = memory.getPreviousSteps();
        if (previousSteps == null || previousSteps.size() == 0) {
            return Collections.emptyList();
        }

        List<QuickReply> quickReplies = new LinkedList<>();
        List<IData<Object>> quickReplyData = previousSteps.get(0).getAllData(KEY_QUICK_REPLIES);
        if (quickReplyData != null) {
            for (IData<Object> data : quickReplyData) {
                String key = data.getKey();
                if (key == null || key.endsWith(TEMPLATED_TWIN_PRE) || key.endsWith(TEMPLATED_TWIN_POST)) {
                    // The templating task's before/after record of the same quick replies.
                    continue;
                }
                quickReplies.addAll(extractQuickReplies(data.getResult()));
            }
        }

        return quickReplies.isEmpty() ? Collections.emptyList() : convertQuickReplies(quickReplies, expressionProvider);
    }

    /**
     * Reads the quick replies of one data entry: {@link QuickReply} objects on the
     * live memory, maps once the step has been stored and loaded again. A quick
     * reply without a value is skipped — it used to throw a
     * {@code NullPointerException} that failed the parser.
     */
    private List<QuickReply> extractQuickReplies(Object result) {
        if (!(result instanceof List<?> list)) {
            return List.of();
        }
        List<QuickReply> quickReplies = new LinkedList<>();
        for (Object item : list) {
            String value;
            Object expressions;
            Object isDefault;
            if (item instanceof QuickReply quickReply) {
                value = quickReply.getValue();
                expressions = quickReply.getExpressions();
                isDefault = quickReply.getIsDefault();
            } else if (item instanceof Map<?, ?> map) {
                value = map.get(KEY_VALUE) != null ? map.get(KEY_VALUE).toString() : null;
                expressions = map.get(KEY_EXPRESSIONS);
                // QuickReply serializes its flag as "isDefault"; this used to read
                // "default", which is never written, so the flag was always null.
                isDefault = map.get(KEY_IS_DEFAULT);
            } else {
                continue;
            }
            if (value == null || value.isBlank()) {
                continue;
            }
            String expressionString = expressions != null && !expressions.toString().isBlank()
                    ? expressions.toString()
                    : generateExpression(value);
            quickReplies.add(new QuickReply(value, expressionString,
                    isDefault instanceof Boolean flag ? flag : isDefault != null ? Boolean.valueOf(isDefault.toString()) : null));
        }
        return quickReplies;
    }

    private static String generateExpression(String value) {
        var sanitized = NON_ALPHANUMERIC.matcher(value).replaceAll("");
        return sanitized.trim().replaceAll("\\s+", "_").toLowerCase();
    }

    private static void storeNormalizedResultInMemory(IWritableConversationStep currentStep, String normalizedInput) {
        if (!isNullOrEmpty(normalizedInput)) {
            IData<String> expressionsData = new Data<>(INPUT_NORMALIZED.key(), normalizedInput);
            currentStep.storeData(expressionsData);
            currentStep.addConversationOutputString(INPUT.key(), normalizedInput);
        }
    }

    private void storeResultInMemory(IWritableConversationStep currentStep, List<RawSolution> parsedSolutions, IInputParser.Config parserConfig) {
        if (parsedSolutions.isEmpty()) {
            return;
        }

        Solution solution = extractExpressions(parsedSolutions, parserConfig.isIncludeUnused(), parserConfig.isIncludeUnknown()).getFirst();

        Expressions newExpressions = solution.getExpressions();
        if (newExpressions.isEmpty()) {
            return;
        }

        // 'appendExpressions' only decides whether the freshly parsed expressions are
        // merged with the ones already present on this step — it must never suppress
        // storing them, otherwise downstream behaviour rules would see no expressions
        // and no intents at all.
        if (parserConfig.isAppendExpressions()) {
            IData<String> latestExpressions = currentStep.getLatestData(EXPRESSIONS_PARSED);
            if (latestExpressions != null) {
                Expressions currentExpressions = expressionProvider.parseExpressions(latestExpressions.getResult());
                currentExpressions.addAll(newExpressions);
                newExpressions = currentExpressions.stream().distinct().collect(Collectors.toCollection(Expressions::new));
            }
        }

        String expressionString = joinStrings(", ", newExpressions);
        IData<String> expressionsData = new Data<>(EXPRESSIONS_PARSED.key(), expressionString);
        currentStep.storeData(expressionsData);
        currentStep.addConversationOutputString(KEY_EXPRESSIONS, expressionString);

        List<String> intents = newExpressions.stream().map(Expression::getExpressionName).distinct().toList();

        Data<List<String>> intentData = new Data<>(INTENTS.key(), intents);
        currentStep.storeData(intentData);
        currentStep.addConversationOutputList(INTENTS.key(), intents);

        // Store input→expression matches for debugging/audit
        List<String> matchDetails = solution.getMatchDetails();
        if (!matchDetails.isEmpty()) {
            currentStep.storeData(new Data<>(EXPRESSIONS_MATCHES.key(), matchDetails));
        }
    }

    @Override
    public Object configure(Map<String, Object> configuration, Map<String, Object> extensions)
            throws WorkflowConfigurationException, IllegalExtensionConfigurationException, UnrecognizedExtensionException {

        ParserConfiguration document = loadParserDocument(configuration);
        if (document != null) {
            configuration = mergeConfig(document.getConfig(), configuration);
            extensions = mergeExtensions(document.getExtensions(), extensions);
        }

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

        var limits = new Limits(parsePositiveInt(configuration.get(CONFIG_MAX_INPUT_TOKENS), Limits.DEFAULT.maxInputTokens()),
                parsePositiveInt(configuration.get(CONFIG_MAX_SUGGESTIONS), Limits.DEFAULT.maxSuggestions()),
                parsePositiveInt(configuration.get(CONFIG_MAX_SOLUTIONS), Limits.DEFAULT.maxSolutions()));

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

        return new InputParser(normalizers, dictionaries, corrections, config, limits);
    }

    /**
     * The {@code parserstore} document a step references through {@code config.uri}
     * — the shape {@code AgentSetupService}, the MCP setup tools and the Manager's
     * pipeline builder write. {@code null} when the step has no {@code uri}, i.e.
     * carries its parser inline.
     * <p>
     * This used to be ignored: such a step ran with no dictionaries, normalizers or
     * corrections at all, and nothing said so.
     */
    private ParserConfiguration loadParserDocument(Map<String, Object> configuration) throws WorkflowConfigurationException {
        Object uriObj = configuration == null ? null : configuration.get(KEY_URI);
        if (isNullOrEmpty(uriObj)) {
            return null;
        }
        URI uri = ResourceUris.require(uriObj, ID);
        try {
            var document = resourceClientLibrary.getResource(uri, ParserConfiguration.class);
            if (document == null) {
                throw new WorkflowConfigurationException("Parser configuration " + uriObj + " could not be found.");
            }
            return document;
        } catch (ServiceException e) {
            throw new WorkflowConfigurationException("Error while fetching ParserConfiguration " + uriObj + "!\n" + e.getLocalizedMessage(), e);
        }
    }

    /**
     * The document's {@code config}, overlaid with the step's own {@code config}: a
     * key the step sets wins, so a workflow can tune one parser document per step.
     */
    private static Map<String, Object> mergeConfig(Map<String, Object> documentConfig, Map<String, Object> stepConfig) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (documentConfig != null) {
            merged.putAll(documentConfig);
        }
        if (stepConfig != null) {
            stepConfig.forEach((key, value) -> {
                if (!KEY_URI.equals(key)) {
                    merged.put(key, value);
                }
            });
        }
        return merged;
    }

    /**
     * The document's extensions followed by the step's own: per extension point
     * (normalizer, dictionaries, corrections) the step's entries are appended after
     * the document's, so the document's normalizers run first and the step can add
     * a dictionary without restating the document's.
     */
    private Map<String, Object> mergeExtensions(Map<String, Object> documentExtensions, Map<String, Object> stepExtensions) {
        Map<String, Object> merged = new LinkedHashMap<>();
        for (var source : Arrays.asList(documentExtensions, stepExtensions)) {
            if (source == null) {
                continue;
            }
            source.forEach((name, value) -> {
                List<Map<String, Object>> entries = convertObjectToListOfMaps(value);
                if (entries == null) {
                    return;
                }
                @SuppressWarnings("unchecked")
                List<Object> existing = (List<Object>) merged.computeIfAbsent(name, ignored -> new ArrayList<>());
                existing.addAll(entries);
            });
        }
        return merged;
    }

    /**
     * Reads an optional positive integer from the workflow configuration. Missing,
     * blank or unparseable values fall back to the given default so that a
     * malformed config never disables the parser's safety limits.
     */
    private static int parsePositiveInt(Object value, int defaultValue) {
        if (isNullOrEmpty(value)) {
            return defaultValue;
        }

        try {
            int parsed = Integer.parseInt(value.toString().trim());
            if (parsed < 1) {
                log.warnf("Parser limit must be greater than 0, but was %s. Falling back to %s.", parsed, defaultValue);
                return defaultValue;
            }

            return parsed;
        } catch (NumberFormatException e) {
            log.warnf("Parser limit '%s' is not a number. Falling back to %s.", value, defaultValue);
            return defaultValue;
        }
    }

    private List<Map<String, Object>> convertObjectToListOfMaps(Object extension) {
        return objectMapper.convertValue(extension, new TypeReference<>() {
        });
    }

    @Override
    public ExtensionDescriptor getExtensionDescriptor() {
        ExtensionDescriptor extensionDescriptor = new ExtensionDescriptor(new TaskId(ID));
        extensionDescriptor.setDisplayName("Input Parser");

        normalizerProviders.keySet().forEach(type -> {
            ExtensionDescriptor normalizerDescriptor = new ExtensionDescriptor(new TaskId(type));
            Provider<INormalizerProvider> normalizerProvider = normalizerProviders.get(type);
            INormalizerProvider provider = normalizerProvider.get();
            normalizerDescriptor.setDisplayName(provider.getDisplayName());
            normalizerDescriptor.setConfigs(provider.getConfigs());
            extensionDescriptor.addExtension(EXTENSION_NAME_NORMALIZER, normalizerDescriptor);
        });

        dictionaryProviders.keySet().forEach(type -> {
            ExtensionDescriptor dictionaryDescriptor = new ExtensionDescriptor(new TaskId(type));
            Provider<IDictionaryProvider> dictionaryProvider = dictionaryProviders.get(type);
            IDictionaryProvider provider = dictionaryProvider.get();
            dictionaryDescriptor.setDisplayName(provider.getDisplayName());
            dictionaryDescriptor.setConfigs(provider.getConfigs());
            extensionDescriptor.addExtension(EXTENSION_NAME_DICTIONARIES, dictionaryDescriptor);
        });

        correctionProviders.keySet().forEach(type -> {
            ExtensionDescriptor correctionsDescriptor = new ExtensionDescriptor(new TaskId(type));
            Provider<ICorrectionProvider> correctionProvider = correctionProviders.get(type);
            ICorrectionProvider provider = correctionProvider.get();
            correctionsDescriptor.setDisplayName(provider.getDisplayName());
            correctionsDescriptor.setConfigs(provider.getConfigs());
            extensionDescriptor.addExtension(EXTENSION_NAME_CORRECTIONS, correctionsDescriptor);
        });

        Map<String, ConfigValue> extensionConfigs = new HashMap<>();
        extensionConfigs.put(KEY_URI, new ConfigValue("Resource URI", ExtensionDescriptor.FieldType.URI, true, null));
        extensionConfigs.put(CONFIG_APPEND_EXPRESSIONS, new ConfigValue("Append Expressions", BOOLEAN, true, true));
        extensionConfigs.put(CONFIG_INCLUDE_UNUSED, new ConfigValue("Include Unused Expressions", BOOLEAN, true, true));
        extensionConfigs.put(CONFIG_INCLUDE_UNKNOWN, new ConfigValue("Include Unknown Expressions", BOOLEAN, true, true));
        extensionConfigs.put(CONFIG_MAX_INPUT_TOKENS, new ConfigValue("Max Input Tokens", INT, true, Limits.DEFAULT.maxInputTokens()));
        extensionConfigs.put(CONFIG_MAX_SUGGESTIONS, new ConfigValue("Max Suggestions Evaluated", INT, true, Limits.DEFAULT.maxSuggestions()));
        extensionConfigs.put(CONFIG_MAX_SOLUTIONS, new ConfigValue("Max Solutions Collected", INT, true, Limits.DEFAULT.maxSolutions()));
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
                var providedNormalizer = configObject instanceof Map
                        ? normalizer.provide(convertObjectToMap(configObject))
                        : normalizer.provide(Collections.emptyMap());

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
                var providedDictionary = configObject instanceof Map
                        ? dictionary.provide(convertObjectToMap(configObject))
                        : dictionary.provide(Collections.emptyMap());

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
                var correction = configObject instanceof Map
                        ? correctionProvider.provide(convertObjectToMap(configObject))
                        : correctionProvider.provide(Collections.emptyMap());

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
        return objectMapper.convertValue(configObject, new TypeReference<>() {
        });
    }

    private static String getResourceType(Map<String, Object> resourceMap) {
        URI normalizerUri = URI.create(resourceMap.get(KEY_TYPE).toString());
        return normalizerUri.getHost();
    }
}
