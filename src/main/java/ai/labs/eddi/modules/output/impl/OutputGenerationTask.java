package ai.labs.eddi.modules.output.impl;

import ai.labs.eddi.configs.output.model.OutputConfiguration;
import ai.labs.eddi.configs.output.model.OutputConfigurationSet;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.exceptions.PackageConfigurationException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IConversationProperties;
import ai.labs.eddi.engine.memory.IConversationMemory.IConversationStepStack;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.models.Context;
import ai.labs.eddi.models.ExtensionDescriptor;
import ai.labs.eddi.models.ExtensionDescriptor.ConfigValue;
import ai.labs.eddi.models.ExtensionDescriptor.FieldType;
import ai.labs.eddi.modules.output.IOutputFilter;
import ai.labs.eddi.modules.output.IOutputGeneration;
import ai.labs.eddi.modules.output.model.OutputEntry;
import ai.labs.eddi.modules.output.model.OutputItem;
import ai.labs.eddi.modules.output.model.OutputValue;
import ai.labs.eddi.modules.output.model.QuickReply;
import ai.labs.eddi.modules.output.model.types.QuickReplyOutputItem;
import ai.labs.eddi.utils.StringUtilities;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static ai.labs.eddi.engine.memory.ContextUtilities.retrieveContextLanguageFromLongTermMemory;
import static java.lang.String.format;

/**
 * @author ginccc
 */
@ApplicationScoped
public class OutputGenerationTask implements ILifecycleTask {
    public static final String ID = "ai.labs.output";
    private static final String KEY_ACTIONS = "actions";
    private static final String MEMORY_OUTPUT_IDENTIFIER = "output";
    private static final String MEMORY_QUICK_REPLIES_IDENTIFIER = "quickReplies";
    private static final String CONTEXT_IDENTIFIER = "context";
    private static final String QUICK_REPLIES_IDENTIFIER = "quickReplies";
    private static final String OUTPUT_SET_CONFIG_URI = "uri";
    private static final String KEY_VALUE = "value";
    private static final String KEY_VALUE_ALTERNATIVES = "valueAlternatives";
    private static final String KEY_EXPRESSIONS = "expressions";
    private static final String KEY_IS_DEFAULT = "isDefault";
    private static final String OUTPUT_TYPE_QUICK_REPLY = "quickReply";
    public static final String OUTPUT_TYPE = "output";
    private final IResourceClientLibrary resourceClientLibrary;
    private final IDataFactory dataFactory;
    private final ObjectMapper objectMapper;

    private static final Logger log = Logger.getLogger(OutputGenerationTask.class);

