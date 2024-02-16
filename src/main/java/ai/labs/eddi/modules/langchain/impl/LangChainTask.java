package ai.labs.eddi.modules.langchain.impl;


import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.exceptions.PackageConfigurationException;
import ai.labs.eddi.engine.memory.*;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.model.ConversationLog;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.models.ExtensionDescriptor;
import ai.labs.eddi.models.ExtensionDescriptor.ConfigValue;
import ai.labs.eddi.models.ExtensionDescriptor.FieldType;
import ai.labs.eddi.modules.langchain.model.LangChainConfiguration;
import ai.labs.eddi.modules.langchain.model.OpenAIWrapper;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import dev.ai4j.openai4j.chat.*;
import io.quarkiverse.langchain4j.openai.QuarkusOpenAiClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;
import static java.lang.String.format;

@ApplicationScoped
public class LangChainTask implements ILifecycleTask {
    public static final String ID = "ai.labs.langchain";
    private static final String ACTION_KEY = "actions";
    private static final String KEY_LANG_CHAIN = "langChain";
    private static final String MEMORY_OUTPUT_IDENTIFIER = "output";
    private final IResourceClientLibrary resourceClientLibrary;
    private final IDataFactory dataFactory;
    private final IMemoryItemConverter memoryItemConverter;
    private final ITemplatingEngine templatingEngine;

    private static final Logger LOGGER = Logger.getLogger(LangChainTask.class);

    @Inject
    public LangChainTask(IResourceClientLibrary resourceClientLibrary,
                         IDataFactory dataFactory,
                         IMemoryItemConverter memoryItemConverter,
                         ITemplatingEngine templatingEngine) {
        this.resourceClientLibrary = resourceClientLibrary;
        this.dataFactory = dataFactory;
        this.memoryItemConverter = memoryItemConverter;
        this.templatingEngine = templatingEngine;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getType() {
        return KEY_LANG_CHAIN;
    }

    @Override
    public void execute(IConversationMemory memory, Object component) throws LifecycleException {
        final var openAIWrapper = (OpenAIWrapper) component;
        final var openAiClient = openAIWrapper.openAiClient();
        final var langChainConfig = openAIWrapper.langChainConfiguration();

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

            for (var action : actions) {
                if (langChainConfig.actions().contains(action)) {
                    var systemMessage = templatingEngine.processTemplate(langChainConfig.systemMessage(), templateDataObjects);
                    var chatCompletionRequest = ChatCompletionRequest.builder().
                            addSystemMessage(systemMessage).
                            messages(
                                    new ConversationLogGenerator(memory).
                                            generate(langChainConfig.logSizeLimit())
                                            .getMessages()
                                            .stream()
                                            .map(this::convertMessage)
                                            .collect(Collectors.toList())
                            ).
                            build();

                    var chatCompletionResponseStreaming = openAiClient.chatCompletion(chatCompletionRequest);
                    var completionResponse = chatCompletionResponseStreaming.execute();
                    String content = completionResponse.content();

                    var outputData = dataFactory.createData("output:text:langchain", content);
                    currentStep.storeData(outputData);
                    currentStep.addConversationOutputString(MEMORY_OUTPUT_IDENTIFIER, content);
                }
            }

        } catch (ITemplatingEngine.TemplateEngineException e) {
            throw new LifecycleException(e.getLocalizedMessage(), e);
        }
    }

    private Message convertMessage(ConversationLog.ConversationPart eddiMessage) {
        return switch (eddiMessage.getRole().toLowerCase()) {
            case "user" -> UserMessage.from(eddiMessage.getContent());
            case "assistant" -> AssistantMessage.from(eddiMessage.getContent());
            default -> SystemMessage.from(eddiMessage.getContent());
        };
    }

    @Override
    public Object configure(Map<String, Object> configuration, Map<String, Object> extensions)
            throws PackageConfigurationException {

        Object uriObj = configuration.get("uri");
        if (!isNullOrEmpty(uriObj)) {
            URI uri = URI.create(uriObj.toString());

            try {
                var langChainConfig = resourceClientLibrary.getResource(uri, LangChainConfiguration.class);

                var actions = langChainConfig.actions();
                if (isNullOrEmpty(actions)) {
                    String message = format("Property \"actions\" in LangChain cannot be null or empty! (uri:%s)", uriObj);
                    throw new ServiceException(message);
                }

                var authKey = langChainConfig.authKey();
                if (isNullOrEmpty(authKey)) {
                    String message = format("Property \"authKey\" in LangChain cannot be null or empty! (uri:%s)", uriObj);
                    throw new ServiceException(message);
                }

                return new OpenAIWrapper(langChainConfig, new QuarkusOpenAiClient(langChainConfig.authKey()));
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
        extensionDescriptor.getConfigs().put("uri", configValue);
        return extensionDescriptor;
    }
}
