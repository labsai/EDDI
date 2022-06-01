package ai.labs.eddi.configs.http.model;


import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * @author ginccc
 */

@Getter
@Setter
public class HttpCallsConfiguration {
    private String targetServerUrl;
    private List<HttpCall> httpCalls;
}
