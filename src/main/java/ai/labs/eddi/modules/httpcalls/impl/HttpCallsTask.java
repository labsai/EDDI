package ai.labs.eddi.modules.httpcalls.impl;


import ai.labs.eddi.configs.extensions.model.ExtensionDescriptor;
import ai.labs.eddi.configs.extensions.model.ExtensionDescriptor.ConfigValue;
import ai.labs.eddi.configs.extensions.model.ExtensionDescriptor.FieldType;
import ai.labs.eddi.configs.http.model.*;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.LifecycleException;
import ai.labs.eddi.engine.lifecycle.PackageConfigurationException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.runtime.SystemRuntime;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.httpclient.IHttpClient;
import ai.labs.eddi.httpclient.IHttpClient.Method;
import ai.labs.eddi.httpclient.IRequest;
import ai.labs.eddi.httpclient.IResponse;
import ai.labs.eddi.models.Context;
import ai.labs.eddi.models.HttpCodeValidator;
import ai.labs.eddi.models.Property;
import ai.labs.eddi.models.PropertyInstruction;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import com.jayway.jsonpath.JsonPath;
import org.jboss.logging.Logger;

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

import static ai.labs.eddi.utils.MatchingUtilities.executeValuePath;
import static ai.labs.eddi.utils.RuntimeUtilities.checkNotNull;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

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
    private final IDataFactory dataFactory;
    private final ITemplatingEngine templatingEngine;
    private final IMemoryItemConverter memoryItemConverter;
    private final SystemRuntime.IRuntime runtime;
    private String targetServerUri;
    private List<HttpCall> httpCalls;

    @Inject
    Logger log;

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
    public String getType() {
        return KEY_HTTP_CALLS;
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
                        IResponse response = null;
                        boolean retryCall = false;
                        int amountOfExecutions = 0;
                        boolean validationError = false;
                        try {
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
                        } catch (HttpCallsValidationException e) {
                            validationError = true;
                        }

                        runPostResponse(memory, call, templateDataObjects, response.getHttpCode(), validationError);
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
        log.info(call.getName() + String.format(" Execution time: Duration: %sms Delay: %sms Total: %sms\n",
                duration, delayInMillis, duration + delayInMillis));

        return response;
    }

    private Map<String, Object> executePreRequestPropertyInstructions(IConversationMemory memory,
                                                                      Map<String, Object> templateDataObjects,
                                                                      PreRequest preRequest)
            throws ITemplatingEngine.TemplateEngineException {

        if (preRequest != null && preRequest.getPropertyInstructions() != null) {
            var propertyInstructions = preRequest.getPropertyInstructions();
            executePropertyInstructions(propertyInstructions, 0, false, memory, templateDataObjects);
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
                        log.info("Batch Request: " + request);
                    } else {
                        request.send(r -> {
                            //ignore response
                        });
                        log.info("Batch Request (f'n'f): " + request);
                    }
                }
                return null;
            }, null);


        } else {
            IRequest request = buildRequest(call.getRequest(), templateDataObjects);
            request.send(r -> {
                //ignore response
            });
            log.info("Request (f'n'f): " + request);
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
                              int amountOfExecutions, int httpCode, String contentAsString) throws HttpCallsValidationException {

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

    private List<HttpCall> removeDuplicates(List<HttpCall> httpCalls) {
        return httpCalls.stream().distinct().collect(Collectors.toList());
    }

    private void runPostResponse(IConversationMemory memory, HttpCall call, Map<String, Object> templateDataObjects, int httpCode, boolean validationError)
            throws IOException, ITemplatingEngine.TemplateEngineException {

        var postResponse = call.getPostResponse();
        if (postResponse != null) {
            var propertyInstructions = postResponse.getPropertyInstructions();
            executePropertyInstructions(propertyInstructions, httpCode, validationError, memory, templateDataObjects);

            buildOutput(memory, templateDataObjects, httpCode, postResponse);
            buildQuickReplies(memory, templateDataObjects, httpCode, postResponse);
        }


    }

    private void buildOutput(IConversationMemory memory, Map<String, Object> templateDataObjects, int httpCode, PostResponse postResponse)
            throws IOException, ITemplatingEngine.TemplateEngineException {

        var outputBuildInstructions = postResponse.getOutputBuildInstructions();
        if (outputBuildInstructions != null) {
            List<Object> output = new LinkedList<>();
            for (var buildingInstruction : outputBuildInstructions) {
                if (verifyHttpCode(buildingInstruction.getHttpCodeValidator(), httpCode)) {

                    output.addAll(
                            buildOutput(
                                    buildingInstruction.getIterationObjectName(),
                                    buildingInstruction.getPathToTargetArray(),
                                    buildingInstruction.getTemplateFilterExpression(),
                                    buildingInstruction.getOutputType(),
                                    buildingInstruction.getOutputValue(),
                                    templateDataObjects));
                }
            }

            var context = new Context(Context.ContextType.object, output);
            IData<Context> contextData = dataFactory.createData("context:output", context);
            memory.getCurrentStep().storeData(contextData);
        }
    }

    private void buildQuickReplies(IConversationMemory memory, Map<String, Object> templateDataObjects, int httpCode, PostResponse postResponse) throws IOException, ITemplatingEngine.TemplateEngineException {
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

    private void executePropertyInstructions(List<PropertyInstruction> propertyInstructions,
                                             int httpCode, boolean validationError, IConversationMemory memory,
                                             Map<String, Object> templateDataObjects)
            throws ITemplatingEngine.TemplateEngineException {

        if (propertyInstructions != null) {
            for (PropertyInstruction propertyInstruction : propertyInstructions) {
                if ((validationError && propertyInstruction.getRunOnValidationError()) || (httpCode == 0 ||
                        verifyHttpCode(propertyInstruction.getHttpCodeValidator(), httpCode))) {

                    String propertyName = propertyInstruction.getName();
                    checkNotNull(propertyName, "name");
                    propertyName = templateValues(propertyName, templateDataObjects);

                    String path = propertyInstruction.getFromObjectPath();
                    checkNotNull(path, "fromObjectPath");

                    Property.Scope scope = propertyInstruction.getScope();
                    Object propertyValue;
                    if (!isNullOrEmpty(path)) {
                        propertyValue = JsonPath.parse(templateDataObjects).read(path);
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

    private List<Object> buildOutput(String iterationObjectName,
                                     String pathToTargetArray,
                                     String templateFilterExpression,
                                     String outputType,
                                     String outputValue,
                                     Map<String, Object> templateDataObjects)
            throws IOException, ITemplatingEngine.TemplateEngineException {

        final String quickReplyTemplate = "    {" +
                "        \"type\":\"" + outputType + "\"," +
                "        \"valueAlternatives\":[{" +
                "               \"type\":\"" + outputType + "\"," +
                "               \"text\":\"" + outputValue + "\"" +
                "        }]" +
                "    },";

        return buildListFromJson(iterationObjectName,
                pathToTargetArray, templateFilterExpression, quickReplyTemplate, templateDataObjects);
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

    private static class HttpCallsValidationException extends Exception {
    }
}
