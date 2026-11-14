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
import ai.labs.eddi.modules.nlp.semantic.SemanticFallback;
import ai.labs.eddi.modules.llm.impl.EmbeddingModelFactory;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import dev.langchain4j.model.embedding.request.EmbeddingInputType;
import io.micrometer.core.instrument.MeterRegistry;
import ai.labs.eddi.modules.output.model.QuickReply;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
import static ai.labs.eddi.configs.workflows.model.ExtensionDescriptor.FieldType.DOUBLE;
import static ai.labs.eddi.configs.workflows.model.ExtensionDescriptor.FieldType.INT;
import static ai.labs.eddi.engine.memory.ContextUtilities.retrieveContextLanguageFromLongTermMemory;
import static ai.labs.eddi.engine.memory.MemoryKeys.EXPRESSIONS_ALTERNATIVES;
import static ai.labs.eddi.engine.memory.MemoryKeys.EXPRESSIONS_CONFIDENCE;
import static ai.labs.eddi.engine.memory.MemoryKeys.EXPRESSIONS_INPUT_TRUNCATED;
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
    private static final String CONFIG_MATCH_PARTIAL_PHRASES = "matchPartialPhrases";
    private static final String CONFIG_MIN_PARTIAL_PHRASE_COVERAGE = "minPartialPhraseCoverage";
    private static final String CONFIG_MIN_MATCHING_ACCURACY = "minMatchingAccuracy";
    private static final String CONFIG_MAX_ALTERNATIVES = "maxAlternatives";
    private static final String CONFIG_SEMANTIC_FALLBACK = "semanticFallback";
    /**
     * Upper bound on {@code maxAlternatives}: each one is a string in conversation
     * memory.
     */
    static final int MAX_ALTERNATIVES_LIMIT = 10;
    static final double DEFAULT_SEMANTIC_MIN_SCORE = 0.8;
    static final String METRIC_SEMANTIC_FALLBACK = "eddi.parser.semantic.fallback";
    private static final String EXTENSION_NAME_NORMALIZER = "normalizer";
    private static final String EXTENSION_NAME_DICTIONARIES = "dictionaries";
    private static final String EXTENSION_NAME_CORRECTIONS = "corrections";
    /**
     * What a generated quick-reply expression keeps: letters and digits of any
     * script. This was {@code [^a-zA-Z0-9 ]}, which turned "Größe" into "gre" and a
     * Cyrillic, Greek, Arabic or CJK quick reply into an empty expression — one
     * that a click on it could never produce.
     */
    private static final Pattern NOT_LETTER_OR_DIGIT = Pattern.compile("[^\\p{L}\\p{N}\\s]+");
    /**
     * Characters of the expression notation itself, removed from a fallback name.
     */
    private static final Pattern EXPRESSION_SYNTAX = Pattern.compile("[(),\\s]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
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
    private final EmbeddingModelFactory embeddingModelFactory;
    private final MeterRegistry meterRegistry;

    private static final Logger log = Logger.getLogger(InputParserTask.class);

    /**
     * Without an embedding model factory or metrics — a parser configured with a
     * {@code semanticFallback} is then refused at deployment.
     */
    public InputParserTask(IExpressionProvider expressionProvider, Map<String, Provider<INormalizerProvider>> normalizerProviders,
            Map<String, Provider<IDictionaryProvider>> dictionaryProviders, Map<String, Provider<ICorrectionProvider>> correctionProviders,
            ObjectMapper objectMapper, IResourceClientLibrary resourceClientLibrary) {
        this(expressionProvider, normalizerProviders, dictionaryProviders, correctionProviders, objectMapper, resourceClientLibrary, null, null);
    }

    @Inject
    public InputParserTask(IExpressionProvider expressionProvider,
            @ParserNormalizerExtensions Map<String, Provider<INormalizerProvider>> normalizerProviders,
            @ParserDictionaryExtensions Map<String, Provider<IDictionaryProvider>> dictionaryProviders,
            @ParserCorrectionExtensions Map<String, Provider<ICorrectionProvider>> correctionProviders, ObjectMapper objectMapper,
            IResourceClientLibrary resourceClientLibrary, EmbeddingModelFactory embeddingModelFactory, MeterRegistry meterRegistry) {
        this.embeddingModelFactory = embeddingModelFactory;
        this.meterRegistry = meterRegistry;
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

        // Resolved once, so the dictionaries and the semantic fallback read the turn in
        // the same language; the fallback used to get null when the conversation had
        // none and then accepted examples of every language, while the dictionaries
        // parsed in the default one.
        String userLanguage = InputParser.effectiveLanguage(retrieveContextLanguageFromLongTermMemory(memory.getConversationProperties()));

        List<RawSolution> parsedSolutions;
        String normalizedUserInput;
        try {
            List<IDictionary> temporaryDictionaries = prepareTemporaryDictionaries(memory, parser, userLanguage);
            String userInput = inputData.getResult();
            normalizedUserInput = parser.normalize(userInput, userLanguage);
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

        IWritableConversationStep currentStep = memory.getCurrentStep();
        Limits limits = parser.getLimits();
        if (limits != null && InputParser.exceedsTokenLimit(normalizedUserInput, limits)) {
            currentStep.storeData(new Data<>(EXPRESSIONS_INPUT_TRUNCATED.key(), Boolean.TRUE));
        }

        IInputParser.Config config = parser.getConfig();
        List<Solution> solutions = parsedSolutions.isEmpty()
                ? List.of()
                : extractExpressions(parsedSolutions, config.isIncludeUnused(), config.isIncludeUnknown(), config.getMinMatchingAccuracy());
        Solution solution = solutions.isEmpty() ? null : solutions.getFirst();

        if ((solution == null || !solution.hasMeaningfulExpressions()) && parser.getSemanticFallback() != null) {
            // The same tokens the dictionaries read: words past maxInputTokens must not
            // select an example either.
            String fallbackInput = limits == null ? normalizedUserInput : InputParser.withinTokenLimit(normalizedUserInput, limits);
            Solution semantic = consultSemanticFallback(parser.getSemanticFallback(), fallbackInput, userLanguage);
            if (semantic != null) {
                solution = semantic;
                solutions = List.of(semantic);
            }
        }

        if (solution != null) {
            storeResultInMemory(currentStep, solution, solutions, config);
        }
    }

    /**
     * @return the semantic match as a solution, or {@code null} when no example is
     *         similar enough (or the provider failed — the fallback never fails the
     *         turn)
     */
    private Solution consultSemanticFallback(SemanticFallback fallback, String normalizedInput, String userLanguage) {
        var match = fallback.classify(normalizedInput, userLanguage);
        countSemanticFallback(match.isPresent() ? "match" : "no_match");
        if (match.isEmpty()) {
            return null;
        }
        Expressions expressions;
        try {
            expressions = expressionProvider.parseExpressions(match.get().example().expressions());
        } catch (RuntimeException e) {
            log.warnf("Semantic parser fallback example '%s' has unparseable expressions: %s", match.get().example().text(), e.getMessage());
            return null;
        }
        if (expressions.isEmpty()) {
            return null;
        }
        double score = match.get().score();
        String detail = String.format(Locale.ROOT, "\"%s\" [semantic %.2f, like \"%s\"] → %s", normalizedInput, score, match.get().example().text(),
                expressions);
        return new Solution(expressions, List.of(detail), score, false);
    }

    private void countSemanticFallback(String outcome) {
        if (meterRegistry != null) {
            meterRegistry.counter(METRIC_SEMANTIC_FALLBACK, "outcome", outcome).increment();
        }
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
    private List<IDictionary> prepareTemporaryDictionaries(IConversationMemory memory, IInputParser parser, String userLanguage)
            throws InterruptedException {
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

        if (quickReplies.isEmpty()) {
            return Collections.emptyList();
        }

        // A click sends the quick reply's value as input, and the input is
        // normalized before it is parsed — so the value has to be normalized the
        // same way, or it is not what the parser compares it with. With the
        // punctuation normalizer, "Yes, please!" arrived as "Yes , please !" and no
        // longer matched the quick reply it came from.
        List<QuickReply> normalized = new ArrayList<>(quickReplies.size());
        for (QuickReply quickReply : quickReplies) {
            String value = parser.normalize(quickReply.getValue(), userLanguage);
            if (value == null) {
                value = quickReply.getValue();
            }
            if (!isNullOrEmpty(value)) {
                normalized.add(new QuickReply(value, quickReply.getExpressions(), quickReply.getIsDefault()));
            }
        }
        return normalized.isEmpty() ? Collections.emptyList() : convertQuickReplies(normalized, expressionProvider);
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

    static String generateExpression(String value) {
        String sanitized = NOT_LETTER_OR_DIGIT.matcher(value).replaceAll("").trim();
        if (sanitized.isEmpty()) {
            // nothing but symbols ("👍", "?!"): keep them, minus the characters the
            // expression notation itself uses, rather than generating nothing
            sanitized = EXPRESSION_SYNTAX.matcher(value.trim()).replaceAll(" ").trim();
        }
        return WHITESPACE.matcher(sanitized).replaceAll("_").toLowerCase(Locale.ROOT);
    }

    private static void storeNormalizedResultInMemory(IWritableConversationStep currentStep, String normalizedInput) {
        if (!isNullOrEmpty(normalizedInput)) {
            IData<String> expressionsData = new Data<>(INPUT_NORMALIZED.key(), normalizedInput);
            currentStep.storeData(expressionsData);
            currentStep.addConversationOutputString(INPUT.key(), normalizedInput);
        }
    }

    private void storeResultInMemory(IWritableConversationStep currentStep, Solution solution, List<Solution> solutions,
                                     IInputParser.Config parserConfig) {
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
        } else {
            // This result replaces an earlier parser's on this step: its diagnostics
            // must not survive next to it (they are only rewritten when this one has
            // its own).
            currentStep.removeData(EXPRESSIONS_MATCHES.key());
            currentStep.removeData(EXPRESSIONS_CONFIDENCE.key());
            currentStep.removeData(EXPRESSIONS_ALTERNATIVES.key());
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

        if (solution.getConfidence() != null) {
            currentStep.storeData(new Data<>(EXPRESSIONS_CONFIDENCE.key(), solution.getConfidence()));
        }

        if (parserConfig.getMaxAlternatives() > 0) {
            String chosen = joinStrings(", ", solution.getExpressions());
            List<String> alternatives = solutions.stream().skip(1).filter(Solution::hasMeaningfulExpressions)
                    .map(alternative -> joinStrings(", ", alternative.getExpressions())).filter(alternative -> !alternative.equals(chosen)).distinct()
                    .limit(parserConfig.getMaxAlternatives()).toList();
            if (!alternatives.isEmpty()) {
                currentStep.storeData(new Data<>(EXPRESSIONS_ALTERNATIVES.key(), alternatives));
            }
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

        Object matchPartialPhrases = configuration.get(CONFIG_MATCH_PARTIAL_PHRASES);
        if (!isNullOrEmpty(matchPartialPhrases)) {
            config.setMatchPartialPhrases(Boolean.parseBoolean(matchPartialPhrases.toString()));
        }
        config.setMinPartialPhraseCoverage(
                parseFraction(configuration.get(CONFIG_MIN_PARTIAL_PHRASE_COVERAGE), IInputParser.Config.DEFAULT_MIN_PARTIAL_PHRASE_COVERAGE, false));
        config.setMinMatchingAccuracy(parseFraction(configuration.get(CONFIG_MIN_MATCHING_ACCURACY), 0.0, true));
        config.setMaxAlternatives(parseAlternatives(configuration.get(CONFIG_MAX_ALTERNATIVES)));

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

        SemanticFallback semanticFallback = buildSemanticFallback(configuration.get(CONFIG_SEMANTIC_FALLBACK), dictionaries);

        return new InputParser(normalizers, dictionaries, corrections, config, limits, semanticFallback);
    }

    /**
     * The {@code semanticFallback} block of the parser configuration, or
     * {@code null} when there is none. Everything that can be checked without
     * calling the provider is checked here, so a broken block fails the deployment
     * instead of a conversation.
     */
    private SemanticFallback buildSemanticFallback(Object fallbackConfig, List<IDictionary> dictionaries)
            throws IllegalExtensionConfigurationException {
        if (isNullOrEmpty(fallbackConfig)) {
            return null;
        }
        SemanticFallbackConfiguration fallback;
        try {
            fallback = objectMapper.convertValue(fallbackConfig, SemanticFallbackConfiguration.class);
        } catch (IllegalArgumentException e) {
            throw new IllegalExtensionConfigurationException("Parser 'semanticFallback' is malformed: " + e.getMessage(), e);
        }
        if (fallback == null || isNullOrEmpty(fallback.embeddingProvider())) {
            throw new IllegalExtensionConfigurationException("Parser 'semanticFallback' needs an 'embeddingProvider'.");
        }
        // The model is built lazily, so a misspelt provider would otherwise deploy and
        // only ever answer "no match".
        if (!RagConfiguration.SUPPORTED_EMBEDDING_PROVIDERS.contains(fallback.embeddingProvider().trim())) {
            throw new IllegalExtensionConfigurationException("Parser 'semanticFallback.embeddingProvider' '" + fallback.embeddingProvider()
                    + "' is not supported (supported: " + RagConfiguration.SUPPORTED_EMBEDDING_PROVIDERS.stream().sorted().toList() + ").");
        }
        if (embeddingModelFactory == null) {
            throw new IllegalExtensionConfigurationException("Parser 'semanticFallback' is not available in this runtime (no embedding models).");
        }
        double minScore = fallback.minScore() == null ? DEFAULT_SEMANTIC_MIN_SCORE : fallback.minScore();
        if (!(minScore > 0.0 && minScore <= 1.0)) {
            throw new IllegalExtensionConfigurationException("Parser 'semanticFallback.minScore' must be above 0 and at most 1, but was " + minScore);
        }

        List<SemanticFallback.Example> examples = new ArrayList<>();
        if (fallback.examples() != null) {
            for (var example : fallback.examples()) {
                if (example == null || isNullOrEmpty(example.text()) || isNullOrEmpty(example.expressions())) {
                    throw new IllegalExtensionConfigurationException("Every 'semanticFallback.examples' entry needs a 'text' and 'expressions'.");
                }
                Expressions parsed;
                try {
                    parsed = expressionProvider.parseExpressions(example.expressions());
                } catch (RuntimeException e) {
                    throw new IllegalExtensionConfigurationException(
                            "Parser 'semanticFallback' example '" + example.text() + "' has unparseable expressions: " + e.getMessage(), e);
                }
                if (parsed == null || parsed.isEmpty()) {
                    throw new IllegalExtensionConfigurationException(
                            "Parser 'semanticFallback' example '" + example.text() + "' has no expressions.");
                }
                examples.add(new SemanticFallback.Example(example.text().trim(), example.expressions(), example.lang()));
            }
        }
        boolean includePhrases = fallback.includeDictionaryPhrases() == null || fallback.includeDictionaryPhrases();
        boolean includeWords = Boolean.TRUE.equals(fallback.includeDictionaryWords());
        for (IDictionary dictionary : dictionaries) {
            if (includePhrases) {
                dictionary.getPhrases().stream().filter(phrase -> hasMeaningfulExpression(phrase.getExpressions()))
                        .forEach(phrase -> examples.add(
                                new SemanticFallback.Example(phrase.getValue(), phrase.getExpressions().toString(), dictionary.getLanguageCode())));
            }
            if (includeWords) {
                dictionary.getWords().stream().filter(word -> !word.isPartOfPhrase() && hasMeaningfulExpression(word.getExpressions()))
                        .forEach(word -> examples.add(
                                new SemanticFallback.Example(word.getValue(), word.getExpressions().toString(), dictionary.getLanguageCode())));
            }
        }
        if (examples.isEmpty()) {
            throw new IllegalExtensionConfigurationException(
                    "Parser 'semanticFallback' has no examples: add 'examples', or dictionaries with phrases that carry expressions.");
        }
        List<SemanticFallback.Example> distinct = examples.stream().distinct().toList();
        if (distinct.size() > SemanticFallback.MAX_EXAMPLES) {
            log.warnf("Parser 'semanticFallback' has %d examples; only the first %d are used.", distinct.size(), SemanticFallback.MAX_EXAMPLES);
            distinct = distinct.subList(0, SemanticFallback.MAX_EXAMPLES);
        }

        var ragConfiguration = new RagConfiguration();
        ragConfiguration.setEmbeddingProvider(fallback.embeddingProvider().trim());
        ragConfiguration.setEmbeddingParameters(fallback.embeddingParameters() == null ? Map.of() : fallback.embeddingParameters());
        // A symmetric task: the input and the examples are the same kind of text, so
        // both are embedded in the query role.
        return new SemanticFallback(() -> embeddingModelFactory.getOrCreate(ragConfiguration, EmbeddingInputType.QUERY), distinct, minScore);
    }

    private static boolean hasMeaningfulExpression(Expressions expressions) {
        return expressions != null && expressions.stream().anyMatch(DictionaryUtilities::isMeaningful);
    }

    /**
     * The {@code semanticFallback} block of a parser configuration.
     *
     * @param embeddingProvider
     *            as for a knowledge base: openai, azure-openai, ollama, mistral,
     *            bedrock, cohere, gemini, vertex
     * @param embeddingParameters
     *            the provider's parameters (model name, API key as a vault
     *            reference, base URL), as for a knowledge base
     * @param minScore
     *            least cosine similarity that counts as a match; default 0.8
     * @param includeDictionaryPhrases
     *            use the parser's dictionary phrases as examples; default true
     * @param includeDictionaryWords
     *            use its single dictionary words as examples too; default false
     * @param examples
     *            further example sentences
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SemanticFallbackConfiguration(String embeddingProvider, Map<String, String> embeddingParameters, Double minScore,
            Boolean includeDictionaryPhrases, Boolean includeDictionaryWords, List<ExampleConfiguration> examples) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ExampleConfiguration(String text, String expressions, String lang) {
    }

    /**
     * Reads an optional number in 0..1. Missing, unparseable or out-of-range values
     * fall back to the default, so a typo cannot switch matching off outright.
     */
    private static double parseFraction(Object value, double defaultValue, boolean allowZero) {
        if (isNullOrEmpty(value)) {
            return defaultValue;
        }
        try {
            double parsed = Double.parseDouble(value.toString().trim());
            if (Double.isNaN(parsed) || parsed < 0.0 || parsed > 1.0 || (!allowZero && parsed == 0.0)) {
                log.warnf("Parser fraction must be %s 0 and at most 1, but was %s. Falling back to %s.", allowZero ? "at least" : "above", value,
                        defaultValue);
                return defaultValue;
            }
            return parsed;
        } catch (NumberFormatException e) {
            log.warnf("Parser fraction '%s' is not a number. Falling back to %s.", value, defaultValue);
            return defaultValue;
        }
    }

    /** {@code maxAlternatives}, clamped to 0..{@link #MAX_ALTERNATIVES_LIMIT}. */
    private static int parseAlternatives(Object value) {
        if (isNullOrEmpty(value)) {
            return 0;
        }
        try {
            int parsed = Integer.parseInt(value.toString().trim());
            return Math.max(0, Math.min(MAX_ALTERNATIVES_LIMIT, parsed));
        } catch (NumberFormatException e) {
            log.warnf("Parser 'maxAlternatives' value '%s' is not a number. Storing no alternatives.", value);
            return 0;
        }
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
        extensionConfigs.put(CONFIG_MATCH_PARTIAL_PHRASES, new ConfigValue("Match Partial Phrases", BOOLEAN, true, false));
        extensionConfigs.put(CONFIG_MIN_PARTIAL_PHRASE_COVERAGE,
                new ConfigValue("Min Partial Phrase Coverage", DOUBLE, true, IInputParser.Config.DEFAULT_MIN_PARTIAL_PHRASE_COVERAGE));
        extensionConfigs.put(CONFIG_MIN_MATCHING_ACCURACY, new ConfigValue("Min Matching Accuracy", DOUBLE, true, 0.0));
        extensionConfigs.put(CONFIG_MAX_ALTERNATIVES, new ConfigValue("Max Alternatives Stored", INT, true, 0));
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
