package ai.labs.graphql.connector.impl;

import ai.labs.httpclient.IHttpClient;
import ai.labs.httpclient.IRequest;
import ai.labs.httpclient.IResponse;
import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.lifecycle.LifecycleException;
import ai.labs.lifecycle.PackageConfigurationException;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IDataFactory;
import ai.labs.serialization.IJsonSerialization;
import ai.labs.templateengine.ITemplatingEngine;

import javax.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HttpCallsTask implements ILifecycleTask {
    public static final String UTF_8 = "utf-8";
    private final IHttpClient httpClient;
    private IJsonSerialization jsonSerialization;
    private IDataFactory dataFactory;
    private final ITemplatingEngine templatingEngine;
    private URI hostServer;

    @Inject
    public HttpCallsTask(IHttpClient httpClient, IJsonSerialization jsonSerialization,
                         IDataFactory dataFactory, ITemplatingEngine templatingEngine) {
        this.httpClient = httpClient;
        this.jsonSerialization = jsonSerialization;
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
        try {
            IHttpClient.Method method = IHttpClient.Method.POST;
            IRequest request = httpClient.newRequest(hostServer, method);

            String name = "someName";
            List<String> actions = Arrays.asList("verifySMSCode");
            Map<String, Object> properties = new HashMap<>();

            String requestBody = templateValues("mutation verifySMSCode {verifySMSCode(phone: \"+#{properties.user.phoneNumber}\", code: \"\") {token}}", properties);
            String contentType = "text/plain";

            String token = "";
            request.setHttpHeader("authorization", templateValues("#{httpCalls.data.token}", properties));

            IResponse response = request.
                    setBodyEntity(templateValues(requestBody, properties), UTF_8, contentType).
                    send();

            String responseBody = response.getContentAsString();
            Object responseObject = jsonSerialization.deserialize(responseBody, Object.class);

            memory.getCurrentStep().storeData(dataFactory.createData("properties:" + name, responseObject));


           /* memory.getCurrentStep().storeData(dataFactory.createData(
                    "context:quickReplies",
                    buildQuickReplies(response.getContentAsString()))
            );*/
        } catch (IRequest.HttpRequestException | IOException | ITemplatingEngine.TemplateEngineException e) {
            throw new LifecycleException(e.getLocalizedMessage(), e);
        }
    }

    private String templateValues(String toBeTemplated, Map<String, Object> properties)
            throws ITemplatingEngine.TemplateEngineException {

        return templatingEngine.processTemplate(toBeTemplated, properties);
    }

    private String buildQuickReplies(String contentAsString) throws ITemplatingEngine.TemplateEngineException {
        HashMap<String, Object> dynamicAttributesMap = new HashMap<>();
        return templatingEngine.processTemplate("[# th:each=\"community : ${graphQL.communities}\"]" +
                "    {" +
                "        \"value\":\"[(${community.name})]\"," +
                "        \"expressions\":\"property(community([(${community.id})])\"" +
                "    }" +
                "[/]", dynamicAttributesMap);
    }

    @Override
    public void configure(Map<String, Object> configuration) throws PackageConfigurationException {

    }
}
