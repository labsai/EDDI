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
import ai.labs.resources.rest.extensions.model.ExtensionDescriptor;
import ai.labs.resources.rest.extensions.model.ExtensionDescriptor.ConfigValue;
import ai.labs.resources.rest.extensions.model.ExtensionDescriptor.FieldType;
import ai.labs.resources.rest.http.model.*;
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
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
    private String targetServerUri;
    private List<HttpCall> httpCalls;

    @Inject
    public HttpCallsTask(IHttpClient httpClient, IJsonSerialization jsonSerialization,
                         IResourceClientLibrary resourceClientLibrary, IDataFactory dataFactory,
                         ITemplatingEngine templatingEngine, IMemoryItemConverter memoryItemConverter) {
        this.httpClient = httpClient;
        this.jsonSerialization = jsonSerialization;
        this.resourceClientLibrary = resourceClientLibrary;
        this.dataFactory = dataFactory;
        this.templatingEngine = templatingEngine;
        this.memoryItemConverter = memoryItemConverter;
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
                    IRequest request;
                    if (call.isFireAndForget()) {
                        var preRequest = call.getPreRequest();
                        if (preRequest != null && preRequest.getBatchRequests() != null) {
                            BuildingInstruction batchRequest = preRequest.getBatchRequests();

                            List<Object> batchIterationList = buildListFromJson(
                                    batchRequest.getIterationObjectName(), batchRequest.getPathToTargetArray(),
                                    batchRequest.getTemplateFilterExpression(), null, templateDataObjects);

                            for (Object iterationObject : batchIterationList) {
                                templateDataObjects.put(batchRequest.getIterationObjectName(), iterationObject);
                                request = buildRequest(call.getRequest(), templateDataObjects);
                                request.send(r -> {
                                    //ignore response
                                });
                                log.info("Request: " + request.toString());
                            }
                        } else {
                            request = buildRequest(call.getRequest(), templateDataObjects);
                            request.send(r -> {
                                //ignore response
                            });
                            log.info("Request: " + request.toString());
                        }
                    } else {
                        request = buildRequest(call.getRequest(), templateDataObjects);
                        IResponse response = request.send();
                        log.info("Request: " + request.toString());

                        if (response.getHttpCode() != 200) {
                            String message = "HttpCall (%s) didn't return http code 200, instead %s.";
                            log.warn(String.format(message, call.getName(), response.getHttpCode()));
                            log.warn("Error Msg:" + response.getHttpCodeMessage());
                        }

                        if (response.getHttpCode() == 200 && call.isSaveResponse()) {
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
                                continue;
                            }

                            Object responseObject = jsonSerialization.deserialize(responseBody, Object.class);
                            String responseObjectName = call.getResponseObjectName();
                            templateDataObjects.put(responseObjectName, responseObject);

                            String memoryDataName = "httpCalls:" + responseObjectName;
                            IData<Object> httpResponseData = dataFactory.createData(memoryDataName, responseObject);
                            currentStep.storeData(httpResponseData);
                            currentStep.addConversationOutputMap(KEY_HTTP_CALLS, Map.of(responseObjectName, responseObject));
                        }

                        runPostResponse(memory, call, templateDataObjects, response.getHttpCode());
                    }
                } catch (IRequest.HttpRequestException |
                        ITemplatingEngine.TemplateEngineException |
                        IOException |
                        OgnlException e) {
                    log.error(e.getLocalizedMessage(), e);
                    throw new LifecycleException(e.getLocalizedMessage(), e);
                }
            }
        }
    }

    private LinkedList<HttpCall> removeDuplicates(List<HttpCall> httpCalls) {
        return new LinkedList<>(new HashSet<>(httpCalls));
    }

    private void runPostResponse(IConversationMemory memory, HttpCall call, Map<String, Object> templateDataObjects, int httpCode)
            throws IOException, ITemplatingEngine.TemplateEngineException, OgnlException {

        var postResponse = call.getPostResponse();
        if (postResponse != null) {
            var propertyInstructions = postResponse.getPropertyInstructions();
            if (propertyInstructions != null) {
                for (PropertyInstruction propertyInstruction : propertyInstructions) {
                    if (verifyHttpCode(propertyInstruction.getHttpCodeValidator(), httpCode)) {

                        String propertyName = propertyInstruction.getName();
                        checkNotNull(propertyName, "name");

                        String path = propertyInstruction.getFromObjectPath();
                        checkNotNull(path, "fromObjectPath");

                        Property.Scope scope = propertyInstruction.getScope();
                        Object propertyValue;
                        if (!isNullOrEmpty(path)) {
                            propertyValue = Ognl.getValue(path, templateDataObjects);
                        } else {
                            propertyValue = propertyInstruction.getValue();
                        }
                        memory.getConversationProperties().put(propertyName,
                                new Property(propertyName, propertyValue, scope));
                    }
                }
            }

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
        if (!path.startsWith(SLASH_CHAR) && !path.isEmpty()) {
            path = SLASH_CHAR + path;
        }
        URI targetUri = URI.create(targetServerUri + templateValues(path, templateDataObjects));
        String requestBody = templateValues(requestConfig.getBody(), templateDataObjects);

        var method = Method.valueOf(requestConfig.getMethod().toUpperCase());
        log.info("targetUri: {}", targetUri);
        log.info("method: {}", method);
        log.info("body: {}", requestBody);
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

                String targetServerUri = httpCallsConfig.getTargetServer().toString();
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
