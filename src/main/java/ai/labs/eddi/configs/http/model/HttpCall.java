package ai.labs.eddi.configs.http.model;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class HttpCall {
    private String name;
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
