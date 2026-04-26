/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.impl;

import ai.labs.eddi.engine.httpclient.ICompleteListener;
import ai.labs.eddi.engine.httpclient.IHttpClient;
import ai.labs.eddi.engine.httpclient.IRequest;
import ai.labs.eddi.engine.httpclient.IResponse;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClientSession;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class HttpClientWrapper implements IHttpClient {
    private static final String KEY_URI = "uri";
    private static final String KEY_METHOD = "method";
    private static final String KEY_HEADERS = "headers";
    private static final String KEY_LOGICAL_AND = "&";
    private static final String KEY_EQUALS = "=";
    private static final String KEY_QUERY_PARAMS = "queryParams";
    private static final String KEY_BODY = "body";
    private static final String KEY_USER_AGENT = "userAgent";
    private static final String KEY_MAX_LENGTH = "maxLength";
    private static final int TEXT_LIMIT = 150;
    private final WebClientSession webClient;
    private final String userAgent;
    private static final Logger log = Logger.getLogger(HttpClientWrapper.class);

    @Inject
    public HttpClientWrapper(VertxHttpClient httpClient, @ConfigProperty(name = "systemRuntime.projectDomain") String projectDomain,
            @ConfigProperty(name = "systemRuntime.projectVersion") String projectVersion) {
        this.webClient = httpClient.getWebClient();
        this.userAgent = projectDomain.toUpperCase() + "/" + projectVersion;
    }

    @Override
    public IRequest newRequest(URI uri) {
        return newRequest(uri, Method.GET);
    }

    @Override
    public IRequest newRequest(URI uri, Method method) {
        io.vertx.core.http.HttpMethod vertxMethod = io.vertx.core.http.HttpMethod.valueOf(method.name());
        // WebClient's requestAbs handles absolute URIs
        HttpRequest<Buffer> request = webClient.requestAbs(vertxMethod, uri.toString());
        request.putHeader("User-Agent", userAgent);

        return new RequestWrapper(uri, request, vertxMethod);
    }

    /**
     * Wrapper for Vert.x HttpRequest.
     * <p>
     * <b>Note:</b> This class is stateful and wraps a mutable {@link HttpRequest}.
     * It is designed to be used for a single request configuration and execution.
     * Reusing an instance of this class for multiple {@code send()} calls may
     * result in accumulated headers or query parameters.
     */
    private class RequestWrapper implements IRequest {
        private final URI uri;
        private final HttpRequest<Buffer> request;
        private final io.vertx.core.http.HttpMethod method;
        private int maxLength = 8 * 1024 * 1024;
        private String requestBody;
        private String requestEncoding;
        private long currentTimeout = 60000; // Default timeout fallback
        private final Map<String, List<String>> queryParamsMap = new HashMap<>();

        RequestWrapper(URI uri, HttpRequest<Buffer> request, io.vertx.core.http.HttpMethod method) {
            this.uri = uri;
            this.request = request;
            this.method = method;

            // Parse initial query params from URI if any
            String query = uri.getQuery();
            if (query != null && !query.isEmpty()) {
                String[] pairs = query.split(KEY_LOGICAL_AND);
                for (String pair : pairs) {
                    int idx = pair.indexOf(KEY_EQUALS);
                    String key = idx > 0 ? pair.substring(0, idx) : pair;
                    String value = idx > 0 && pair.length() > idx + 1 ? pair.substring(idx + 1) : null;

                    if (key != null)
                        key = URLDecoder.decode(key, StandardCharsets.UTF_8);
                    if (value != null)
                        value = URLDecoder.decode(value, StandardCharsets.UTF_8);

                    queryParamsMap.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
                }
            }
        }

        @Override
        public IRequest setBasicAuthentication(String username, String password, String realm, boolean preemptive) {
            if (!preemptive) {
                log.warn("Non-preemptive authentication is not supported with Vert.x WebClient; falling back to preemptive.");
            }
            request.basicAuthentication(username, password);
            return this;
        }

        public IRequest setHttpHeader(String headerName, String value) {
            request.putHeader(headerName, value);
            return this;
        }

        @Override
        public IRequest setQueryParam(String key, String value) {
            request.addQueryParam(key, value);
            queryParamsMap.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
            return this;
        }

        @Override
        public IRequest setUserAgent(String userAgent) {
            request.putHeader("User-Agent", userAgent);
            return this;
        }

        @Override
        public IRequest setBodyEntity(String content, String encoding, String contentType) {
            this.requestBody = content;
            this.requestEncoding = encoding;

            if (contentType != null && encoding != null) {
                request.putHeader("Content-Type", contentType + "; charset=" + encoding);
            } else if (contentType != null) {
                request.putHeader("Content-Type", contentType);
            }
            return this;
        }

        @Override
        public IRequest setMaxResponseSize(int maxLength) {
            this.maxLength = maxLength;
            // Note: Vert.x WebClient buffers the entire response by default.
            // Size limits are validated in handleResponse() after the response is received.
            return this;
        }

        @Override
        public IRequest setTimeout(long timeout, TimeUnit timeUnit) {
            long timeoutMillis = timeUnit.toMillis(timeout);
            this.currentTimeout = timeoutMillis;
            request.timeout(timeoutMillis);
            return this;
        }

        @Override
        public IResponse send() throws HttpRequestException {
            CompletableFuture<IResponse> future = new CompletableFuture<>();

            doSend(ar -> {
                if (ar.succeeded()) {
                    future.complete(ar.result());
                } else {
                    future.completeExceptionally(ar.cause());
                }
            });

            try {
                // Use a timeout slightly larger than the request timeout to ensure we don't
                // block indefinitely
                // if the callback never fires (though Vert.x should handle the timeout).
                return future.get(currentTimeout + 1000, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                throw new HttpRequestException("Request timed out while waiting for response", e);
            } catch (InterruptedException | ExecutionException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                Throwable cause = (e instanceof ExecutionException && e.getCause() != null) ? e.getCause() : e;
                throw new HttpRequestException(cause.getLocalizedMessage(), cause);
            }
        }

        private void doSend(io.vertx.core.Handler<io.vertx.core.AsyncResult<IResponse>> handler) {
            // Buffer entire response in memory; check size limits in handleResponse to
            // mitigate large responses.
            if (requestBody != null) {
                Buffer buffer;
                try {
                    buffer = requestEncoding != null ? Buffer.buffer(requestBody, requestEncoding) : Buffer.buffer(requestBody);
                } catch (IllegalArgumentException e) {
                    handler.handle(io.vertx.core.Future.failedFuture(new HttpRequestException("Invalid encoding: " + requestEncoding, e)));
                    return;
                }
                request.sendBuffer(buffer, ar -> handleResponse(ar, handler));
            } else {
                request.send(ar -> handleResponse(ar, handler));
            }
        }

        private void handleResponse(io.vertx.core.AsyncResult<HttpResponse<Buffer>> ar,
                                    io.vertx.core.Handler<io.vertx.core.AsyncResult<IResponse>> handler) {
            if (ar.succeeded()) {
                HttpResponse<Buffer> response = ar.result();
                // Check Content-Length header if available
                String contentLengthHeader = response.getHeader("Content-Length");
                if (contentLengthHeader != null) {
                    try {
                        long contentLength = Long.parseLong(contentLengthHeader);
                        if (contentLength > maxLength) {
                            String message = String.format("Response Content-Length %d exceeds maximum allowed length %d", contentLength, maxLength);
                            log.warn(message);
                            handler.handle(io.vertx.core.Future.failedFuture(new IResponse.HttpResponseException(message)));
                            return;
                        }
                    } catch (NumberFormatException e) {
                        // Ignore invalid content-length
                    }
                }

                Buffer body = response.body();
                if (body != null && body.length() > maxLength) {
                    String message = String.format("Response body length %d exceeds maximum allowed length %d", body.length(), maxLength);
                    log.warn(message);
                    handler.handle(io.vertx.core.Future.failedFuture(new IResponse.HttpResponseException(message)));
                    return;
                }

                ResponseWrapper responseWrapper = new ResponseWrapper();
                if (body != null && body.length() > 0) {
                    responseWrapper.setContentAsString(body.toString());
                } else {
                    responseWrapper.setContentAsString("");
                }

                responseWrapper.setHttpCode(response.statusCode());
                responseWrapper.setHttpCodeMessage(response.statusMessage());
                responseWrapper.setHttpHeader(convertHeaderToMap(response.headers()));
                handler.handle(io.vertx.core.Future.succeededFuture(responseWrapper));
            } else {
                handler.handle(io.vertx.core.Future.failedFuture(ar.cause()));
            }
        }

        @Override
        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();

            // Add URI and HTTP method
            map.put(KEY_URI, uri.toString());
            map.put(KEY_METHOD, method.name());

            // Add headers
            Map<String, String> headers = new HashMap<>();
            request.headers().forEach(entry -> headers.put(entry.getKey(), entry.getValue()));
            map.put(KEY_HEADERS, headers);

            // Add query parameters
            map.put(KEY_QUERY_PARAMS, queryParamsMap);

            // Add body if present
            if (requestBody != null) {
                map.put(KEY_BODY, requestBody);
            }

            // Add user agent
            String userAgent = request.headers().get("User-Agent");
            if (userAgent != null) {
                map.put(KEY_USER_AGENT, userAgent);
            }

            // Add other relevant details
            map.put(KEY_MAX_LENGTH, maxLength);

            return map;
        }

        @Override
        public void send(final ICompleteListener completeListener) {
            doSend(ar -> {
                if (ar.succeeded()) {
                    try {
                        completeListener.onComplete(ar.result());
                    } catch (IResponse.HttpResponseException e) {
                        log.error(e.getLocalizedMessage(), e);
                    }
                } else {
                    log.error(ar.cause().getLocalizedMessage(), ar.cause());
                    // Notify listener of failure via a synthetic 503 response.
                    ResponseWrapper errorResponse = new ResponseWrapper();
                    errorResponse.setHttpCode(503);
                    errorResponse.setHttpCodeMessage("Service Unavailable: " + ar.cause().getLocalizedMessage());
                    errorResponse.setContentAsString("");
                    errorResponse.setHttpHeader(Collections.emptyMap());
                    try {
                        completeListener.onComplete(errorResponse);
                    } catch (IResponse.HttpResponseException e) {
                        log.error("Error while calling onComplete with error response", e);
                    }
                }
            });
        }

        @Override
        public String toString() {
            String requestBodyTruncated = truncateAndClean(this.requestBody);

            return String.format("RequestWrapper{uri=%s, method=%s, requestBody=\"%s\", maxLength=%d, queryParams=%s}", uri, method,
                    requestBodyTruncated, maxLength, queryParamsMap);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            RequestWrapper that = (RequestWrapper) o;
            return maxLength == that.maxLength && currentTimeout == that.currentTimeout && java.util.Objects.equals(uri, that.uri)
                    && java.util.Objects.equals(request, that.request) && java.util.Objects.equals(method, that.method)
                    && java.util.Objects.equals(requestBody, that.requestBody) && java.util.Objects.equals(requestEncoding, that.requestEncoding)
                    && java.util.Objects.equals(queryParamsMap, that.queryParamsMap);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(uri, request, method, maxLength, requestBody, requestEncoding, currentTimeout, queryParamsMap);
        }
    }

    private static class ResponseWrapper implements IResponse {
        private String contentAsString;
        private int httpCode;
        private String httpCodeMessage;
        private Map<String, String> httpHeader;

        @Override
        public String toString() {
            String contentAsStringTruncated = truncateAndClean(this.contentAsString);

            String httpHeaderString = httpHeader != null ? httpHeader.toString() : null;

            return String.format("ResponseWrapper{httpCode=%d, httpCodeMessage=\"%s\", responseBody=\"%s\", httpHeader=%s}", httpCode,
                    httpCodeMessage, contentAsStringTruncated, httpHeaderString);
        }

        public String getContentAsString() {
            return contentAsString;
        }

        public void setContentAsString(String contentAsString) {
            this.contentAsString = contentAsString;
        }

        public int getHttpCode() {
            return httpCode;
        }

        public void setHttpCode(int httpCode) {
            this.httpCode = httpCode;
        }

        public String getHttpCodeMessage() {
            return httpCodeMessage;
        }

        public void setHttpCodeMessage(String httpCodeMessage) {
            this.httpCodeMessage = httpCodeMessage;
        }

        public Map<String, String> getHttpHeader() {
            return httpHeader;
        }

        public void setHttpHeader(Map<String, String> httpHeader) {
            this.httpHeader = httpHeader;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            ResponseWrapper that = (ResponseWrapper) o;
            return httpCode == that.httpCode && java.util.Objects.equals(contentAsString, that.contentAsString)
                    && java.util.Objects.equals(httpCodeMessage, that.httpCodeMessage) && java.util.Objects.equals(httpHeader, that.httpHeader);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(contentAsString, httpCode, httpCodeMessage, httpHeader);
        }
    }

    // Package-private for testability (HttpClientWrapperTest)
    static String truncateAndClean(String text) {
        if (text == null) {
            return null;
        }

        text = text.replaceAll("\\r?\\n", " ");
        if (text.length() > TEXT_LIMIT) {
            text = text.substring(0, TEXT_LIMIT) + "...";
        }

        return text;
    }

    // Package-private for testability (HttpClientWrapperTest)
    static Map<String, String> convertHeaderToMap(MultiMap headers) {
        Map<String, String> httpHeader = new HashMap<>();
        for (Map.Entry<String, String> header : headers) {
            httpHeader.put(header.getKey(), header.getValue());
        }
        return httpHeader;
    }
}
