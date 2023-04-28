package ai.labs.eddi.engine.httpclient.impl;

import ai.labs.eddi.engine.httpclient.ICompleteListener;
import ai.labs.eddi.engine.httpclient.IHttpClient;
import ai.labs.eddi.engine.httpclient.IRequest;
import ai.labs.eddi.engine.httpclient.IResponse;
import lombok.Getter;
import lombok.Setter;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.*;
import org.eclipse.jetty.client.util.BasicAuthentication;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.client.util.FutureResponseListener;
import org.eclipse.jetty.client.util.StringRequestContent;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.CookieStore;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class HttpClientWrapper implements IHttpClient {
    private final HttpClient httpClient;

    private static final Logger log = Logger.getLogger(HttpClientWrapper.class);

    @Inject
    public HttpClientWrapper(JettyHttpClient httpClient) {
        this.httpClient = httpClient.getHttpClient();
    }

    @Override
    public CookieStore getCookieStore() {
        return httpClient.getCookieStore();
    }

    @Override
    public IRequest newRequest(URI uri) {
        return newRequest(uri, Method.GET);
    }

    @Override
    public IRequest newRequest(URI uri, Method method) {
        Request request = httpClient.newRequest(uri).method(method.name());
        return new RequestWrapper(uri, request);
    }

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
                request.headers(httpFields ->
                        httpFields.add("Authorization", "Basic " + Base64.getEncoder().
                                encodeToString((username + ":" + password).getBytes())));
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
            final FutureResponseListener listener = new FutureResponseListener(request, maxLength);
            request.send(listener);
            try {
                final ContentResponse response = listener.get();
                var responseWrapper = new ResponseWrapper();
                responseWrapper.setContentAsString(listener.getContentAsString());
                responseWrapper.setHttpCode(response.getStatus());
                responseWrapper.setHttpCodeMessage(response.getReason());
                responseWrapper.setHttpHeader(convertHeaderToMap(response.getHeaders()));

                return responseWrapper;
            } catch (InterruptedException | ExecutionException e) {
                listener.cancel(true);
                throw new HttpRequestException(e.getLocalizedMessage(), e);
            }
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
            return "RequestWrapper{" +
                    "uri=" + uri +
                    ", request=" + request.toString() +
                    ", requestBody=" + requestBody +
                    ", maxLength=" + maxLength +
                    ", queryParams=" + request.getParams() +
                    '}';
        }
    }

    @Setter
    @Getter
    private static class ResponseWrapper implements IResponse {
        private String contentAsString;
        private int httpCode;
        private String httpCodeMessage;
        private Map<String, String> httpHeader;

        @Override
        public String toString() {
            return "ResponseWrapper{" +
                    "httpCode=" + httpCode +
                    ", httpCodeMessage=" + httpCodeMessage +
                    ", responseBody=" + contentAsString +
                    ", httpHeader=" + httpHeader.toString() +
                    '}';
        }
    }

    private static Map<String, String> convertHeaderToMap(HttpFields headers) {
        Map<String, String> httpHeader = new HashMap<>();
        for (HttpField header : headers) {
            httpHeader.put(header.getName(), header.getValue());
        }
        return httpHeader;
    }
}
