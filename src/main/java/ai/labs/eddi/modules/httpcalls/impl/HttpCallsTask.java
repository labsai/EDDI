package ai.labs.eddi.modules.httpcalls.impl;

import ai.labs.eddi.configs.httpcalls.model.*;
import ai.labs.eddi.configs.packages.model.ExtensionDescriptor;
import ai.labs.eddi.configs.packages.model.ExtensionDescriptor.ConfigValue;
import ai.labs.eddi.configs.packages.model.ExtensionDescriptor.FieldType;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.exceptions.PackageConfigurationException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.engine.memory.MemoryKeys.ACTIONS;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;
import static java.lang.String.format;

@ApplicationScoped
public class HttpCallsTask implements ILifecycleTask {
    public static final String ID = "ai.labs.httpcalls";
    private static final String KEY_HTTP_CALLS = "httpCalls";
    private final IResourceClientLibrary resourceClientLibrary;
    private final IMemoryItemConverter memoryItemConverter;
    private final IHttpCallExecutor httpCallExecutor;

    private static final Logger LOGGER = Logger.getLogger(HttpCallsTask.class);

    @Inject
    public HttpCallsTask(IResourceClientLibrary resourceClientLibrary,
            IMemoryItemConverter memoryItemConverter,
            IHttpCallExecutor httpCallExecutor) {
        this.resourceClientLibrary = resourceClientLibrary;
        this.memoryItemConverter = memoryItemConverter;
        this.httpCallExecutor = httpCallExecutor;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getType() {
        return KEY_HTTP_CALLS;
    }

    @Override
    public void execute(IConversationMemory memory, Object component) throws LifecycleException {
        final var httpCallsConfig = (HttpCallsConfiguration) component;

        IWritableConversationStep currentStep = memory.getCurrentStep();
        IData<List<String>> latestData = currentStep.getLatestData(ACTIONS);
        if (latestData == null) {
            return;
        }

        Map<String, Object> templateDataObjects = memoryItemConverter.convert(memory);
        List<String> actions = latestData.getResult();

        for (String action : actions) {
            List<HttpCall> filteredHttpCalls = httpCallsConfig.getHttpCalls().stream().filter(httpCall -> {
                List<String> httpCallActions = httpCall.getActions();
                return httpCallActions.contains(action) || httpCallActions.contains("*");
            }).distinct().toList();

            for (var call : filteredHttpCalls) {
                var httpCallResult = httpCallExecutor.execute(call, memory, templateDataObjects,
                        httpCallsConfig.getTargetServerUrl());
                // HttpCallExecutor stores response in conversation memory via prePostUtils.
                // We also merge into templateDataObjects so subsequent calls in this loop can
                // reference previous results.
                if (httpCallResult != null && !httpCallResult.isEmpty()) {
                    templateDataObjects.putAll(httpCallResult);
                }
            }
        }
    }

    @Override
    public Object configure(Map<String, Object> configuration, Map<String, Object> extensions)
            throws PackageConfigurationException {

        Object uriObj = configuration.get("uri");
        if (!isNullOrEmpty(uriObj)) {
            URI uri = URI.create(uriObj.toString());

            try {
                HttpCallsConfiguration httpCallsConfig = resourceClientLibrary.getResource(uri,
                        HttpCallsConfiguration.class);

                String targetServerUrl = httpCallsConfig.getTargetServerUrl();
                if (isNullOrEmpty(targetServerUrl)) {
                    String message = format(
                            "Property \"targetServerUrl\" in HttpCalls cannot be null or empty! (uri:%s)", uriObj);
                    throw new ServiceException(message);
                }
                if (targetServerUrl.endsWith("/")) {
                    targetServerUrl = targetServerUrl.substring(0, targetServerUrl.length() - 2);
                }
                httpCallsConfig.setTargetServerUrl(targetServerUrl);
                return httpCallsConfig;
            } catch (ServiceException e) {
                LOGGER.error(e.getLocalizedMessage(), e);
                throw new PackageConfigurationException(e.getMessage(), e);
            }
        }

        throw new PackageConfigurationException("No resource URI has been defined! [HttpCallsConfiguration]");
    }

    @Override
    public ExtensionDescriptor getExtensionDescriptor() {
        ExtensionDescriptor extensionDescriptor = new ExtensionDescriptor(ID);
        extensionDescriptor.setDisplayName("Http Calls");
        ConfigValue configValue = new ConfigValue("Resource URI", FieldType.URI, false, null);
        extensionDescriptor.getConfigs().put("uri", configValue);
        return extensionDescriptor;
    }
}
