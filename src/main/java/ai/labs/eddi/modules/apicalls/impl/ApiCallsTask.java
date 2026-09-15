/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import ai.labs.eddi.configs.apicalls.model.*;
import ai.labs.eddi.configs.workflows.model.ExtensionDescriptor;
import ai.labs.eddi.configs.workflows.model.ExtensionDescriptor.ConfigValue;
import ai.labs.eddi.configs.workflows.model.ExtensionDescriptor.FieldType;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.TaskId;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException;
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
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.engine.memory.MemoryKeys.ACTIONS;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;
import static java.lang.String.format;

@ApplicationScoped
public class ApiCallsTask implements ILifecycleTask {
    public static final String ID = "ai.labs.httpcalls";
    public static final TaskId TASK_ID = new TaskId(ID);

    private static final String KEY_HTTP_CALLS = "httpCalls";

    /**
     * Action value that makes an api call match every action of a conversation
     * step.
     */
    static final String WILDCARD_ACTION = "*";

    private final IResourceClientLibrary resourceClientLibrary;
    private final IMemoryItemConverter memoryItemConverter;
    private final IApiCallExecutor httpCallExecutor;

    private static final Logger LOGGER = Logger.getLogger(ApiCallsTask.class);

    @Inject
    public ApiCallsTask(IResourceClientLibrary resourceClientLibrary, IMemoryItemConverter memoryItemConverter, IApiCallExecutor httpCallExecutor) {
        this.resourceClientLibrary = resourceClientLibrary;
        this.memoryItemConverter = memoryItemConverter;
        this.httpCallExecutor = httpCallExecutor;
    }

    @Override
    public TaskId getId() {
        return TASK_ID;
    }

    @Override
    public String getType() {
        return KEY_HTTP_CALLS;
    }

    @Override
    public void execute(IConversationMemory memory, Object component) throws LifecycleException {
        final var httpCallsConfig = (ApiCallsConfiguration) component;

        IWritableConversationStep currentStep = memory.getCurrentStep();
        IData<List<String>> latestData = currentStep.getLatestData(ACTIONS);
        if (latestData == null) {
            return;
        }

        Map<String, Object> templateDataObjects = memoryItemConverter.convert(memory);
        List<String> actions = latestData.getResult();

        for (var call : collectMatchingApiCalls(httpCallsConfig.getHttpCalls(), actions)) {
            var httpCallResult = httpCallExecutor.execute(call, memory, templateDataObjects, httpCallsConfig.getTargetServerUrl());
            // ApiCallExecutor stores response in conversation memory via prePostUtils.
            // We also merge into templateDataObjects so subsequent calls in this loop can
            // reference previous results.
            //
            // SUCCESSFUL results only. The result map doubles as the LLM tool
            // contract, which now populates "body"/"httpCode" on FAILURES too (the
            // model must be able to see a call failed) — but this pipeline's
            // cross-call template contract predates that and never included error
            // text: a failed call used to merge nothing. Without this guard, a
            // failed call's error body would overwrite a previous successful
            // call's {body} and a later call templating it would silently send
            // error text as its payload. Failure details remain available to
            // templates under the scoped keys the executor has always written
            // ({responseObjectName}Error / {responseObjectName}HttpCode).
            if (httpCallResult != null && !httpCallResult.isEmpty() && !isFailureResult(httpCallResult)) {
                templateDataObjects.putAll(httpCallResult);
            }
        }
    }

    /**
     * Whether the executor's result map describes a FAILED call — a non-2xx
     * {@code httpCode}. A missing code is not failure: fire-and-forget returns an
     * empty map, and pre-contract results carried no code at all.
     */
    // Package-private for unit testing.
    static boolean isFailureResult(Map<String, Object> result) {
        return result.get("httpCode") instanceof Integer code && (code < 200 || code >= 300);
    }

    /**
     * Collect every configured api call that matches at least one action of the
     * current conversation step.
     * <p>
     * The result is the <b>union</b> across all actions, deduplicated: a call that
     * matches several actions — in particular a {@value #WILDCARD_ACTION} call,
     * which matches all of them — is executed exactly <b>once per conversation
     * step</b>. Executing it once per action would repeat non-idempotent requests
     * (POSTs) as often as the step happens to carry actions.
     * <p>
     * Order follows the actions of the step and, within one action, the
     * configuration order — the order in which the calls would first have been
     * triggered.
     */
    // Package-private for unit testing.
    static Collection<ApiCall> collectMatchingApiCalls(List<ApiCall> httpCalls, List<String> actions) {
        if (isNullOrEmpty(httpCalls) || isNullOrEmpty(actions)) {
            return List.of();
        }

        // Insertion-ordered and identity-based (ApiCall does not override equals).
        Collection<ApiCall> matchingCalls = new LinkedHashSet<>();
        for (String action : actions) {
            for (ApiCall httpCall : httpCalls) {
                List<String> httpCallActions = httpCall.getActions();
                if (httpCallActions != null && (httpCallActions.contains(action) || httpCallActions.contains(WILDCARD_ACTION))) {
                    matchingCalls.add(httpCall);
                }
            }
        }

        return matchingCalls;
    }

    @Override
    public Object configure(Map<String, Object> configuration, Map<String, Object> extensions) throws WorkflowConfigurationException {

        Object uriObj = configuration.get("uri");
        if (!isNullOrEmpty(uriObj)) {
            URI uri = URI.create(uriObj.toString());

            try {
                ApiCallsConfiguration httpCallsConfig = resourceClientLibrary.getResource(uri, ApiCallsConfiguration.class);

                String targetServerUrl = httpCallsConfig.getTargetServerUrl();
                if (isNullOrEmpty(targetServerUrl)) {
                    String message = format("Property \"targetServerUrl\" in ApiCalls cannot be null or empty! (uri:%s)", uriObj);
                    throw new ServiceException(message);
                }
                if (targetServerUrl.endsWith("/")) {
                    targetServerUrl = targetServerUrl.substring(0, targetServerUrl.length() - 1);
                }
                httpCallsConfig.setTargetServerUrl(targetServerUrl);
                return httpCallsConfig;
            } catch (ServiceException e) {
                LOGGER.error(e.getLocalizedMessage(), e);
                throw new WorkflowConfigurationException(e.getMessage(), e);
            }
        }

        throw new WorkflowConfigurationException("No resource URI has been defined! [ApiCallsConfiguration]");
    }

    @Override
    public ExtensionDescriptor getExtensionDescriptor() {
        ExtensionDescriptor extensionDescriptor = new ExtensionDescriptor(new TaskId(ID));
        extensionDescriptor.setDisplayName("Http Calls");
        ConfigValue configValue = new ConfigValue("Resource URI", FieldType.URI, false, null);
        extensionDescriptor.getConfigs().put("uri", configValue);
        return extensionDescriptor;
    }
}
