/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import ai.labs.eddi.configs.apicalls.model.*;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.httpclient.IHttpClient;
import ai.labs.eddi.engine.httpclient.IRequest;
import ai.labs.eddi.engine.httpclient.IResponse;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import ai.labs.eddi.secrets.SecretResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static ai.labs.eddi.utils.MatchingUtilities.executeValuePath;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;
import static jakarta.ws.rs.core.MediaType.TEXT_PLAIN;
import static java.lang.String.format;
import static java.lang.System.currentTimeMillis;
import static java.util.Objects.requireNonNullElse;

/**
 * Reusable HTTP call executor that can be used by different lifecycle tasks.
 * Extracted from ApiCallsTask to enable reuse in AI agent tools.
 */
@ApplicationScoped
public class ApiCallExecutor implements IApiCallExecutor {
    private static final String UTF_8 = "utf-8";
    private static final String CONTENT_TYPE_APPLICATION_JSON = "application/json";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String KEY_HTTP_CALLS = "httpCalls";
    private static final String SLASH_CHAR = "/";

    private static final Logger LOGGER = Logger.getLogger(ApiCallExecutor.class);

    private final IHttpClient httpClient;
    private final IJsonSerialization jsonSerialization;
    private final IRuntime runtime;
    private final PrePostUtils prePostUtils;
    private final SecretResolver secretResolver;

    @Inject
    public ApiCallExecutor(IHttpClient httpClient, IJsonSerialization jsonSerialization, IRuntime runtime, PrePostUtils prePostUtils,
            SecretResolver secretResolver) {
        this.httpClient = httpClient;
        this.jsonSerialization = jsonSerialization;
        this.runtime = runtime;
        this.prePostUtils = prePostUtils;
        this.secretResolver = secretResolver;
    }

    @Override
    public Map<String, Object> execute(ApiCall call, IConversationMemory memory, Map<String, Object> templateDataObjects, String targetServerUrl)
            throws LifecycleException {
        if (call == null) {
            throw new IllegalArgumentException("call cannot be null");
        }
        if (memory == null) {
            throw new IllegalArgumentException("memory cannot be null");
        }
        if (templateDataObjects == null) {
            throw new IllegalArgumentException("templateDataObjects cannot be null");
        }
        if (targetServerUrl == null || targetServerUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("targetServerUrl cannot be null or empty");
        }

        try {
            IWritableConversationStep currentStep = memory.getCurrentStep();

            var preRequest = call.getPreRequest();
            templateDataObjects = prePostUtils.executePreRequestPropertyInstructions(memory, templateDataObjects, preRequest);

            if (call.getFireAndForget()) {
                executeFireAndForgetCalls(targetServerUrl, call.getRequest(), preRequest, templateDataObjects, call.getName());
                return Collections.emptyMap();
            } else {
                IRequest request;
                IResponse response = null;
                boolean retryCall = false;
                int amountOfExecutions = 0;
                boolean validationError = false;
                Map<String, Object> result = new HashMap<>();

                try {
                    do {
                        request = buildRequest(targetServerUrl, call.getRequest(), templateDataObjects);
                        var objectName = call.getName() + "Request";
                        var requestMap = request.toMap();
                        // Scrub resolved secrets from request map before persisting to conversation
                        // memory.
                        // The actual request (with secrets) was already built — this only affects the
                        // debug record.
                        scrubSensitiveHeaders(requestMap);
                        prePostUtils.createMemoryEntry(currentStep, requestMap, objectName, KEY_HTTP_CALLS);
                        response = executeAndMeasureRequest(call, request, retryCall, amountOfExecutions);

                        var isResponseSuccessful = response.getHttpCode() >= 200 && response.getHttpCode() < 300;
                        if (!isResponseSuccessful) {
                            String message = "ApiCall (%s) didn't return http code 2xx, instead %s.";
                            LOGGER.warn(format(message, call.getName(), response.getHttpCode()));
                            LOGGER.warn("Error Msg:" + response.getHttpCodeMessage());
                        }

                        var responseHeaderObjectName = call.getResponseHeaderObjectName();
                        if (!isNullOrEmpty(responseHeaderObjectName)) {
                            var responseObjectHeader = requireNonNullElse(response.getHttpHeader(), new HashMap<>());
                            templateDataObjects.put(responseHeaderObjectName, responseObjectHeader);
                            prePostUtils.createMemoryEntry(currentStep, responseObjectHeader, responseHeaderObjectName, KEY_HTTP_CALLS);
                            result.put("headers", responseObjectHeader);
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
                            if (CONTENT_TYPE_APPLICATION_JSON.equals(actualContentType)) {
                                responseObject = jsonSerialization.deserialize(responseBody, Object.class);
                            } else {
                                if (!actualContentType.startsWith("<not-present>") && !actualContentType.startsWith("text")) {
                                    var message = "ApiCall (%s) didn't return application/json, text/plain nor text/html "
                                            + "as content-type, instead was (%s)";
                                    LOGGER.warn(format(message, call.getName(), actualContentType));
                                }
                                responseObject = responseBody;
                            }

                            var responseObjectName = call.getResponseObjectName();
                            templateDataObjects.put(responseObjectName, responseObject);
                            prePostUtils.createMemoryEntry(currentStep, responseObject, responseObjectName, KEY_HTTP_CALLS);
                            result.put("body", responseObject);
                            result.put("httpCode", response.getHttpCode());
                        }

                        amountOfExecutions++;
                        retryCall = retryCall(call.getPostResponse(), templateDataObjects, amountOfExecutions, response.getHttpCode(),
                                response.getContentAsString());
                    } while (retryCall);
                } catch (ApiCallsValidationException e) {
                    validationError = true;
                }

                prePostUtils.runPostResponse(memory, call.getPostResponse(), templateDataObjects, response != null ? response.getHttpCode() : 500,
                        validationError);

                return result;
            }
        } catch (Exception e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new LifecycleException(e.getLocalizedMessage(), e);
        }
    }

