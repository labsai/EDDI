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
     * SSRF-protected callers disable redirects to prevent a
     * {@code 3xx → internal host} bypass of URL validation. This is intentionally
     * <b>not</b> a default no-op: any {@link IRequest} implementation must honour
     * it (or explicitly throw) so a new client cannot silently re-enable the
     * redirect bypass — it fails closed at compile time instead.
     */
    IRequest setFollowRedirects(boolean follow);

    IResponse send() throws HttpRequestException;

    /** Key of the fully-resolved target URI in {@link #toMap()}. */
    String KEY_URI = "uri";
    /** Key of the HTTP method name in {@link #toMap()}. */
    String KEY_METHOD = "method";
    /** Key of the {@code Map<String, String>} of headers in {@link #toMap()}. */
    String KEY_HEADERS = "headers";
    /**
     * Key of the {@code Map<String, String>} of query params in {@link #toMap()}.
     */
    String KEY_QUERY_PARAMS = "queryParams";
    /** Key of the request body in {@link #toMap()}; absent when there is none. */
    String KEY_BODY = "body";
    /** Key of the User-Agent header in {@link #toMap()}; absent when unset. */
    String KEY_USER_AGENT = "userAgent";

    /**
     * The request as a plain map, keyed by the {@code KEY_*} constants above.
     * <p>
     * <b>Header values are live</b> — resolved secrets and bearer tokens included.
     * Anything that persists or displays this must redact it first
     * ({@code RequestRedactor}).
     */
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
