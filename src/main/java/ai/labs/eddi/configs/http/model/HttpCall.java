package ai.labs.eddi.configs.http.model;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
public class HttpCall {
    private String name;
    private String description; // Natural language description for LLM agents
    private Map<String, String> parameters; // Map of parameter_name -> parameter_description for LLM agents
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
