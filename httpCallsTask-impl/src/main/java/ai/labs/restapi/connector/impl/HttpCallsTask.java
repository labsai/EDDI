package ai.labs.restapi.connector.impl;

import ai.labs.httpclient.IHttpClient;
import ai.labs.httpclient.IHttpClient.Method;
import ai.labs.httpclient.IRequest;
import ai.labs.httpclient.IResponse;
import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.lifecycle.LifecycleException;
import ai.labs.lifecycle.PackageConfigurationException;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;
import ai.labs.memory.IDataFactory;
import ai.labs.memory.IMemoryItemConverter;
import ai.labs.models.Context;
import ai.labs.models.HttpCodeValidator;
import ai.labs.models.Property;
import ai.labs.models.PropertyInstruction;
import ai.labs.resources.rest.config.http.model.BatchRequestBuildingInstruction;
import ai.labs.resources.rest.config.http.model.HttpCall;
import ai.labs.resources.rest.config.http.model.HttpCallsConfiguration;
import ai.labs.resources.rest.config.http.model.PostResponse;
import ai.labs.resources.rest.config.http.model.PreRequest;
import ai.labs.resources.rest.config.http.model.QuickRepliesBuildingInstruction;
import ai.labs.resources.rest.config.http.model.Request;
import ai.labs.resources.rest.extensions.model.ExtensionDescriptor;
import ai.labs.resources.rest.extensions.model.ExtensionDescriptor.ConfigValue;
import ai.labs.resources.rest.extensions.model.ExtensionDescriptor.FieldType;
import ai.labs.runtime.SystemRuntime;
import ai.labs.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.runtime.service.ServiceException;
import ai.labs.serialization.IJsonSerialization;
import ai.labs.templateengine.ITemplatingEngine;
import lombok.extern.slf4j.Slf4j;
import ognl.Ognl;
import ognl.OgnlException;

import javax.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static ai.labs.utilities.MatchingUtilities.executeValuePath;
import static ai.labs.utilities.RuntimeUtilities.checkNotNull;
import static ai.labs.utilities.RuntimeUtilities.isNullOrEmpty;

@Slf4j
public class HttpCallsTask implements ILifecycleTask {
    private static final String ID = "ai.labs.httpcalls";
    private static final String UTF_8 = "utf-8";
    private static final String CONTENT_TYPE_APPLICATION_JSON = "application/json";
    private static final String ACTION_KEY = "actions";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String KEY_HTTP_CALLS = "httpCalls";
    private static final String SLASH_CHAR = "/";
    private final IHttpClient httpClient;
    private final IJsonSerialization jsonSerialization;
    private final IResourceClientLibrary resourceClientLibrary;
    private final IDataFactory dataFactory;
    private final ITemplatingEngine templatingEngine;
    private final IMemoryItemConverter memoryItemConverter;
    private final SystemRuntime.IRuntime runtime;
    private String targetServerUri;
    private List<HttpCall> httpCalls;

