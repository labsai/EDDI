package ai.labs.eddi.engine.httpclient;

import java.util.Map;

public interface IResponse {
    String getContentAsString();

    int getHttpCode();

    String getHttpCodeMessage();

    Map<String, String> getHttpHeader();

    class HttpResponseException extends Exception {
        public HttpResponseException(String message) {
            super(message);
        }

        public HttpResponseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
