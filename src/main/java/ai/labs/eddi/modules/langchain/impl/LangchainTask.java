package ai.labs.eddi.modules.langchain.impl;


import ai.labs.eddi.configs.packages.model.ExtensionDescriptor;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.exceptions.PackageConfigurationException;
import ai.labs.eddi.engine.memory.*;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.model.ConversationLog;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.modules.httpcalls.impl.PrePostUtils;
import ai.labs.eddi.modules.langchain.impl.builder.ILanguageModelBuilder;
import ai.labs.eddi.modules.langchain.model.LangChainConfiguration;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatLanguageModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static ai.labs.eddi.configs.packages.model.ExtensionDescriptor.ConfigValue;
import static ai.labs.eddi.configs.packages.model.ExtensionDescriptor.FieldType;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;
import static java.util.Objects.requireNonNullElse;

@ApplicationScoped
public class LangchainTask implements ILifecycleTask {
    public static final String ID = "ai.labs.langchain";

    private static final String KEY_URI = "uri";
    private static final String KEY_ACTIONS = "actions";
    private static final String KEY_LANGCHAIN = "langchain";
    private static final String KEY_SYSTEM_MESSAGE = "systemMessage";
    private static final String KEY_PROMPT = "prompt";
    private static final String KEY_LOG_SIZE_LIMIT = "logSizeLimit";
    private static final String KEY_INCLUDE_FIRST_BOT_MESSAGE = "includeFirstBotMessage";
    private static final String KEY_CONVERT_TO_OBJECT = "convertToObject";
    private static final String KEY_ADD_TO_OUTPUT = "addToOutput";

    static final String MEMORY_OUTPUT_IDENTIFIER = "output";
    static final String LANGCHAIN_OUTPUT_IDENTIFIER = MEMORY_OUTPUT_IDENTIFIER + ":text:langchain";

    private final IResourceClientLibrary resourceClientLibrary;
    private final IDataFactory dataFactory;
    private final IMemoryItemConverter memoryItemConverter;
    private final ITemplatingEngine templatingEngine;
    private final IJsonSerialization jsonSerialization;
    private final PrePostUtils prePostUtils;
    private final Map<String, Provider<ILanguageModelBuilder>> languageModelApiConnectorBuilders;

    private final Map<ModelCacheKey, ChatLanguageModel> modelCache = new ConcurrentHashMap<>(1);

    private static final Logger LOGGER = Logger.getLogger(LangchainTask.class);

