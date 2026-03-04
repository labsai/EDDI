package ai.labs.eddi.configs.http.model;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
public class HttpCall {
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
}
