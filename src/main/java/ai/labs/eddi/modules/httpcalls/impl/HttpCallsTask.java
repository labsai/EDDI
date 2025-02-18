package ai.labs.eddi.modules.httpcalls.impl;


import ai.labs.eddi.configs.http.model.*;
import ai.labs.eddi.configs.packages.model.ExtensionDescriptor;
import ai.labs.eddi.configs.packages.model.ExtensionDescriptor.ConfigValue;
import ai.labs.eddi.configs.packages.model.ExtensionDescriptor.FieldType;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.httpclient.IHttpClient;
import ai.labs.eddi.engine.httpclient.IHttpClient.Method;
import ai.labs.eddi.engine.httpclient.IRequest;
import ai.labs.eddi.engine.httpclient.IRequest.HttpRequestException;
import ai.labs.eddi.engine.httpclient.IResponse;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.exceptions.PackageConfigurationException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.modules.templating.ITemplatingEngine.TemplateEngineException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static ai.labs.eddi.utils.MatchingUtilities.executeValuePath;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;
import static jakarta.ws.rs.core.MediaType.TEXT_PLAIN;
import static java.lang.String.format;
import static java.lang.System.currentTimeMillis;
import static java.util.Objects.requireNonNullElse;

@ApplicationScoped
public class HttpCallsTask implements ILifecycleTask {
    public static final String ID = "ai.labs.httpcalls";
    private static final String UTF_8 = "utf-8";
    private static final String CONTENT_TYPE_APPLICATION_JSON = "application/json";
    private static final String ACTION_KEY = "actions";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String KEY_HTTP_CALLS = "httpCalls";
    private static final String SLASH_CHAR = "/";
    private final IHttpClient httpClient;
    private final IJsonSerialization jsonSerialization;
    private final IResourceClientLibrary resourceClientLibrary;
    private final IMemoryItemConverter memoryItemConverter;
    private final IRuntime runtime;
    private final PrePostUtils prePostUtils;

    private static final Logger LOGGER = Logger.getLogger(HttpCallsTask.class);

    @Inject
    public HttpCallsTask(IHttpClient httpClient,
                         IJsonSerialization jsonSerialization,
                         IResourceClientLibrary resourceClientLibrary,
                         IMemoryItemConverter memoryItemConverter,
                         IRuntime runtime, PrePostUtils prePostUtils) {
        this.httpClient = httpClient;
        this.jsonSerialization = jsonSerialization;
        this.resourceClientLibrary = resourceClientLibrary;
        this.memoryItemConverter = memoryItemConverter;
        this.runtime = runtime;
        this.prePostUtils = prePostUtils;
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
        IData<List<String>> latestData = currentStep.getLatestData(ACTION_KEY);
        if (latestData == null) {
            return;
        }

        Map<String, Object> templateDataObjects = memoryItemConverter.convert(memory);
        List<String> actions = latestData.getResult();

        for (String action : actions) {
            List<HttpCall> filteredHttpCalls = httpCallsConfig.getHttpCalls().stream().
                    filter(httpCall -> {
                        List<String> httpCallActions = httpCall.getActions();
                        return httpCallActions.contains(action) || httpCallActions.contains("*");
                    }).distinct().toList();

            for (var call : filteredHttpCalls) {
                try {
                    var preRequest = call.getPreRequest();
                    templateDataObjects = prePostUtils.
                            executePreRequestPropertyInstructions(memory, templateDataObjects, preRequest);

                    if (call.getFireAndForget()) {
                        executeFireAndForgetCalls(httpCallsConfig.getTargetServerUrl(), call.getRequest(), preRequest, templateDataObjects, call.getName()
                        );
                    } else {
                        IRequest request;
                        IResponse response = null;
                        boolean retryCall = false;
                        int amountOfExecutions = 0;
                        boolean validationError = false;
                        try {
                            do {
                                request = buildRequest(
                                        httpCallsConfig.getTargetServerUrl(), call.getRequest(), templateDataObjects);
                                var objectName = call.getName() + "Request";
                                prePostUtils.createMemoryEntry(currentStep, request.toMap(), objectName, KEY_HTTP_CALLS);
                                response = executeAndMeasureRequest(call, request, retryCall, amountOfExecutions);

                                var isResponseSuccessful = response.getHttpCode() >= 200 && response.getHttpCode() < 300;
                                if (!isResponseSuccessful) {
                                    String message = "HttpCall (%s) didn't return http code 2xx, instead %s.";
                                    LOGGER.warn(format(message, call.getName(), response.getHttpCode()));
                                    LOGGER.warn("Error Msg:" + response.getHttpCodeMessage());
                                }

                                var responseHeaderObjectName = call.getResponseHeaderObjectName();
                                if (!isNullOrEmpty(responseHeaderObjectName)) {
                                    var responseObjectHeader =
                                            requireNonNullElse(response.getHttpHeader(), new HashMap<>());
                                    templateDataObjects.put(responseHeaderObjectName, responseObjectHeader);
                                    prePostUtils.createMemoryEntry(currentStep, responseObjectHeader,
                                            responseHeaderObjectName, KEY_HTTP_CALLS);
                                }

                                if (isResponseSuccessful && call.getSaveResponse()) {
                                    final String responseBody = response.getContentAsString();
                                    String actualContentType = response.getHttpHeader().get(CONTENT_TYPE);
                                    if (actualContentType != null) {
                                        actualContentType = actualContentType.split(";")[0];
                                    } else {
                                        actualContentType = "<not-present>";
                                    }

                                    Object responseObject;
                                    if (CONTENT_TYPE_APPLICATION_JSON.startsWith(actualContentType)) {
                                        responseObject = jsonSerialization.deserialize(responseBody, Object.class);
                                    } else {
                                        if (!actualContentType.startsWith("<not-present>") &&
                                                !actualContentType.startsWith("text")) {
                                            var message =
                                                    "HttpCall (%s) didn't return application/json, text/plain nor text/html " +
                                                            "as content-type, instead was (%s)";
                                            LOGGER.warn(format(message, call.getName(), actualContentType));
                                        }

                                        responseObject = responseBody;
                                    }

                                    var responseObjectName = call.getResponseObjectName();
                                    templateDataObjects.put(responseObjectName, responseObject);

                                    prePostUtils.createMemoryEntry(currentStep, responseObject, responseObjectName, KEY_HTTP_CALLS);
                                }

                                amountOfExecutions++;
                                retryCall = retryCall(call.getPostResponse(),
                                        templateDataObjects, amountOfExecutions,
                                        response.getHttpCode(), response.getContentAsString());
                            } while (retryCall);
                        } catch (HttpCallsValidationException e) {
                            validationError = true;
                        }

                        prePostUtils.runPostResponse(memory, call.getPostResponse(), templateDataObjects, response.getHttpCode(), validationError);
                    }
                } catch (Exception e) {
                    LOGGER.error(e.getLocalizedMessage(), e);
                    throw new LifecycleException(e.getLocalizedMessage(), e);
                }
            }
        }
    }

