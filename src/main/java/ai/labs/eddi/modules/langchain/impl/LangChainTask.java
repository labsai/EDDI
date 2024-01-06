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
import dev.ai4j.openai4j.OpenAiClient;
import dev.ai4j.openai4j.chat.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

@ApplicationScoped
public class LangChainTask implements ILifecycleTask {
    public static final String ID = "ai.labs.langchain";
    private static final String ACTION_KEY = "actions";
    private static final String KEY_LANG_CHAIN = "langChain";

    private static final String MEMORY_OUTPUT_IDENTIFIER = "output";
    private final IResourceClientLibrary resourceClientLibrary;
    private final IDataFactory dataFactory;
    private final IMemoryItemConverter memoryItemConverter;
    private final OpenAiClient openAiClient;

    private static final Logger LOGGER = Logger.getLogger(LangChainTask.class);

    @Inject
    public LangChainTask(IResourceClientLibrary resourceClientLibrary, IDataFactory dataFactory, IMemoryItemConverter memoryItemConverter,
                         OpenAiClient openAiClient) {
        this.resourceClientLibrary = resourceClientLibrary;
        this.dataFactory = dataFactory;
        this.memoryItemConverter = memoryItemConverter;
        this.openAiClient = openAiClient;
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
        final var langChainConfig = (LangChainConfiguration) component;

        IWritableConversationStep currentStep = memory.getCurrentStep();
        IData<List<String>> latestData = currentStep.getLatestData(ACTION_KEY);
        if (latestData == null) {
            return;
        }

        Map<String, Object> templateDataObjects = memoryItemConverter.convert(memory);
        List<String> actions = latestData.getResult();


        var chatCompletionRequest = ChatCompletionRequest.builder().
                addSystemMessage(langChainConfig.systemMessage()).
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
        var content = completionResponse.content();

        var outputData = dataFactory.createData("output:text:langchain", content);
        // outputData.setPublic(true);
        currentStep.storeData(outputData);
        currentStep.addConversationOutputString(MEMORY_OUTPUT_IDENTIFIER, content);
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

                /*String targetServerUrl = langChainConfig.getTargetServerUrl();
                if (isNullOrEmpty(targetServerUrl)) {
                    String message = format("Property \"targetServerUrl\" in HttpCalls cannot be null or empty! (uri:%s)", uriObj);
                    throw new ServiceException(message);
                }
                if (targetServerUrl.endsWith(SLASH_CHAR)) {
                    targetServerUrl = targetServerUrl.substring(0, targetServerUrl.length() - 2);
                }
                langChainConfig.setTargetServerUrl(targetServerUrl);*/
                return langChainConfig;
            } catch (ServiceException e) {
                LOGGER.error(e.getLocalizedMessage(), e);
                throw new PackageConfigurationException(e.getMessage(), e);
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

    private static class LangChainValidationException extends Exception {
    }
}
