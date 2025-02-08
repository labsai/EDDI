package ai.labs.eddi.engine.httpclient.impl;

import ai.labs.eddi.engine.httpclient.ICompleteListener;
import ai.labs.eddi.engine.httpclient.IHttpClient;
import ai.labs.eddi.engine.httpclient.IRequest;
import ai.labs.eddi.engine.httpclient.IResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import org.eclipse.jetty.client.*;
import org.eclipse.jetty.http.HttpCookieStore;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
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
    private final HttpClient httpClient;
    private final String userAgent;
    private static final Logger log = Logger.getLogger(HttpClientWrapper.class);

    @Inject
    public HttpClientWrapper(JettyHttpClient httpClient,
                             @ConfigProperty(name = "systemRuntime.projectDomain") String projectDomain,
                             @ConfigProperty(name = "systemRuntime.projectVersion") String projectVersion) {
        this.httpClient = httpClient.getHttpClient();
        this.userAgent = projectDomain.toUpperCase() + "/" + projectVersion;
    }

    @Override
    public HttpCookieStore getCookieStore() {
        return httpClient.getHttpCookieStore();
    }

    @Override
    public IRequest newRequest(URI uri) {
        return newRequest(uri, Method.GET);
    }

    @Override
    public IRequest newRequest(URI uri, Method method) {
        var request = httpClient.newRequest(uri).method(method.name()).
                headers(httpFields -> httpFields.put(HttpHeader.USER_AGENT, userAgent));
        return new RequestWrapper(uri, request);
    }

    @Getter
    @EqualsAndHashCode
    private class RequestWrapper implements IRequest {
        private final URI uri;
        private final Request request;
        private int maxLength = 8 * 1024 * 1024;
        private String requestBody;

        RequestWrapper(URI uri, Request request) {
            this.uri = uri;
            this.request = request;
        }

        @Override
        public IRequest setBasicAuthentication(String username, String password, String realm, boolean preemptive) {
            if (preemptive) {
                request.headers(httpFields -> httpFields.add("Authorization", "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes())));
            } else {
                AuthenticationStore auth = httpClient.getAuthenticationStore();
                auth.addAuthentication(new BasicAuthentication(uri, realm, username, password));
            }

            return this;
        }

        public IRequest setHttpHeader(String headerName, String value) {
            request.headers(httpFields -> httpFields.add(headerName, value));
            return this;
        }

        @Override
        public IRequest setQueryParam(String key, String value) {
            request.param(key, value);
            return this;
        }

        @Override
        public IRequest setUserAgent(String userAgent) {
            request.agent(userAgent);
            return this;
        }

        @Override
        public IRequest setBodyEntity(String content, String encoding, String contentType) {
            this.requestBody = content;
            request.body(new StringRequestContent(contentType, this.requestBody, Charset.forName(encoding)));
            return this;
        }

        @Override
        public IRequest setMaxResponseSize(int maxLength) {
            this.maxLength = maxLength;

            return this;
        }

        @Override
        public IRequest setTimeout(long timeout, TimeUnit timeUnit) {
            request.timeout(timeout, timeUnit);

            return this;
        }

        @Override
        public IResponse send() throws HttpRequestException {
            try {
                var listener = new CompletableResponseListener(request, maxLength);
                CompletableFuture<ContentResponse> completableFuture = listener.send();
                completableFuture.thenApply(ContentResponse::getContentAsString);
                var response = completableFuture.get();
                var responseWrapper = new ResponseWrapper();
                responseWrapper.setContentAsString(response.getContentAsString());
                responseWrapper.setHttpCode(response.getStatus());
                responseWrapper.setHttpCodeMessage(response.getReason());
                responseWrapper.setHttpHeader(convertHeaderToMap(response.getHeaders()));

                return responseWrapper;
            } catch (InterruptedException | ExecutionException e) {
                throw new HttpRequestException(e.getLocalizedMessage(), e);
            }
        }

        @Override
        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();

            // Add URI and HTTP method
            map.put(KEY_URI, uri.toString());
            map.put(KEY_METHOD, request.getMethod());

            // Add headers
            Map<String, String> headers = new HashMap<>();
            request.getHeaders().forEach(field -> headers.put(field.getName(), field.getValue()));
            map.put(KEY_HEADERS, headers);

            // Add query parameters by parsing the URI
            Map<String, List<String>> queryParams = new HashMap<>();
            String query = uri.getQuery();
            if (query != null && !query.isEmpty()) {
                String[] pairs = query.split(KEY_LOGICAL_AND);
                for (String pair : pairs) {
                    int idx = pair.indexOf(KEY_EQUALS);
                    String key = idx > 0 ? pair.substring(0, idx) : pair;
                    String value = idx > 0 && pair.length() > idx + 1 ? pair.substring(idx + 1) : null;
                    queryParams.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
                }
            }
            map.put(KEY_QUERY_PARAMS, queryParams);

            // Add body if present
            if (requestBody != null) {
                map.put(KEY_BODY, requestBody);
            }

            // Add user agent if present
            String userAgent = request.getAgent();
            if (userAgent != null) {
                map.put(KEY_USER_AGENT, userAgent);
            }

            // Add other relevant details
            map.put(KEY_MAX_LENGTH, maxLength);

            return map;
        }


        @Override
        public void send(final ICompleteListener completeListener) {
            final BufferingResponseListener responseListener = new BufferingResponseListener(maxLength) {
                @Override
                public void onComplete(final Result result) {
                    final Response response = result.getResponse();
                    final String content = getContentAsString();

                    try {
                        var responseWrapper = new ResponseWrapper();
                        responseWrapper.setContentAsString(content);
                        responseWrapper.setHttpCode(response.getStatus());
                        responseWrapper.setHttpCodeMessage(response.getReason());
                        responseWrapper.setHttpHeader(convertHeaderToMap(response.getHeaders()));

                        completeListener.onComplete(responseWrapper);
                    } catch (IResponse.HttpResponseException e) {
                        log.error(e.getLocalizedMessage(), e);
                    }
                }
            };

            request.send(responseListener);
        }

        @Override
        public String toString() {
            String requestBody = truncateAndClean(this.requestBody);

            return String.format("RequestWrapper{uri=%s, request=%s, requestBody=\"%s\", maxLength=%d, queryParams=%s}",
                    uri, request, requestBody, maxLength, request.getParams());
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
            String contentAsString = truncateAndClean(this.contentAsString);

            String httpHeaderString = httpHeader != null ? httpHeader.toString() : null;

            return String.format("ResponseWrapper{httpCode=%d, httpCodeMessage=\"%s\", responseBody=\"%s\", httpHeader=%s}",
                    httpCode, httpCodeMessage, contentAsString, httpHeaderString);
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

    private static Map<String, String> convertHeaderToMap(HttpFields headers) {
        Map<String, String> httpHeader = new HashMap<>();
        for (HttpField header : headers) {
            httpHeader.put(header.getName(), header.getValue());
        }
        return httpHeader;
    }
}