    private IResponse executeAndMeasureRequest(HttpCall call, IRequest request, boolean retryCall, int amountOfExecutions)
            throws HttpRequestException, ExecutionException, InterruptedException {

        LOGGER.info(call.getName() + " Request: " +
                (amountOfExecutions > 0 ? amountOfExecutions + ". retry - " : "") + request.toString());
        int delayInMillis = getDelayInMillis(call, retryCall, amountOfExecutions);

        long executionStart = currentTimeMillis();
        IResponse response = executeRequest(request, delayInMillis);
        long executionEnd = currentTimeMillis();
        long duration = executionEnd - executionStart;

        LOGGER.info(call.getName() + " Response: " + response.toString());
        LOGGER.info(call.getName() + format(" Execution time: Duration: %sms Delay: %sms Total: %sms\n",
                duration, delayInMillis, duration + delayInMillis));

        return response;
    }

    private void executeFireAndForgetCalls(String targetServerUrl,
                                           Request callRequest,
                                           HttpPreRequest preRequest,
                                           Map<String, Object> templateDataObjects,
                                           String callName) throws TemplateEngineException, HttpRequestException {

        if (preRequest != null && preRequest.getBatchRequests() != null) {
            BatchRequestBuildingInstruction batchRequest = preRequest.getBatchRequests();
            if (batchRequest.getExecuteCallsSequentially() == null) {
                batchRequest.setExecuteCallsSequentially(false);
            }

            runtime.submitCallable(() -> {
                List<Object> batchIterationList = prePostUtils.buildListFromJson(
                        batchRequest.getIterationObjectName(), batchRequest.getPathToTargetArray(),
                        batchRequest.getTemplateFilterExpression(), null, templateDataObjects);

                IRequest request;
                for (Object iterationObject : batchIterationList) {
                    templateDataObjects.put(batchRequest.getIterationObjectName(), iterationObject);
                    request = buildRequest(targetServerUrl, callRequest, templateDataObjects);
                    if (batchRequest.getExecuteCallsSequentially()) {
                        long executionStart = currentTimeMillis();
                        LOGGER.info(callName + " Batch Request: " + request);
                        IResponse response = request.send();
                        logExecutionResponse(response, callName, executionStart, currentTimeMillis(), false);
                    } else {
                        executeFireAndForgetCall(request, callName);
                    }
                }
                return null;
            }, null);

        } else {
            IRequest request = buildRequest(targetServerUrl, callRequest, templateDataObjects);
            executeFireAndForgetCall(request, callName);
        }
    }

    private static void executeFireAndForgetCall(IRequest request, String httpCallsName)
            throws HttpRequestException {

        LOGGER.info(httpCallsName + " Request (f'n'f): " + request);
        long executionStart = currentTimeMillis();
        request.send(res ->
                logExecutionResponse(res, httpCallsName, executionStart, currentTimeMillis(), true));
    }

    private static void logExecutionResponse(IResponse response, String httpCallsName,
                                             long executionStart, long executionEnd, boolean fireAndForget) {

        long duration = executionEnd - executionStart;
        LOGGER.info(httpCallsName + " Response " + (fireAndForget ? "(f'n'f)" : "") + ": " + response.toString());
        LOGGER.info(httpCallsName + format(" Execution time: %sms\n", duration));
    }

