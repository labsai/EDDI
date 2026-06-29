/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
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

    /**
     * Enable or disable automatic HTTP redirect following for this request.
     * <p>
     * The default implementation is a no-op (preserves the client's configured
     * behaviour); the Vert.x-backed implementation honours it. SSRF-protected
     * callers disable redirects to prevent a {@code 3xx → internal host} bypass of
     * URL validation.
     */
    default IRequest setFollowRedirects(boolean follow) {
        return this;
    }

    IResponse send() throws HttpRequestException;

    Map<String, Object> toMap();

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
