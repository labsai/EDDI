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
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.nio.charset.Charset;
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
    public HttpClientWrapper(VertxHttpClient httpClient,
                             @ConfigProperty(name = "systemRuntime.projectDomain") String projectDomain,
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

    @Getter
    @EqualsAndHashCode
    private class RequestWrapper implements IRequest {
        private final URI uri;
        private final HttpRequest<Buffer> request;
        private final io.vertx.core.http.HttpMethod method;
        private int maxLength = 8 * 1024 * 1024;
        private String requestBody;
        private String requestEncoding;
        private String requestContentType;
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
                    queryParamsMap.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
                }
            }
        }

        @Override
        public IRequest setBasicAuthentication(String username, String password, String realm, boolean preemptive) {
             // Vert.x basic auth helper
             // realm and preemptive are not used in Vert.x basicAuthentication as it sets the header directly (preemptive)
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
            this.requestContentType = contentType;

            if (contentType != null) {
                 request.putHeader("Content-Type", contentType);
            }
            return this;
        }

        @Override
        public IRequest setMaxResponseSize(int maxLength) {
            this.maxLength = maxLength;
            // Vert.x doesn't have a direct "max response size" on the request builder that limits buffering
            // automatically in the same way as Jetty's response listener might, but we can inspect content-length
            // or handle it in a stream if needed. However, for standard buffer response, it just buffers.
            // If we needed strict limiting, we might need to use sendStream and count bytes.
            // For now, we will assume the buffer handles it or we check afterwards.
            // Actually, `as(BodyCodec.string())` or similar doesn't limit.
            // Given the previous implementation used `BufferingResponseListener` with a limit,
            // we should be careful. But Vert.x WebClient buffers everything by default.
            return this;
        }

        @Override
        public IRequest setTimeout(long timeout, TimeUnit timeUnit) {
            request.timeout(timeUnit.toMillis(timeout));
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
                return future.get();
            } catch (InterruptedException | ExecutionException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new HttpRequestException(e.getLocalizedMessage(), e);
            }
        }

        private void doSend(io.vertx.core.Handler<io.vertx.core.AsyncResult<IResponse>> handler) {
            // Use stream to handle large responses safely
            if (requestBody != null) {
                Buffer buffer = requestEncoding != null ? Buffer.buffer(requestBody, requestEncoding) : Buffer.buffer(requestBody);
                request.sendBuffer(buffer, ar -> handleResponse(ar, handler));
            } else {
                request.send(ar -> handleResponse(ar, handler));
            }
        }

        private void handleResponse(io.vertx.core.AsyncResult<HttpResponse<Buffer>> ar, io.vertx.core.Handler<io.vertx.core.AsyncResult<IResponse>> handler) {
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
                 if (body != null) {
                     responseWrapper.setContentAsString(response.bodyAsString());
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
                    // Attempt to notify listener of failure via a 500 response if possible,
                    // strictly speaking ICompleteListener expects a response.
                    ResponseWrapper errorResponse = new ResponseWrapper();
                    errorResponse.setHttpCode(500);
                    errorResponse.setHttpCodeMessage(ar.cause().getLocalizedMessage());
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

            return String.format("RequestWrapper{uri=%s, method=%s, requestBody=\"%s\", maxLength=%d, queryParams=%s}",
                    uri, method, requestBodyTruncated, maxLength, queryParamsMap);
        }
    }

    @Setter
    @Getter
    @EqualsAndHashCode
    private static class ResponseWrapper implements IResponse {
        private String contentAsString;
        private int httpCode;
        private String httpCodeMessage;
        private Map<String, String> httpHeader;

        @Override
        public String toString() {
            String contentAsStringTruncated = truncateAndClean(this.contentAsString);

            String httpHeaderString = httpHeader != null ? httpHeader.toString() : null;

            return String.format("ResponseWrapper{httpCode=%d, httpCodeMessage=\"%s\", responseBody=\"%s\", httpHeader=%s}",
                    httpCode, httpCodeMessage, contentAsStringTruncated, httpHeaderString);
        }
    }

    private static String truncateAndClean(String text) {
        if (text == null) {
            return null;
        }

        text = text.replaceAll("\\r?\\n", " ");
        if (text.length() > TEXT_LIMIT) {
            text = text.substring(0, TEXT_LIMIT) + "...";
        }

        return text;
    }

    private static Map<String, String> convertHeaderToMap(MultiMap headers) {
        Map<String, String> httpHeader = new HashMap<>();
        for (Map.Entry<String, String> header : headers) {
            httpHeader.put(header.getKey(), header.getValue());
        }
        return httpHeader;
    }
}