    private IResponse executeAndMeasureRequest(ApiCall call, IRequest request, boolean retryCall, int amountOfExecutions)
            throws IRequest.HttpRequestException, ExecutionException, InterruptedException {

        LOGGER.info(call.getName() + " Request: " + (amountOfExecutions > 0 ? amountOfExecutions + ". retry - " : "") + request.toString());
        int delayInMillis = getDelayInMillis(call, retryCall, amountOfExecutions);

        long executionStart = currentTimeMillis();
        IResponse response = executeRequest(request, delayInMillis);
        long executionEnd = currentTimeMillis();
        long duration = executionEnd - executionStart;

        LOGGER.info(call.getName() + " Response: " + response.toString());
        LOGGER.info(call.getName()
                + format(" Execution time: Duration: %sms Delay: %sms Total: %sms\n", duration, delayInMillis, duration + delayInMillis));

        return response;
    }

    private void executeFireAndForgetCalls(String targetServerUrl, Request callRequest, HttpPreRequest preRequest,
                                           Map<String, Object> templateDataObjects, String callName)
            throws ITemplatingEngine.TemplateEngineException, IRequest.HttpRequestException {

        if (preRequest != null && preRequest.getBatchRequests() != null) {
            BatchRequestBuildingInstruction batchRequest = preRequest.getBatchRequests();
            if (batchRequest.getExecuteCallsSequentially() == null) {
                batchRequest.setExecuteCallsSequentially(false);
            }

            runtime.submitCallable(() -> {
                List<Object> batchIterationList = prePostUtils.buildListFromJson(batchRequest.getIterationObjectName(),
                        batchRequest.getPathToTargetArray(), batchRequest.getTemplateFilterExpression(), null, templateDataObjects);

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

    private static void executeFireAndForgetCall(IRequest request, String httpCallsName) throws IRequest.HttpRequestException {

        LOGGER.info(httpCallsName + " Request (f'n'f): " + request);
        long executionStart = currentTimeMillis();
        request.send(res -> logExecutionResponse(res, httpCallsName, executionStart, currentTimeMillis(), true));
    }

    private static void logExecutionResponse(IResponse response, String httpCallsName, long executionStart, long executionEnd,
                                             boolean fireAndForget) {

        long duration = executionEnd - executionStart;
        LOGGER.info(httpCallsName + " Response " + (fireAndForget ? "(f'n'f)" : "") + ": " + response.toString());
        LOGGER.info(httpCallsName + format(" Execution time: %sms\n", duration));
    }

    private static int getDelayInMillis(ApiCall call, boolean retryCall, int amountOfExecutions) {
        int delayInMillis = 0;

        if (retryCall) {
            Integer exponentialBackoffDelay = call.getPostResponse().getRetryApiCallInstruction().getExponentialBackoffDelayInMillis();
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

    private IResponse executeRequest(IRequest request, int delay) throws IRequest.HttpRequestException, ExecutionException, InterruptedException {

        if (delay > 0) {
            return runtime.getScheduledExecutorService().schedule((Callable<IResponse>) request::send, delay, TimeUnit.MILLISECONDS).get();
        } else {
            return request.send();
        }
    }

    private boolean retryCall(HttpPostResponse postResponse, Map<String, Object> conversationValues, int amountOfExecutions, int httpCode,
                              String contentAsString)
            throws ApiCallsValidationException {

        if (isNullOrEmpty(postResponse)) {
            return false;
        }

        var retryApiCallInstruction = postResponse.getRetryApiCallInstruction();
        if (isNullOrEmpty(retryApiCallInstruction)) {
            return false;
        }

        int maxRetries = retryApiCallInstruction.getMaxRetries();
        if (maxRetries >= 1 && maxRetries >= amountOfExecutions) {

            var retryOnHttpCodes = retryApiCallInstruction.getRetryOnHttpCodes();
            if (!isNullOrEmpty(retryOnHttpCodes) && retryOnHttpCodes.contains(httpCode)) {
                return true;
            }

            var valuePathMatchers = retryApiCallInstruction.getResponseValuePathMatchers();
            if (!isNullOrEmpty(contentAsString) && !isNullOrEmpty(valuePathMatchers)) {
                for (var valuePathMatcher : valuePathMatchers) {
                    boolean success = executeValuePath(conversationValues, valuePathMatcher.getValuePath(), valuePathMatcher.getEquals(),
                            valuePathMatcher.getContains());

                    if (valuePathMatcher.getTrueIfNoMatch() != success) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private IRequest buildRequest(String targetServerUrl, Request requestConfig, Map<String, Object> templateDataObjects)
            throws ITemplatingEngine.TemplateEngineException {

        String path = requestConfig.getPath().trim();
        if (!path.startsWith(SLASH_CHAR) && !path.isEmpty() && !path.startsWith("http")) {
            path = SLASH_CHAR + path;
        }
        var targetDestination = !path.startsWith("http") ? targetServerUrl + path : path;
        var targetUriStr = prePostUtils.templateValues(targetDestination, templateDataObjects);
        // Resolve vault references in URL (e.g., target server with embedded
        // credentials)
        targetUriStr = secretResolver.resolveValue(targetUriStr);
        var targetUri = URI.create(targetUriStr);
        var requestBody = prePostUtils.templateValues(requestConfig.getBody(), templateDataObjects);
        // Resolve vault references in request body
        requestBody = secretResolver.resolveValue(requestBody);

        var method = IHttpClient.Method.valueOf(requestConfig.getMethod().toUpperCase());
        IRequest request = httpClient.newRequest(targetUri, method);
        if (!isNullOrEmpty(requestBody)) {
            String contentType = requestConfig.getContentType();
            request.setBodyEntity(requestBody, UTF_8, !isNullOrEmpty(contentType) ? contentType : TEXT_PLAIN);
        }

        Map<String, String> headers = requestConfig.getHeaders();
        for (String headerName : headers.keySet()) {
            String headerValue = prePostUtils.templateValues(headers.get(headerName), templateDataObjects);
            // Resolve vault references in headers (e.g., Authorization: Bearer
            // ${eddivault:...})
            headerValue = secretResolver.resolveValue(headerValue);
            request.setHttpHeader(headerName, headerValue);
        }

        Map<String, String> queryParams = requestConfig.getQueryParams();
        for (String queryParam : queryParams.keySet()) {
            var qpValue = prePostUtils.templateValues(queryParams.get(queryParam), templateDataObjects);
            // Resolve vault references in query params
            qpValue = secretResolver.resolveValue(qpValue);
            request.setQueryParam(queryParam, qpValue);
        }
        return request;
    }

    /**
     * Scrub sensitive header values from the request map before it is stored in
     * conversation memory. This prevents resolved secrets (API keys, bearer tokens)
     * from being persisted to the database.
     */
    @SuppressWarnings("unchecked")
    private static void scrubSensitiveHeaders(Map<String, Object> requestMap) {
        Object headersObj = requestMap.get("headers");
        if (headersObj instanceof Map) {
            var headers = (Map<String, Object>) headersObj;
            var scrubbed = new HashMap<>(headers);
            for (var entry : scrubbed.entrySet()) {
                String headerName = entry.getKey().toLowerCase();
                if (headerName.contains("authorization") || headerName.contains("api-key") || headerName.contains("api_key")
                        || headerName.contains("apikey") || headerName.contains("x-api-key") || headerName.contains("token")
                        || headerName.contains("secret") || headerName.contains("credential")) {
                    entry.setValue("<REDACTED>");
                } else if (entry.getValue() instanceof String val && val.contains("${eddivault:")) {
                    entry.setValue("<REDACTED>");
                }
            }
            requestMap.put("headers", scrubbed);
        }
    }

    private static class ApiCallsValidationException extends Exception {
    }
}
