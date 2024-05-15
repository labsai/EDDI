package ai.labs.eddi.modules.langchain.impl;


import ai.labs.eddi.configs.packages.model.ExtensionDescriptor;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.exceptions.PackageConfigurationException;
import ai.labs.eddi.engine.memory.*;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.model.ConversationLog;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
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

import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static ai.labs.eddi.configs.packages.model.ExtensionDescriptor.ConfigValue;
import static ai.labs.eddi.configs.packages.model.ExtensionDescriptor.FieldType;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

@ApplicationScoped
public class LangChainTask implements ILifecycleTask {
    public static final String ID = "ai.labs.langchain";

    private static final String ACTION_KEY = "actions";
    private static final String KEY_URI = "uri";
    private static final String KEY_LANGCHAIN = "langchain";
    private static final String KEY_SYSTEM_MESSAGE = "systemMessage";
    private static final String KEY_LOG_SIZE_LIMIT = "logSizeLimit";

    static final String MEMORY_OUTPUT_IDENTIFIER = "output";
    static final String LANGCHAIN_OUTPUT_IDENTIFIER = MEMORY_OUTPUT_IDENTIFIER + ":text:langchain";

    private final IResourceClientLibrary resourceClientLibrary;
    private final IDataFactory dataFactory;
    private final IMemoryItemConverter memoryItemConverter;
    private final ITemplatingEngine templatingEngine;
    private final Map<String, Provider<ILanguageModelBuilder>> languageModelApiConnectorBuilders;

    private static final Logger LOGGER = Logger.getLogger(LangChainTask.class);
    private final Map<ModelCacheKey, ChatLanguageModel> modelCache = new ConcurrentHashMap<>(1);

    @Inject
    public LangChainTask(IResourceClientLibrary resourceClientLibrary,
                         IDataFactory dataFactory,
                         IMemoryItemConverter memoryItemConverter,
                         ITemplatingEngine templatingEngine,
                         Map<String, Provider<ILanguageModelBuilder>> languageModelApiConnectorBuilders) {
        this.resourceClientLibrary = resourceClientLibrary;
        this.dataFactory = dataFactory;
        this.memoryItemConverter = memoryItemConverter;
        this.templatingEngine = templatingEngine;
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
            IData<List<String>> latestData = currentStep.getLatestData(ACTION_KEY);
            if (latestData == null) {
                return;
            }

            var templateDataObjects = memoryItemConverter.convert(memory);
            var actions = latestData.getResult();
            if (actions == null) {
                return;
            }

            for (var task : langChainConfig.tasks()) {
                if (task.actions().contains("*") || task.actions().stream().anyMatch(actions::contains)) {
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

                    var chatMessages = new ConversationLogGenerator(memory).
                            generate(logSizeLimit)
                            .getMessages()
                            .stream()
                            .map(this::convertMessage)
                            .toList();

                    if (chatMessages.isEmpty()) {
                        //start of the conversation
                        continue;
                    }

                    messages.addAll(chatMessages);
                    var chatLanguageModel = getChatLanguageModel(task.type(), task.parameters());
                    var messageResponse = chatLanguageModel.generate(messages);
                    var content = messageResponse.content().text();
                    var outputData = dataFactory.createData(LANGCHAIN_OUTPUT_IDENTIFIER + ":" + task.type(), content);
                    currentStep.storeData(outputData);
                    var outputItem = new TextOutputItem(content, 0);
                    currentStep.addConversationOutputList(MEMORY_OUTPUT_IDENTIFIER, Collections.singletonList(outputItem));
                }
            }

        } catch (ITemplatingEngine.TemplateEngineException | UnsupportedLangchainTaskException e) {
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
