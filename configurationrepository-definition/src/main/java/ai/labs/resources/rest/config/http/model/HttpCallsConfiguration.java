package ai.labs.resources.rest.config.http.model;


import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * @author ginccc
 */

@Getter
@Setter
public class HttpCallsConfiguration {
    @JsonAlias("targetServer")
    private String targetServerUrl;
    private List<HttpCall> httpCalls;
}
