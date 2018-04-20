package ai.labs.resources.rest.http.model;

import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
public class Request {
    private String path;
    private Map<String, String> headers;
    private String method;
    private String contentType;
    private String body;

    public Request() {
        path = "";
        headers = new HashMap<>();
        method = "GET";
        contentType = "";
        body = "";
    }
}
