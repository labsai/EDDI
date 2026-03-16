package ai.labs.eddi.configs.http.model;



import java.util.List;

/**
 * @author ginccc
 */

public class HttpCallsConfiguration {
    private String targetServerUrl;
    private List<HttpCall> httpCalls;

    public String getTargetServerUrl() {
        return targetServerUrl;
    }

    public void setTargetServerUrl(String targetServerUrl) {
        this.targetServerUrl = targetServerUrl;
    }

    public List<HttpCall> getHttpCalls() {
        return httpCalls;
    }

    public void setHttpCalls(List<HttpCall> httpCalls) {
        this.httpCalls = httpCalls;
    }
}
