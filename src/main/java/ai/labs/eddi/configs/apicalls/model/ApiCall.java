package ai.labs.eddi.configs.apicalls.model;

import java.util.List;
import java.util.Map;

public class ApiCall {
    private String name;
    /**
     * Natural language description for LLM agents.
     */
    private String description;
    /**
     * Map of parameter name to parameter description for LLM agents.
     */
    private Map<String, String> parameters;
    private List<String> actions;
    private Boolean saveResponse = false;
    private String responseObjectName;
    private String responseHeaderObjectName;
    private Boolean fireAndForget = false;
    private Boolean isBatchCalls = false;
    private String iterationObjectName;
    private HttpPreRequest preRequest;
    private Request request;
    private HttpPostResponse postResponse;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Map<String, String> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, String> parameters) {
        this.parameters = parameters;
    }

    public List<String> getActions() {
        return actions;
    }

    public void setActions(List<String> actions) {
        this.actions = actions;
    }

    public Boolean getSaveResponse() {
        return saveResponse;
    }

    public void setSaveResponse(Boolean saveResponse) {
        this.saveResponse = saveResponse;
    }

    public String getResponseObjectName() {
        return responseObjectName;
    }

    public void setResponseObjectName(String responseObjectName) {
        this.responseObjectName = responseObjectName;
    }

    public String getResponseHeaderObjectName() {
        return responseHeaderObjectName;
    }

    public void setResponseHeaderObjectName(String responseHeaderObjectName) {
        this.responseHeaderObjectName = responseHeaderObjectName;
    }

    public Boolean getFireAndForget() {
        return fireAndForget;
    }

    public void setFireAndForget(Boolean fireAndForget) {
        this.fireAndForget = fireAndForget;
    }

    public Boolean getIsBatchCalls() {
        return isBatchCalls;
    }

    public void setIsBatchCalls(Boolean isBatchCalls) {
        this.isBatchCalls = isBatchCalls;
    }

    public String getIterationObjectName() {
        return iterationObjectName;
    }

    public void setIterationObjectName(String iterationObjectName) {
        this.iterationObjectName = iterationObjectName;
    }

    public HttpPreRequest getPreRequest() {
        return preRequest;
    }

    public void setPreRequest(HttpPreRequest preRequest) {
        this.preRequest = preRequest;
    }

    public Request getRequest() {
        return request;
    }

    public void setRequest(Request request) {
        this.request = request;
    }

    public HttpPostResponse getPostResponse() {
        return postResponse;
    }

    public void setPostResponse(HttpPostResponse postResponse) {
        this.postResponse = postResponse;
    }
}
