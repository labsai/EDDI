/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.apicalls.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Request {
    private String path;
    private Map<String, String> headers;
    private Map<String, String> queryParams;
    private List<String> optionalQueryParams;
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

    /**
     * Keys of {@link #getQueryParams()} that are left off the request when their
     * value renders empty, instead of being sent as {@code ?name=}.
     * <p>
     * For a value the caller may omit: a generated tool marks an OpenAPI query
     * parameter that is not {@code required} as an optional tool argument, and an
     * argument the model leaves out renders as the empty string. Sent anyway, that
     * empty value replaces the server's own default — and an empty {@code ?limit=}
     * on an integer parameter is not a value at all. {@code null} (the default)
     * keeps every parameter, as configurations written before this field expect.
     */
    public List<String> getOptionalQueryParams() {
        return optionalQueryParams;
    }

    public void setOptionalQueryParams(List<String> optionalQueryParams) {
        this.optionalQueryParams = optionalQueryParams;
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