    @Inject
    public LangchainTask(IResourceClientLibrary resourceClientLibrary,
                         IDataFactory dataFactory,
                         IMemoryItemConverter memoryItemConverter,
                         ITemplatingEngine templatingEngine,
                         IJsonSerialization jsonSerialization,
                         PrePostUtils prePostUtils,
                         Map<String, Provider<ILanguageModelBuilder>> languageModelApiConnectorBuilders) {
        this.resourceClientLibrary = resourceClientLibrary;
        this.dataFactory = dataFactory;
        this.memoryItemConverter = memoryItemConverter;
        this.templatingEngine = templatingEngine;
        this.jsonSerialization = jsonSerialization;
        this.prePostUtils = prePostUtils;
        this.languageModelApiConnectorBuilders = languageModelApiConnectorBuilders;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getType() {
        return KEY_LANGCHAIN;
    }

    @Override
    public void execute(IConversationMemory memory, Object component) throws LifecycleException {
        final var langChainConfig = (LangChainConfiguration) component;

        try {
            IWritableConversationStep currentStep = memory.getCurrentStep();
            IData<List<String>> latestData = currentStep.getLatestData(KEY_ACTIONS);
            if (latestData == null) {
                return;
            }

            var templateDataObjects = memoryItemConverter.convert(memory);
            var actions = latestData.getResult();
            if (actions == null) {
                return;
            }

            for (var task : langChainConfig.tasks()) {
                if (task.actions().stream().anyMatch(actions::contains)) {
                    var processedParams = runTemplateEngineOnParams(task.parameters(), templateDataObjects);
                    var messages = new LinkedList<ChatMessage>();

                    if (!isNullOrEmpty(processedParams.get(KEY_SYSTEM_MESSAGE))) {
                        var systemMessage = processedParams.get(KEY_SYSTEM_MESSAGE);
                        messages = new LinkedList<>(List.of(new SystemMessage(systemMessage)));
                    }

                    int logSizeLimit = -1;
                    if (!isNullOrEmpty(processedParams.get((KEY_LOG_SIZE_LIMIT)))) {
                        logSizeLimit = Integer.parseInt(processedParams.get(KEY_LOG_SIZE_LIMIT));
                    }

                    boolean includeFirstBotMessage = true;
                    if (!isNullOrEmpty(processedParams.get((KEY_INCLUDE_FIRST_BOT_MESSAGE)))) {
                        includeFirstBotMessage = Boolean.parseBoolean(processedParams.get(KEY_INCLUDE_FIRST_BOT_MESSAGE));
                    }

                    var chatMessages = new ArrayList<>(new ConversationLogGenerator(memory).
                            generate(logSizeLimit, includeFirstBotMessage)
                            .getMessages()
                            .stream()
                            .map(this::convertMessage)
                            .toList());

                    if (!isNullOrEmpty(processedParams.get(KEY_PROMPT))) {
                        // if there is a prompt defined, we replace the last user input with it
                        // to allow changing the user input before it is being sent to the LLM
                        if (!chatMessages.isEmpty()) {
                            chatMessages.removeLast();
                        }
                        chatMessages.add(UserMessage.from(processedParams.get(KEY_PROMPT)));
                    }

                    messages.addAll(chatMessages);
                    if (messages.isEmpty()) {
                        continue;
                    }
                    var chatLanguageModel = getChatLanguageModel(task.type(), filterParams(processedParams));
                    prePostUtils.executePreRequestPropertyInstructions(memory, templateDataObjects, task.preRequest());
                    var messageResponse = chatLanguageModel.chat(messages);
                    var responseContent = messageResponse.aiMessage().text();
                    var responseMetadata = messageResponse.metadata();

                    var responseMetadataObjectName = task.responseMetadataObjectName();
                    if (!isNullOrEmpty(responseMetadataObjectName)) {
                        var responseObjectHeader = requireNonNullElse(responseMetadata, new HashMap<>());
                        templateDataObjects.put(responseMetadataObjectName, responseObjectHeader);
                        prePostUtils.createMemoryEntry(currentStep, responseObjectHeader, responseMetadataObjectName, KEY_LANGCHAIN);
                    }

                    var responseObjectName = task.responseObjectName();
                    if (isNullOrEmpty(responseObjectName)) {
                        responseObjectName = task.id();
                    }
                    if (!isNullOrEmpty(processedParams.get(KEY_CONVERT_TO_OBJECT)) &&
                            Boolean.parseBoolean(processedParams.get(KEY_CONVERT_TO_OBJECT))) {
                        var contentAsObject =
                                jsonSerialization.deserialize(processedParams.get(KEY_CONVERT_TO_OBJECT), Map.class);
                        templateDataObjects.put(responseObjectName, contentAsObject);
                    } else {
                        templateDataObjects.put(responseObjectName, responseContent);
                    }

                    var langchainData = dataFactory.createData(KEY_LANGCHAIN + ":" + task.type() + ":" + task.id(), responseContent);
                    currentStep.storeData(langchainData);

                    if (!isNullOrEmpty(processedParams.get(KEY_ADD_TO_OUTPUT)) &&
                            Boolean.parseBoolean(processedParams.get(KEY_ADD_TO_OUTPUT))) {
                        var outputData = dataFactory.createData(LANGCHAIN_OUTPUT_IDENTIFIER + ":" + task.type(), responseContent);
                        currentStep.storeData(outputData);
                        var outputItem = new TextOutputItem(responseContent, 0);
                        currentStep.addConversationOutputList(MEMORY_OUTPUT_IDENTIFIER, List.of(outputItem));
                    }

                    prePostUtils.runPostResponse(memory, task.postResponse(), templateDataObjects, 200, false);
                }
            }

        } catch (ITemplatingEngine.TemplateEngineException | UnsupportedLangchainTaskException | IOException e) {
            throw new LifecycleException(e.getLocalizedMessage(), e);
        }
    }

    private HashMap<String, String> runTemplateEngineOnParams(Map<String, String> parameters,
                                                              Map<String, Object> templateDataObjects) {

        var processedParams = new HashMap<>(parameters);
        processedParams.forEach((key, value) -> {
            try {
                if (!isNullOrEmpty(value)) {
                    processedParams.put(key, templatingEngine.processTemplate(value, templateDataObjects));
                }
            } catch (ITemplatingEngine.TemplateEngineException e) {
                LOGGER.error(e.getLocalizedMessage(), e);
            }
        });
        return processedParams;
    }

    private Map<String, String> filterParams(HashMap<String, String> processedParams) {
        //remove all props that are not directly configuring the langchain builders (for better caching)
        var returnMap = new HashMap<>(processedParams);
        returnMap.remove(KEY_INCLUDE_FIRST_BOT_MESSAGE);
        returnMap.remove(KEY_SYSTEM_MESSAGE);
        returnMap.remove(KEY_PROMPT);
        returnMap.remove(KEY_LOG_SIZE_LIMIT);
        returnMap.remove(KEY_ADD_TO_OUTPUT);
        returnMap.remove(KEY_CONVERT_TO_OBJECT);
        return returnMap;
    }

    private ChatLanguageModel getChatLanguageModel(String type, Map<String, String> parameters)
            throws UnsupportedLangchainTaskException {

        var cacheKey = new ModelCacheKey(type, parameters);

        if (modelCache.containsKey(cacheKey)) {
            return modelCache.get(cacheKey);
        }

        if (!languageModelApiConnectorBuilders.containsKey(type)) {
            throw new UnsupportedLangchainTaskException(String.format("Type \"%s\" is not supported", type));
        }

        var model = languageModelApiConnectorBuilders.get(type).get().build(parameters);
        modelCache.put(cacheKey, model);

        return model;
    }

    private ChatMessage convertMessage(ConversationLog.ConversationPart eddiMessage) {
        return switch (eddiMessage.getRole().toLowerCase()) {
            case "user" -> {
                var contentList = new LinkedList<Content>();
                for (var content : eddiMessage.getContent()) {
                    switch (content.getType()) {
                        case text -> contentList.add(TextContent.from(content.getValue()));
                        case pdf -> contentList.add(PdfFileContent.from(content.getValue()));
                        case audio -> contentList.add(AudioContent.from(content.getValue()));
                        case video -> contentList.add(VideoContent.from(content.getValue()));
                    }
                }
                yield UserMessage.from(contentList);
            }
            case "assistant" -> AiMessage.from(joinMessages(eddiMessage));
            default -> SystemMessage.from(joinMessages(eddiMessage));
        };
    }

    private static String joinMessages(ConversationLog.ConversationPart eddiMessage) {
        return eddiMessage.getContent().stream().
                map(ConversationLog.ConversationPart.Content::getValue).collect(Collectors.joining(" "));
    }

    @Override
    public Object configure(Map<String, Object> configuration, Map<String, Object> extensions)
            throws PackageConfigurationException {

        Object uriObj = configuration.get(KEY_URI);
        if (!isNullOrEmpty(uriObj)) {
            URI uri = URI.create(uriObj.toString());

            try {
                return resourceClientLibrary.getResource(uri, LangChainConfiguration.class);
            } catch (ServiceException e) {
                LOGGER.error(e.getLocalizedMessage(), e);
                throw new PackageConfigurationException(e.getLocalizedMessage(), e);
            }
        }

        throw new PackageConfigurationException("No resource URI has been defined! [LangChainConfiguration]");
    }

    @Override
    public ExtensionDescriptor getExtensionDescriptor() {
        ExtensionDescriptor extensionDescriptor = new ExtensionDescriptor(ID);
        extensionDescriptor.setDisplayName("Lang Chain");
        ConfigValue configValue = new ConfigValue("Resource URI", FieldType.URI, false, null);
        extensionDescriptor.getConfigs().put(KEY_URI, configValue);
        return extensionDescriptor;
    }

    private record ModelCacheKey(String type, Map<String, String> parameters) {
        private ModelCacheKey(String type, Map<String, String> parameters) {
            this.type = type;
            this.parameters = new HashMap<>(parameters);
        }
    }

    public static class UnsupportedLangchainTaskException extends Exception {
        public UnsupportedLangchainTaskException(String message) {
            super(message);
        }
    }
}
