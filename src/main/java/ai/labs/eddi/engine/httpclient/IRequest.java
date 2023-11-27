package ai.labs.eddi.engine.httpclient;

import java.util.Map;
import java.util.concurrent.TimeUnit;

public interface IRequest {
    IRequest setBasicAuthentication(String username, String password, String realm, boolean preemptive);

    IRequest setQueryParam(String key, String value);

    IRequest setUserAgent(String userAgent);

    IRequest setHttpHeader(String headerName, String value);

    IRequest setBodyEntity(String content, String encoding, String contentType);

    IRequest setMaxResponseSize(int byteSize);

    IRequest setTimeout(long timeout, TimeUnit timeUnit);

    IResponse send() throws HttpRequestException;

    Map<String,Object> toMap();

    void send(ICompleteListener completeListener) throws HttpRequestException;

    class HttpRequestException extends Exception {
        public HttpRequestException(String message) {
            super(message);
        }

        public HttpRequestException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
