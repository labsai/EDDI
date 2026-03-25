package ai.labs.eddi.configs.apicalls.model;

import java.util.HashMap;
import java.util.Map;

public class Request {
    private String path;
    private Map<String, String> headers;
    private Map<String, String> queryParams;
    private String method;
    private String contentType;
    private String body;

    public Request() {
        path = "";
        headers = new HashMap<>();
        queryParams = new HashMap<>();
        method = "GET";
        contentType = "";
        body = "";
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }

    public Map<String, String> getQueryParams() {
        return queryParams;
    }

    public void setQueryParams(Map<String, String> queryParams) {
        this.queryParams = queryParams;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }
}
