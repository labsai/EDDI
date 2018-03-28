package ai.labs.resources.rest.http.model;


import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */

@Getter
@Setter
public class HttpCallsConfiguration {
    private URI targetServer;
    private List<HttpCall> httpCalls;
}