    private static int getDelayInMillis(HttpCall call, boolean retryCall, int amountOfExecutions) {
        int delayInMillis = 0;

        if (retryCall) {
            Integer exponentialBackoffDelay = call.getPostResponse().
                    getRetryHttpCallInstruction().
                    getExponentialBackoffDelayInMillis();
            if (exponentialBackoffDelay != null) {
                delayInMillis = exponentialBackoffDelay * amountOfExecutions;
            }
        }

        if (delayInMillis == 0) {
            var preRequest = call.getPreRequest();
            delayInMillis = preRequest == null ? 0 : preRequest.getDelayBeforeExecutingInMillis();
        }

        return delayInMillis;
    }

    private IResponse executeRequest(IRequest request, int delay)
            throws HttpRequestException, ExecutionException, InterruptedException {

        if (delay > 0) {
            return runtime.submitScheduledCallable(
                    request::send,
                    delay, TimeUnit.MILLISECONDS,
                    Collections.emptyMap()).get();
        } else {
            return request.send();
        }
    }

    private boolean retryCall(HttpPostResponse postResponse,
                              Map<String, Object> conversationValues,
                              int amountOfExecutions, int httpCode, String contentAsString)
            throws HttpCallsValidationException {

        if (isNullOrEmpty(postResponse)) {
            return false;
        }

        var retryHttpCallInstruction = postResponse.getRetryHttpCallInstruction();
        if (isNullOrEmpty(retryHttpCallInstruction)) {
            return false;
        }

        int maxRetries = retryHttpCallInstruction.getMaxRetries();
        if (maxRetries >= 1 && maxRetries >= amountOfExecutions) {

            var retryOnHttpCodes = retryHttpCallInstruction.getRetryOnHttpCodes();
            if (!isNullOrEmpty(retryOnHttpCodes) && retryOnHttpCodes.contains(httpCode)) {
                return true;
            }

            var valuePathMatchers = retryHttpCallInstruction.getResponseValuePathMatchers();
            if (!isNullOrEmpty(contentAsString) && !isNullOrEmpty(valuePathMatchers)) {
                for (var valuePathMatcher : valuePathMatchers) {
                    boolean success = executeValuePath(
                            conversationValues,
                            valuePathMatcher.getValuePath(),
                            valuePathMatcher.getEquals(),
                            valuePathMatcher.getContains());

                    if (valuePathMatcher.getTrueIfNoMatch() != success) {
                        return true;
                    }
                }
            }
        }

        throw new HttpCallsValidationException();
    }

    private IRequest buildRequest(String targetServerUrl, Request requestConfig,
                                  Map<String, Object> templateDataObjects)
            throws TemplateEngineException {

        String path = requestConfig.getPath().trim();
        if (!path.startsWith(SLASH_CHAR) && !path.isEmpty() && !path.startsWith("http")) {
            path = SLASH_CHAR + path;
        }
        var targetDestination = !path.startsWith("http") ? targetServerUrl + path : path;
        var targetUri = URI.create(prePostUtils.templateValues(targetDestination, templateDataObjects));
        var requestBody = prePostUtils.templateValues(requestConfig.getBody(), templateDataObjects);

        var method = Method.valueOf(requestConfig.getMethod().toUpperCase());
        IRequest request = httpClient.newRequest(targetUri, method);
        if (!isNullOrEmpty(requestBody)) {
            String contentType = requestConfig.getContentType();
            request.setBodyEntity(requestBody, UTF_8, !isNullOrEmpty(contentType) ? contentType : TEXT_PLAIN);
        }

        Map<String, String> headers = requestConfig.getHeaders();
        for (String headerName : headers.keySet()) {
            request.setHttpHeader(headerName, prePostUtils.templateValues(headers.get(headerName), templateDataObjects));
        }

        Map<String, String> queryParams = requestConfig.getQueryParams();
        for (String queryParam : queryParams.keySet()) {
            request.setQueryParam(queryParam, prePostUtils.templateValues(queryParams.get(queryParam), templateDataObjects));
        }
        return request;
    }

    @Override
    public Object configure(Map<String, Object> configuration, Map<String, Object> extensions)
            throws PackageConfigurationException {

        Object uriObj = configuration.get("uri");
        if (!isNullOrEmpty(uriObj)) {
            URI uri = URI.create(uriObj.toString());

            try {
                HttpCallsConfiguration httpCallsConfig = resourceClientLibrary.getResource(uri, HttpCallsConfiguration.class);

                String targetServerUrl = httpCallsConfig.getTargetServerUrl();
                if (isNullOrEmpty(targetServerUrl)) {
                    String message = format("Property \"targetServerUrl\" in HttpCalls cannot be null or empty! (uri:%s)", uriObj);
                    throw new ServiceException(message);
                }
                if (targetServerUrl.endsWith(SLASH_CHAR)) {
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

    private static class HttpCallsValidationException extends Exception {
    }
}
