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
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static ai.labs.eddi.configs.packages.model.ExtensionDescriptor.ConfigValue;
import static ai.labs.eddi.configs.packages.model.ExtensionDescriptor.FieldType;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

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
    public static final String MATCH_ALL_OPERATOR = "*";

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
                if (task.actions().contains(MATCH_ALL_OPERATOR) ||
                        task.actions().stream().anyMatch(actions::contains)) {

                    var parameters = task.parameters();
                    var messages = new LinkedList<ChatMessage>();
                    if (parameters.containsKey(KEY_SYSTEM_MESSAGE)) {
                        var systemMessage = templatingEngine.processTemplate(
                                parameters.get(KEY_SYSTEM_MESSAGE), templateDataObjects);
                        messages = new LinkedList<>(List.of(new SystemMessage(systemMessage)));
                    }

                    int logSizeLimit = -1;
                    if (parameters.containsKey((KEY_LOG_SIZE_LIMIT))) {
                        logSizeLimit = Integer.parseInt(parameters.get(KEY_LOG_SIZE_LIMIT));
                    }

                    boolean includeFirstBotMessage = true;
                    if (parameters.containsKey((KEY_INCLUDE_FIRST_BOT_MESSAGE))) {
                        includeFirstBotMessage = Boolean.parseBoolean(parameters.get(KEY_INCLUDE_FIRST_BOT_MESSAGE));
                    }

                    var chatMessages = new ArrayList<>(new ConversationLogGenerator(memory).
                            generate(logSizeLimit, includeFirstBotMessage)
                            .getMessages()
                            .stream()
                            .map(this::convertMessage)
                            .toList());

                    if (chatMessages.isEmpty()) {
                        //start of the conversation
                        continue;
                    }

                    if (!isNullOrEmpty(parameters.get(KEY_PROMPT))) {
                        // if there is a prompt defined, we override last user input with it
                        // to allow alerting the user input before it hits the LLM
                        chatMessages.removeLast();
                        chatMessages.add(new UserMessage(
                                templatingEngine.processTemplate(parameters.get(KEY_PROMPT), templateDataObjects)));
                    }

                    messages.addAll(chatMessages);
                    var chatLanguageModel = getChatLanguageModel(task.type(), task.parameters());
                    prePostUtils.executePreRequestPropertyInstructions(memory, templateDataObjects, task.preRequest());
                    var messageResponse = chatLanguageModel.generate(messages);
                    var content = messageResponse.content().text();

                    var langchainObjects = templateDataObjects.containsKey(KEY_LANGCHAIN) ?
                            (Map<String, Object>) templateDataObjects.get(KEY_LANGCHAIN) :
                            new HashMap<String, Object>();

                    if (!isNullOrEmpty(parameters.get(KEY_CONVERT_TO_OBJECT))) {
                        var contentAsObject =
                                jsonSerialization.deserialize(parameters.get(KEY_CONVERT_TO_OBJECT), Map.class);
                        langchainObjects.put(task.id(), contentAsObject);
                    } else {
                        langchainObjects.put(task.id(), content);
                    }
                    templateDataObjects.put(KEY_LANGCHAIN, langchainObjects);

                    var langchainData = dataFactory.createData(KEY_LANGCHAIN + ":" + task.type() + ":" + task.id(), content);
                    currentStep.storeData(langchainData);

                    if (!isNullOrEmpty(parameters.get(KEY_ADD_TO_OUTPUT))) {
                        var outputData = dataFactory.createData(LANGCHAIN_OUTPUT_IDENTIFIER + ":" + task.type(), content);
                        currentStep.storeData(outputData);
                        var outputItem = new TextOutputItem(content, 0);
                        currentStep.addConversationOutputList(MEMORY_OUTPUT_IDENTIFIER, List.of(outputItem));
                    }

                    if (task.postResponse() != null && task.postResponse().getPropertyInstructions() != null) {
                        prePostUtils.executePropertyInstructions(task.postResponse().getPropertyInstructions(),
                                0, false, memory, templateDataObjects);
                    }
                }
            }

        } catch (ITemplatingEngine.TemplateEngineException | UnsupportedLangchainTaskException | IOException e) {
            throw new LifecycleException(e.getLocalizedMessage(), e);
        }
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
            case "user" -> UserMessage.from(eddiMessage.getContent());
            case "assistant" -> AiMessage.from(eddiMessage.getContent());
            default -> SystemMessage.from(eddiMessage.getContent());
        };
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