    @Inject
    public OutputGenerationTask(IResourceClientLibrary resourceClientLibrary,
                                IDataFactory dataFactory,
                                ObjectMapper objectMapper) {
        this.resourceClientLibrary = resourceClientLibrary;
        this.dataFactory = dataFactory;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getType() {
        return OUTPUT_TYPE;
    }

    @Override
    public void execute(IConversationMemory memory, Object component) {
        final var outputGeneration = (IOutputGeneration) component;

        var currentStep = memory.getCurrentStep();
        List<IData<Context>> contextDataList = currentStep.getAllData(CONTEXT_IDENTIFIER);
        storeContextOutput(currentStep, contextDataList);
        storeContextQuickReplies(currentStep, contextDataList);

        if (outputGeneration != null) {
            if (checkLanguage(outputGeneration.getLanguage(), memory.getConversationProperties())) {

                IData<List<String>> latestData = currentStep.getLatestData(KEY_ACTIONS);
                if (latestData == null) {
                    return;
                }
                List<String> actions = latestData.getResult();
                List<IOutputFilter> outputFilters = createOutputFilters(memory, actions);

                Map<String, List<OutputEntry>> outputs = outputGeneration.getOutputs(outputFilters);
                outputs.forEach((action, outputEntries) ->
                        outputEntries.forEach(outputEntry -> {
                            List<OutputValue> outputValues = outputEntry.getOutputs();
                            selectAndStoreOutput(currentStep, action, outputValues);
                            storeQuickReplies(currentStep, outputEntry.getQuickReplies(), outputEntry.getAction());
                        }));
            }
        } else {
            log.error(
                    format("OutputGeneration component was unexpectedly null. (botId=%s, conversationId=%s).",
                            memory.getBotId(), memory.getConversationId()));
        }
    }

    private boolean checkLanguage(String outputLanguage, IConversationProperties conversationProperties) {
        return outputLanguage == null ||
                outputLanguage.equalsIgnoreCase(retrieveContextLanguageFromLongTermMemory(conversationProperties));
    }

    private void storeContextOutput(IWritableConversationStep currentStep, List<IData<Context>> contextDataList) {
        contextDataList.forEach(contextData -> {
            String contextKey = contextData.getKey();
            Context context = contextData.getResult();
            String key = contextKey.substring((CONTEXT_IDENTIFIER + ":").length());
            if (key.startsWith(MEMORY_OUTPUT_IDENTIFIER) && context.getType().equals(Context.ContextType.object)) {
                List<OutputValue> outputList = convertOutputMap(convertObjectToListOfMapsWithObjects(context.getValue()));
                selectAndStoreOutput(currentStep, CONTEXT_IDENTIFIER, outputList);
            }
        });
    }

    private void storeContextQuickReplies(IWritableConversationStep currentStep, List<IData<Context>> contextDataList) {
        contextDataList.forEach(contextData -> {
            String contextKey = contextData.getKey();
            Context context = contextData.getResult();
            String key = contextKey.substring((CONTEXT_IDENTIFIER + ":").length());
            if (key.startsWith(QUICK_REPLIES_IDENTIFIER) && context.getType().equals(Context.ContextType.object)) {
                String contextQuickReplyKey = CONTEXT_IDENTIFIER + ":" + QUICK_REPLIES_IDENTIFIER + ":";
                String quickRepliesKey = "context";
                if (contextKey.contains(contextQuickReplyKey)) {
                    quickRepliesKey = contextKey.substring(contextQuickReplyKey.length());
                }

                List<QuickReply> quickReplies = convertQuickReplyMap(convertObjectToListOfMapsWithStrings(context.getValue()));
                storeQuickReplies(currentStep, quickReplies, quickRepliesKey);
            }
        });
    }

    private List<Map<String, Object>> convertObjectToListOfMapsWithObjects(Object object) {
        return objectMapper.convertValue(object, new TypeReference<>() {
        });
    }

    private List<Map<String, String>> convertObjectToListOfMapsWithStrings(Object object) {
        return objectMapper.convertValue(object, new TypeReference<>() {
        });
    }

    private List<OutputValue> convertOutputMap(List<Map<String, Object>> outputMapList) {
        return outputMapList.stream().map(map ->
                        new OutputValue(objectMapper.convertValue(map.get(KEY_VALUE_ALTERNATIVES), new TypeReference<>() {
                        }))).
                collect(Collectors.toList());
    }

    private List<QuickReply> convertQuickReplyMap(List<Map<String, String>> quickRepliesMapList) {
        return quickRepliesMapList.stream().map(map ->
                        new QuickReply(map.get(KEY_VALUE), map.get(KEY_EXPRESSIONS),
                                Boolean.parseBoolean(map.getOrDefault(KEY_IS_DEFAULT, "false")))).
                collect(Collectors.toList());
    }

    private void selectAndStoreOutput(IWritableConversationStep currentStep, String action, List<OutputValue> outputValues) {
        List<QuickReply> quickReplies = new LinkedList<>();
        IntStream.range(0, outputValues.size()).forEach(index -> {
            OutputValue outputValue = outputValues.get(index);
            List<OutputItem> possibleValueAlternatives = outputValue.getValueAlternatives();
            OutputItem randomValue;
            if (!possibleValueAlternatives.isEmpty()) {
                randomValue = chooseRandomly(possibleValueAlternatives);

                if (OUTPUT_TYPE_QUICK_REPLY.equals(randomValue.getType())) {
                    var qr = (QuickReplyOutputItem) randomValue;
                    quickReplies.add(new QuickReply(qr.getValue(), qr.getExpressions(), qr.getIsDefault()));
                } else {
                    var outputKey = createOutputKey(action, outputValues, randomValue.getType(), index);
                    var outputData = dataFactory.createData(outputKey, randomValue, possibleValueAlternatives);
                    outputData.setPublic(true);
                    currentStep.storeData(outputData);
                    currentStep.addConversationOutputList(MEMORY_OUTPUT_IDENTIFIER, Collections.singletonList(randomValue));
                }
            }
        });

        if (!quickReplies.isEmpty()) {
            storeQuickReplies(currentStep, quickReplies, action);
        }
    }

    private void storeQuickReplies(IWritableConversationStep currentStep, List<QuickReply> quickReplies, String action) {
        if (!quickReplies.isEmpty()) {
            String outputQuickReplyKey = StringUtilities.
                    joinStrings(":", MEMORY_QUICK_REPLIES_IDENTIFIER, action);
            var outputQuickReplies = dataFactory.createData(outputQuickReplyKey, quickReplies);
            outputQuickReplies.setPublic(true);
            currentStep.storeData(outputQuickReplies);
            currentStep.addConversationOutputList(MEMORY_QUICK_REPLIES_IDENTIFIER, quickReplies);
        }
    }

    private LinkedList<IOutputFilter> createOutputFilters(IConversationMemory memory, List<String> actions) {
        return actions.stream().map(action ->
                        new OutputFilter(action, countActionOccurrences(memory.getPreviousSteps(), action))).
                collect(Collectors.toCollection(LinkedList::new));
    }

    @Override
    public Object configure(Map<String, Object> configuration, Map<String, Object> extensions)
            throws PackageConfigurationException {

        Object uriObj = configuration.get(OUTPUT_SET_CONFIG_URI);
        URI uri = URI.create(uriObj.toString());

        try {
            var outputConfigurationSet = resourceClientLibrary.getResource(uri, OutputConfigurationSet.class);
            var outputLanguage = outputConfigurationSet.getLang();
            var outputSet = outputConfigurationSet.getOutputSet();
            outputSet.sort((o1, o2) -> {
                int comparisonOfKeys = o1.getAction().compareTo(o2.getAction());
                if (comparisonOfKeys == 0) {
                    return Integer.compare(o1.getTimesOccurred(), o2.getTimesOccurred());
                } else {
                    return comparisonOfKeys;
                }
            });

            var outputGeneration = new OutputGeneration(outputLanguage);
            outputSet.forEach(outputConfig -> outputGeneration.addOutputEntry(
                    new OutputEntry(outputConfig.getAction(),
                            outputConfig.getTimesOccurred(),
                            convertOutputTypesConfig(outputConfig.getOutputs()),
                            convertQuickRepliesConfig(outputConfig.getQuickReplies()))));

            return outputGeneration;
        } catch (ServiceException e) {
            String message = "Error while fetching OutputConfigurationSet!\n" + e.getLocalizedMessage();
            throw new PackageConfigurationException(message, e);
        }
    }

    private String createOutputKey(String action, List<OutputValue> outputValues, String outputType, int idx) {
        if (outputValues.size() > 1) {
            return StringUtilities.joinStrings(":", MEMORY_OUTPUT_IDENTIFIER,
                    outputType, action, idx);
        } else {
            return StringUtilities.joinStrings(":", MEMORY_OUTPUT_IDENTIFIER,
                    outputType, action);
        }
    }

    private OutputItem chooseRandomly(List<OutputItem> possibleValues) {
        return possibleValues.get(new Random().nextInt(possibleValues.size()));
    }

    private int countActionOccurrences(IConversationStepStack conversationStepStack,
                                       String action) {

        int count = 0;
        for (int i = 0; i < conversationStepStack.size(); i++) {
            var conversationStep = conversationStepStack.get(i);
            IData<List<String>> latestData = conversationStep.getLatestData(KEY_ACTIONS);
            if (latestData != null) {
                List<String> actions = latestData.getResult();
                if (actions.contains(action)) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * helper method to convert from OutputConfiguration to internal Output Value
     *
     * @param configOutputs List<OutputConfiguration.OutputType> as it comes from the configuration repository
     * @return List<OutputValue> as it is used in the internal system
     */
    private List<OutputValue> convertOutputTypesConfig(List<OutputConfiguration.Output> configOutputs) {
        return configOutputs.stream().map(configOutput -> {
            OutputValue outputValue = new OutputValue();
            outputValue.setValueAlternatives(configOutput.getValueAlternatives());
            return outputValue;
        }).collect(Collectors.toCollection(LinkedList::new));
    }

    /**
     * helper method to convert from OutputConfiguration to internal QuickReply
     *
     * @param configQuickReplies List<OutputConfiguration.QuickReply> as it comes from the configuration repository
     * @return List<QuickReply> as it is used in the internal system
     */
    private List<QuickReply> convertQuickRepliesConfig(List<QuickReply> configQuickReplies) {
        return configQuickReplies.stream().map(configQuickReply -> {
            QuickReply quickReply = new QuickReply();
            quickReply.setValue(configQuickReply.getValue());
            quickReply.setExpressions(configQuickReply.getExpressions());
            return quickReply;
        }).collect(Collectors.toCollection(LinkedList::new));
    }

    @Override
    public ExtensionDescriptor getExtensionDescriptor() {
        ExtensionDescriptor extensionDescriptor = new ExtensionDescriptor(ID);
        extensionDescriptor.setDisplayName("Output Generation");

        ConfigValue configValue =
                new ConfigValue("Resource URI", FieldType.URI, false, null);
        extensionDescriptor.getConfigs().put(OUTPUT_SET_CONFIG_URI, configValue);
        return extensionDescriptor;
    }
}
