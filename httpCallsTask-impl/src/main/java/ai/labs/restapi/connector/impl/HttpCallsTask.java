package ai.labs.restapi.connector.impl;

import ai.labs.httpclient.IHttpClient;
import ai.labs.httpclient.IRequest;
import ai.labs.httpclient.IResponse;
import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.lifecycle.LifecycleException;
import ai.labs.lifecycle.PackageConfigurationException;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;
import ai.labs.memory.IDataFactory;
import ai.labs.resources.rest.http.model.HttpCall;
import ai.labs.resources.rest.http.model.HttpCallsConfiguration;
import ai.labs.resources.rest.http.model.Request;
import ai.labs.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.runtime.service.ServiceException;
import ai.labs.serialization.IJsonSerialization;
import ai.labs.templateengine.ITemplatingEngine;
import ai.labs.utilities.TemplatingUtilities;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class HttpCallsTask implements ILifecycleTask {
    private static final String UTF_8 = "utf-8";
    private static final String CONTENT_TYPE_APPLICATION_JSON = "application/json";
    private static final String ACTION_KEY = "actions";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String KEY_HTTP_CALLS = "httpCalls";
    private static final String COLON = ":";
    private final IHttpClient httpClient;
    private IJsonSerialization jsonSerialization;
    private final IResourceClientLibrary resourceClientLibrary;
    private IDataFactory dataFactory;
    private final ITemplatingEngine templatingEngine;
    private String targetServerUri;
    private List<HttpCall> httpCalls;

    @Inject
    public HttpCallsTask(IHttpClient httpClient, IJsonSerialization jsonSerialization,
                         IResourceClientLibrary resourceClientLibrary, IDataFactory dataFactory,
                         ITemplatingEngine templatingEngine) {
        this.httpClient = httpClient;
        this.jsonSerialization = jsonSerialization;
        this.resourceClientLibrary = resourceClientLibrary;
        this.dataFactory = dataFactory;
        this.templatingEngine = templatingEngine;
    }

    @Override
    public String getId() {
        return HttpCallsTask.class.getSimpleName();
    }

    @Override
    public Object getComponent() {
        return null;
    }

    @Override
    public void executeTask(IConversationMemory memory) throws LifecycleException {
        IData<List<String>> latestData = memory.getCurrentStep().getLatestData(ACTION_KEY);
        if (latestData == null) {
            return;
        }

        Map<String, Object> templateDataObjects = new HashMap<>();
        Map<String, Object> memoryForTemplate = TemplatingUtilities.convertMemoryForTemplating(memory);
        Map<String, Object> currentMemory = (Map<String, Object>) memoryForTemplate.get("current");
        templateDataObjects.put("memory", memoryForTemplate);

        List<String> actions = latestData.getResult();

        for (String action : actions) {
            List<HttpCall> httpCalls;
            httpCalls = this.httpCalls.stream().
                    filter(httpCall -> httpCall.getActions().contains(action)).
                    collect(Collectors.toList());
            for (HttpCall call : httpCalls) {
                try {
                    Request requestConfig = call.getRequest();
                    String targetUri = targetServerUri + requestConfig.getPath();
                    String requestBody = templateValues(requestConfig.getBody(), templateDataObjects);
                    IRequest request = httpClient.newRequest(URI.create(targetUri),
                            IHttpClient.Method.valueOf(requestConfig.getMethod().toUpperCase())).
                            setBodyEntity(requestBody,
                                    UTF_8, requestConfig.getContentType());

                    Map<String, String> headers = requestConfig.getHeaders();
                    for (String headerName : headers.keySet()) {
                        request.setHttpHeader(headerName, templateValues(headers.get(headerName), templateDataObjects));
                    }

                    IResponse response = request.send();
                    if (response.getHttpCode() != 200) {
                        String message = "HttpCall (%s) didn't return http code 200, instead %s.";
                        log.warn(String.format(message, call.getName(), response.getHttpCode()));
                        log.warn("Error Msg:" + response.getHttpCodeMessage());
                        continue;
                    }
                    if (call.isSaveResponse()) {
                        String responseBody = response.getContentAsString();
                        log.debug("http call response:" + responseBody);
                        String actualContentType = response.getHttpHeader().get(CONTENT_TYPE);
                        if (!CONTENT_TYPE_APPLICATION_JSON.equals(actualContentType)) {
                            String message = "HttpCall (%s) didn't return application/json as content-type, instead was (%s)";
                            log.warn(String.format(message, call.getName(), actualContentType));
                            continue;
                        }

                        Object responseObject = jsonSerialization.deserialize(responseBody, Object.class);
                        String responseObjectName = call.getResponseObjectName();
                        Map<String, Object> templateHttpCalls = (Map<String, Object>) currentMemory.get(KEY_HTTP_CALLS);
                        if (templateHttpCalls == null) {
                            templateHttpCalls = new HashMap<>();
                            currentMemory.put(KEY_HTTP_CALLS, templateHttpCalls);
                        }

                        templateHttpCalls.put(responseObjectName, responseObject);
                        String memoryDataName = "httpCalls:" + responseObjectName;
                        IData<Object> httpResponseData = dataFactory.createData(memoryDataName, responseObject);
                        memory.getCurrentStep().storeData(httpResponseData);
                    }

                } catch (IRequest.HttpRequestException | IOException | ITemplatingEngine.TemplateEngineException e) {
                    log.error(e.getLocalizedMessage(), e);
                    throw new LifecycleException(e.getLocalizedMessage(), e);
                }
            }
        }

           /* memory.getCurrentStep().storeData(dataFactory.createData(
                    "context:quickReplies",
                    buildQuickReplies(response.getContentAsString()))
            );*/
    }

    private String templateValues(String toBeTemplated, Map<String, Object> properties)
            throws ITemplatingEngine.TemplateEngineException {

        return templatingEngine.processTemplate(toBeTemplated, properties);
    }

/*    private String buildQuickReplies(String contentAsString) throws ITemplatingEngine.TemplateEngineException {
        HashMap<String, Object> dynamicAttributesMap = new HashMap<>();
        return templatingEngine.processTemplate("[# th:each=\"community : ${graphQL.communities}\"]" +
                "    {" +
                "        \"value\":\"[(${community.name})]\"," +
                "        \"expressions\":\"property(community([(${community.id})])\"" +
                "    }" +
                "[/]", dynamicAttributesMap);
    }*/

    @Override
    public void configure(Map<String, Object> configuration) throws PackageConfigurationException {
        Object uriObj = configuration.get("uri");
        URI uri = URI.create(uriObj.toString());

        try {
            HttpCallsConfiguration httpCallsConfig = resourceClientLibrary.getResource(uri, HttpCallsConfiguration.class);

            this.targetServerUri = httpCallsConfig.getTargetServer().toString();
            this.httpCalls = httpCallsConfig.getHttpCalls();

        } catch (ServiceException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new PackageConfigurationException(e.getMessage(), e);
        }
    }
}