    @Inject
    public HttpCallsTask(IHttpClient httpClient, IJsonSerialization jsonSerialization,
                         IResourceClientLibrary resourceClientLibrary, IDataFactory dataFactory,
                         ITemplatingEngine templatingEngine, IMemoryItemConverter memoryItemConverter,
                         SystemRuntime.IRuntime runtime) {
        this.httpClient = httpClient;
        this.jsonSerialization = jsonSerialization;
        this.resourceClientLibrary = resourceClientLibrary;
        this.dataFactory = dataFactory;
        this.templatingEngine = templatingEngine;
        this.memoryItemConverter = memoryItemConverter;
        this.runtime = runtime;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public Object getComponent() {
        return null;
    }

    @Override
    public void executeTask(IConversationMemory memory) throws LifecycleException {
        IConversationMemory.IWritableConversationStep currentStep = memory.getCurrentStep();
        IData<List<String>> latestData = currentStep.getLatestData(ACTION_KEY);
        if (latestData == null) {
            return;
        }

        Map<String, Object> templateDataObjects = memoryItemConverter.convert(memory);
        List<String> actions = latestData.getResult();

        for (String action : actions) {
            List<HttpCall> httpCalls = this.httpCalls.stream().
                    filter(httpCall -> {
                        List<String> httpCallActions = httpCall.getActions();
                        return httpCallActions.contains(action) || httpCallActions.contains("*");
                    }).collect(Collectors.toList());

            httpCalls = removeDuplicates(httpCalls);

            for (HttpCall call : httpCalls) {
                try {
                    PreRequest preRequest = call.getPreRequest();
                    templateDataObjects = executePreRequestPropertyInstructions(memory, templateDataObjects, preRequest);

                    if (call.getFireAndForget()) {
                        executeFireAndForgetCalls(call, preRequest, templateDataObjects);
                    } else {
                        IRequest request;
                        IResponse response;
                        boolean retryCall = false;
                        int amountOfExecutions = 0;
                        do {
                            request = buildRequest(call.getRequest(), templateDataObjects);
                            response = executeAndMeasureRequest(call, request, retryCall, amountOfExecutions);

                            if (response.getHttpCode() != 200) {
                                String message = "HttpCall (%s) didn't return http code 200, instead %s.";
                                log.warn(String.format(message, call.getName(), response.getHttpCode()));
                                log.warn("Error Msg:" + response.getHttpCodeMessage());
                            }

                            if (response.getHttpCode() == 200 && call.getSaveResponse()) {
                                final String responseBody = response.getContentAsString();
                                String actualContentType = response.getHttpHeader().get(CONTENT_TYPE);
                                if (actualContentType != null) {
                                    actualContentType = actualContentType.split(";")[0];
                                } else {
                                    actualContentType = "<not-present>";
                                }

                                if (!CONTENT_TYPE_APPLICATION_JSON.startsWith(actualContentType)) {
                                    String message = "HttpCall (%s) didn't return application/json as content-type, instead was (%s)";
                                    log.warn(String.format(message, call.getName(), actualContentType));
                                }

                                Object responseObject = jsonSerialization.deserialize(responseBody, Object.class);
                                String responseObjectName = call.getResponseObjectName();
                                templateDataObjects.put(responseObjectName, responseObject);

                                String memoryDataName = "httpCalls:" + responseObjectName;
                                IData<Object> httpResponseData = dataFactory.createData(memoryDataName, responseObject);
                                currentStep.storeData(httpResponseData);
                                currentStep.addConversationOutputMap(KEY_HTTP_CALLS, Map.of(responseObjectName, responseObject));
                            }

                            amountOfExecutions++;
                            retryCall = retryCall(call.getPostResponse(),
                                    templateDataObjects, amountOfExecutions,
                                    response.getHttpCode(), response.getContentAsString());
                        } while (retryCall);

                        runPostResponse(memory, call, templateDataObjects, response.getHttpCode());
                    }
                } catch (Exception e) {
                    log.error(e.getLocalizedMessage(), e);
                    throw new LifecycleException(e.getLocalizedMessage(), e);
                }
            }
        }
    }

    private IResponse executeAndMeasureRequest(HttpCall call, IRequest request, boolean retryCall, int amountOfExecutions)
            throws IRequest.HttpRequestException, ExecutionException, InterruptedException {

        log.info(call.getName() + " Request:  " + (amountOfExecutions > 0 ? amountOfExecutions + ". retry - " : "") + request.toString());
        int delayInMillis = getDelayInMillis(call, retryCall, amountOfExecutions);

        long executionStart = System.currentTimeMillis();
        IResponse response = executeRequest(request, delayInMillis);
        long executionEnd = System.currentTimeMillis();
        long duration = executionEnd - executionStart;

        log.info(call.getName() + " Response: " + response.toString());
        log.info(call.getName() + " Execution time: Duration: {}ms Delay: {}ms Total: {}ms\n",
                duration, delayInMillis, duration + delayInMillis);

        return response;
    }

    private Map<String, Object> executePreRequestPropertyInstructions(IConversationMemory memory,
                                                                      Map<String, Object> templateDataObjects,
                                                                      PreRequest preRequest)
            throws ITemplatingEngine.TemplateEngineException {

        if (preRequest != null && preRequest.getPropertyInstructions() != null) {
            var propertyInstructions = preRequest.getPropertyInstructions();
            executePropertyInstructions(propertyInstructions, 0, memory, templateDataObjects);
            templateDataObjects = memoryItemConverter.convert(memory);
        }
        return templateDataObjects;
    }

    private void executeFireAndForgetCalls(HttpCall call, PreRequest preRequest, Map<String, Object> templateDataObjects)
            throws ITemplatingEngine.TemplateEngineException, IRequest.HttpRequestException {

        if (preRequest != null && preRequest.getBatchRequests() != null) {
            BatchRequestBuildingInstruction batchRequest = preRequest.getBatchRequests();
            if (batchRequest.getExecuteCallsSequentially() == null) {
                batchRequest.setExecuteCallsSequentially(false);
            }

            runtime.submitCallable(() -> {
                List<Object> batchIterationList = buildListFromJson(
                        batchRequest.getIterationObjectName(), batchRequest.getPathToTargetArray(),
                        batchRequest.getTemplateFilterExpression(), null, templateDataObjects);

                IRequest request;
                for (Object iterationObject : batchIterationList) {
                    templateDataObjects.put(batchRequest.getIterationObjectName(), iterationObject);
                    request = buildRequest(call.getRequest(), templateDataObjects);
                    if (batchRequest.getExecuteCallsSequentially()) {
                        request.send();
                        log.info("Batch Request: " + request.toString());
                    } else {
                        request.send(r -> {
                            //ignore response
                        });
                        log.info("Batch Request (f'n'f): " + request.toString());
                    }
                }
                return null;
            }, null);


        } else {
            IRequest request = buildRequest(call.getRequest(), templateDataObjects);
            request.send(r -> {
                //ignore response
            });
            log.info("Request (f'n'f): " + request.toString());
        }
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
            throws IRequest.HttpRequestException, ExecutionException, InterruptedException {

        if (delay > 0) {
            return runtime.submitScheduledCallable(
                    request::send,
                    delay, TimeUnit.MILLISECONDS,
                    Collections.emptyMap()).get();
        } else {
            return request.send();
        }
    }

    private boolean retryCall(PostResponse postResponse,
                              Map<String, Object> conversationValues,
                              int amountOfExecutions, int httpCode, String contentAsString) {

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

        return false;
    }

    private List<HttpCall> removeDuplicates(List<HttpCall> httpCalls) {
        return httpCalls.stream().distinct().collect(Collectors.toList());
    }

    private void runPostResponse(IConversationMemory memory, HttpCall call, Map<String, Object> templateDataObjects, int httpCode)
            throws IOException, ITemplatingEngine.TemplateEngineException {

        var postResponse = call.getPostResponse();
        if (postResponse != null) {
            var propertyInstructions = postResponse.getPropertyInstructions();
            executePropertyInstructions(propertyInstructions, httpCode, memory, templateDataObjects);

            var qrBuildInstructions = postResponse.getQrBuildInstructions();
            if (qrBuildInstructions != null) {
                List<Object> quickReplies = new LinkedList<>();
                for (QuickRepliesBuildingInstruction qrBuildInstruction : qrBuildInstructions) {
                    if (verifyHttpCode(qrBuildInstruction.getHttpCodeValidator(), httpCode)) {

                        quickReplies.addAll(
                                buildQuickReplies(
                                        qrBuildInstruction.getIterationObjectName(),
                                        qrBuildInstruction.getPathToTargetArray(),
                                        qrBuildInstruction.getTemplateFilterExpression(),
                                        qrBuildInstruction.getQuickReplyValue(),
                                        qrBuildInstruction.getQuickReplyExpressions(),
                                        templateDataObjects));
                    }
                }

                var context = new Context(Context.ContextType.object, quickReplies);
                IData<Context> contextData = dataFactory.createData("context:quickReplies", context);
                memory.getCurrentStep().storeData(contextData);
            }
        }


    }

    private void executePropertyInstructions(List<PropertyInstruction> propertyInstructions,
                                             int httpCode, IConversationMemory memory,
                                             Map<String, Object> templateDataObjects)
            throws ITemplatingEngine.TemplateEngineException {

        if (propertyInstructions != null) {
            for (PropertyInstruction propertyInstruction : propertyInstructions) {
                if (httpCode == 0 || verifyHttpCode(propertyInstruction.getHttpCodeValidator(), httpCode)) {

                    String propertyName = propertyInstruction.getName();
                    checkNotNull(propertyName, "name");
                    propertyName = templateValues(propertyName, templateDataObjects);

                    String path = propertyInstruction.getFromObjectPath();
                    checkNotNull(path, "fromObjectPath");

                    Property.Scope scope = propertyInstruction.getScope();
                    Object propertyValue;
                    if (!isNullOrEmpty(path)) {
                        try {
                            propertyValue = Ognl.getValue(path, templateDataObjects);
                        } catch (OgnlException oglnException) {
                            log.error("configured path is not correct or value does not exist!", oglnException);
                            propertyValue = null;
                        }
                    } else {
                        Object value = propertyInstruction.getValue();

                        if (!isNullOrEmpty(value) && value instanceof String) {
                            value = templateValues((String) value, templateDataObjects);
                        }

                        propertyValue = value;
                    }
                    memory.getConversationProperties().put(propertyName,
                            new Property(propertyName, propertyValue, scope));
                    templateDataObjects.put("properties", memory.getConversationProperties().toMap());
                }
            }
        }
    }

    private boolean verifyHttpCode(HttpCodeValidator httpCodeValidator, int httpCode) {
        if (httpCodeValidator == null) {
            httpCodeValidator = HttpCodeValidator.DEFAULT;
        } else {
            if (httpCodeValidator.getRunOnHttpCode() == null) {
                httpCodeValidator.setRunOnHttpCode(HttpCodeValidator.DEFAULT.getRunOnHttpCode());
            }
            if (httpCodeValidator.getSkipOnHttpCode() == null) {
                httpCodeValidator.setSkipOnHttpCode(HttpCodeValidator.DEFAULT.getSkipOnHttpCode());
            }
        }

        return httpCodeValidator.getRunOnHttpCode().contains(httpCode) &&
                !httpCodeValidator.getSkipOnHttpCode().contains(httpCode);
    }

    private IRequest buildRequest(Request requestConfig, Map<String, Object> templateDataObjects) throws ITemplatingEngine.TemplateEngineException {
        String path = requestConfig.getPath().trim();
        if (!path.startsWith(SLASH_CHAR) && !path.isEmpty() && !path.startsWith("http")) {
            path = SLASH_CHAR + path;
        }
        URI targetUri = URI.create(templateValues(targetServerUri + path, templateDataObjects));
        String requestBody = templateValues(requestConfig.getBody(), templateDataObjects);

        var method = Method.valueOf(requestConfig.getMethod().toUpperCase());
        IRequest request = httpClient.newRequest(targetUri, method).
                setBodyEntity(requestBody, UTF_8, requestConfig.getContentType());

        Map<String, String> headers = requestConfig.getHeaders();
        for (String headerName : headers.keySet()) {
            request.setHttpHeader(headerName, templateValues(headers.get(headerName), templateDataObjects));
        }

        Map<String, String> queryParams = requestConfig.getQueryParams();
        for (String queryParam : queryParams.keySet()) {
            request.setQueryParam(queryParam, templateValues(queryParams.get(queryParam), templateDataObjects));
        }
        return request;
    }

    private String templateValues(String toBeTemplated, Map<String, Object> properties)
            throws ITemplatingEngine.TemplateEngineException {

        return templatingEngine.processTemplate(toBeTemplated, properties);
    }

    private List<Object> buildQuickReplies(String iterationObjectName,
                                           String pathToTargetArray,
                                           String templateFilterExpression,
                                           String quickReplyValue,
                                           String quickReplyExpressions,
                                           Map<String, Object> templateDataObjects)
            throws IOException, ITemplatingEngine.TemplateEngineException {

        final String quickReplyTemplate = "    {" +
                "        \"value\":\"" + quickReplyValue + "\"," +
                "        \"expressions\":\"" + quickReplyExpressions + "\"" +
                "    },";

        return buildListFromJson(iterationObjectName,
                pathToTargetArray, templateFilterExpression, quickReplyTemplate, templateDataObjects);
    }


    private List<Object> buildListFromJson(String iterationObjectName,
                                           String pathToTargetArray,
                                           String templateFilterExpression,
                                           String iterationValue,
                                           Map<String, Object> templateDataObjects)
            throws ITemplatingEngine.TemplateEngineException, IOException {

        String templateCode = "[" +
                "[# th:each=\"" + iterationObjectName + " : ${" + pathToTargetArray + "}\"";

        if (!isNullOrEmpty(templateFilterExpression)) {
            templateCode += "   th:object=\"${" + iterationObjectName + "}\"";
            templateCode += "   th:if=\"" + templateFilterExpression + "\"";
        }

        templateCode += "]" +
                (isNullOrEmpty(iterationValue) ? "\"[[${" + iterationObjectName + "}]]\"," : iterationValue) +
                "[/]" +
                "]";

        String jsonList = templatingEngine.processTemplate(templateCode, templateDataObjects);

        //remove last comma of iterated array
        if (jsonList.contains(",")) {
            jsonList = new StringBuilder(jsonList).
                    deleteCharAt(jsonList.lastIndexOf(",")).toString();
        }

        return jsonSerialization.deserialize(jsonList, List.class);
    }

    @Override
    public void configure(Map<String, Object> configuration) throws PackageConfigurationException {
        Object uriObj = configuration.get("uri");
        if (!isNullOrEmpty(uriObj)) {
            URI uri = URI.create(uriObj.toString());

            try {
                HttpCallsConfiguration httpCallsConfig = resourceClientLibrary.getResource(uri, HttpCallsConfiguration.class);

                String targetServerUri = httpCallsConfig.getTargetServerUrl();
                if (targetServerUri.endsWith(SLASH_CHAR)) {
                    targetServerUri = targetServerUri.substring(0, targetServerUri.length() - 2);
                }
                this.targetServerUri = targetServerUri;
                this.httpCalls = httpCallsConfig.getHttpCalls();

            } catch (ServiceException e) {
                log.error(e.getLocalizedMessage(), e);
                throw new PackageConfigurationException(e.getMessage(), e);
            }
        } else {
            this.httpCalls = new LinkedList<>();
        }
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
