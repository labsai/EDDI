/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
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
    /**
     * Request timeout for this call in milliseconds. {@code null} falls back to the
     * deployment-wide default ({@code eddi.httpcalls.default-timeout-millis}).
     */
    private Integer timeoutInMillis;
    /**
     * How much of the response body is kept in conversation memory, in bytes. A
     * larger body is <em>truncated</em> at this size (with a warning) rather than
     * failing the call — the call itself only fails if the response exceeds the
     * engine's much higher transport ceiling, which no configuration can lower.
     * {@code null} falls back to the deployment-wide default
     * ({@code eddi.httpcalls.default-max-response-size-bytes}, 2 MB).
     */
    private Integer maxResponseSizeInBytes;
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

    public Integer getTimeoutInMillis() {
        return timeoutInMillis;
    }

    public void setTimeoutInMillis(Integer timeoutInMillis) {
        this.timeoutInMillis = timeoutInMillis;
    }

    public Integer getMaxResponseSizeInBytes() {
        return maxResponseSizeInBytes;
    }

    public void setMaxResponseSizeInBytes(Integer maxResponseSizeInBytes) {
        this.maxResponseSizeInBytes = maxResponseSizeInBytes;
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
