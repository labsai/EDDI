/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.impl;

import ai.labs.eddi.engine.httpclient.ICompleteListener;
import ai.labs.eddi.engine.httpclient.IHttpClient;
import ai.labs.eddi.engine.httpclient.IRequest;
import ai.labs.eddi.engine.httpclient.IResponse;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClientSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link HttpClientWrapper.RequestWrapper} send/doSend/handleResponse
 * branches that are not covered by existing tests.
 */
@DisplayName("HttpClientWrapper — Send / HandleResponse Branch Coverage")
class HttpClientWrapperSendBranchTest {

    private HttpClientWrapper wrapper;
    private WebClientSession webClient;
    private HttpRequest<Buffer> mockVertxRequest;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        var httpClient = mock(VertxHttpClient.class);
        webClient = mock(WebClientSession.class);
        when(httpClient.getWebClient()).thenReturn(webClient);

        mockVertxRequest = mock(HttpRequest.class);
        when(mockVertxRequest.putHeader(anyString(), anyString())).thenReturn(mockVertxRequest);
        when(mockVertxRequest.addQueryParam(anyString(), anyString())).thenReturn(mockVertxRequest);
        when(mockVertxRequest.timeout(anyLong())).thenReturn(mockVertxRequest);
        var headers = HeadersMultiMap.httpHeaders();
        when(mockVertxRequest.headers()).thenReturn(headers);
        when(webClient.requestAbs(any(), anyString())).thenReturn(mockVertxRequest);

        wrapper = new HttpClientWrapper(httpClient, "testdomain", "1.0");
    }

    // ==================== Async send(ICompleteListener) — success path
    // ====================

    @Nested
    @DisplayName("send(ICompleteListener)")
    class AsyncSendTests {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("success path — calls completeListener.onComplete with response")
        void asyncSend_success() throws Exception {
            HttpResponse<Buffer> vertxResponse = mock(HttpResponse.class);
            when(vertxResponse.statusCode()).thenReturn(200);
            when(vertxResponse.statusMessage()).thenReturn("OK");
            when(vertxResponse.getHeader("Content-Length")).thenReturn(null);
            when(vertxResponse.body()).thenReturn(Buffer.buffer("hello"));
            when(vertxResponse.headers()).thenReturn(HeadersMultiMap.httpHeaders());

            doAnswer(inv -> {
                Handler<AsyncResult<HttpResponse<Buffer>>> handler = inv.getArgument(0);
                handler.handle(Future.succeededFuture(vertxResponse));
                return null;
            }).when(mockVertxRequest).send(any(Handler.class));

            IRequest request = wrapper.newRequest(URI.create("http://example.com"));
            ICompleteListener listener = mock(ICompleteListener.class);
            request.send(listener);

            verify(listener).onComplete(argThat(response -> response.getHttpCode() == 200 && "hello".equals(response.getContentAsString())));
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("failure path — calls completeListener with synthetic 503")
        void asyncSend_failure() throws Exception {
            doAnswer(inv -> {
                Handler<AsyncResult<HttpResponse<Buffer>>> handler = inv.getArgument(0);
                handler.handle(Future.failedFuture(new RuntimeException("connection refused")));
                return null;
            }).when(mockVertxRequest).send(any(Handler.class));

            IRequest request = wrapper.newRequest(URI.create("http://example.com"));
            ICompleteListener listener = mock(ICompleteListener.class);
            request.send(listener);

            verify(listener).onComplete(argThat(response -> response.getHttpCode() == 503));
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("success but listener throws — logs error silently")
        void asyncSend_listenerThrows() throws Exception {
            HttpResponse<Buffer> vertxResponse = mock(HttpResponse.class);
            when(vertxResponse.statusCode()).thenReturn(200);
            when(vertxResponse.statusMessage()).thenReturn("OK");
            when(vertxResponse.getHeader("Content-Length")).thenReturn(null);
            when(vertxResponse.body()).thenReturn(Buffer.buffer("hello"));
            when(vertxResponse.headers()).thenReturn(HeadersMultiMap.httpHeaders());

            doAnswer(inv -> {
                Handler<AsyncResult<HttpResponse<Buffer>>> handler = inv.getArgument(0);
                handler.handle(Future.succeededFuture(vertxResponse));
                return null;
            }).when(mockVertxRequest).send(any(Handler.class));

            IRequest request = wrapper.newRequest(URI.create("http://example.com"));
            ICompleteListener listener = response -> {
                throw new IResponse.HttpResponseException("listener error");
            };

            // Should not throw — error is logged
            assertDoesNotThrow(() -> request.send(listener));
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("failure + listener throws HttpResponseException — logged silently")
        void asyncSend_failureListenerThrows() throws Exception {
            doAnswer(inv -> {
                Handler<AsyncResult<HttpResponse<Buffer>>> handler = inv.getArgument(0);
                handler.handle(Future.failedFuture(new RuntimeException("network error")));
                return null;
            }).when(mockVertxRequest).send(any(Handler.class));

            IRequest request = wrapper.newRequest(URI.create("http://example.com"));
            ICompleteListener listener = response -> {
                throw new IResponse.HttpResponseException("listener error on 503");
            };

            assertDoesNotThrow(() -> request.send(listener));
        }
    }

    // ==================== handleResponse branches ====================

    @Nested
    @DisplayName("handleResponse branches")
    class HandleResponseTests {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("Content-Length exceeds max → fails with HttpResponseException")
        void contentLengthExceedsMax() throws Exception {
            HttpResponse<Buffer> vertxResponse = mock(HttpResponse.class);
            when(vertxResponse.getHeader("Content-Length")).thenReturn("999999999");
            when(vertxResponse.statusCode()).thenReturn(200);
            when(vertxResponse.statusMessage()).thenReturn("OK");
            when(vertxResponse.headers()).thenReturn(HeadersMultiMap.httpHeaders());

            doAnswer(inv -> {
                Handler<AsyncResult<HttpResponse<Buffer>>> handler = inv.getArgument(0);
                handler.handle(Future.succeededFuture(vertxResponse));
                return null;
            }).when(mockVertxRequest).send(any(Handler.class));

            IRequest request = wrapper.newRequest(URI.create("http://example.com"));
            request.setMaxResponseSize(1024);

            ICompleteListener listener = mock(ICompleteListener.class);
            request.send(listener);

            // The listener should get a 503 due to the response being rejected
            verify(listener).onComplete(argThat(response -> response.getHttpCode() == 503));
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("Body exceeds max → fails with HttpResponseException")
        void bodyExceedsMax() throws Exception {
            HttpResponse<Buffer> vertxResponse = mock(HttpResponse.class);
            when(vertxResponse.getHeader("Content-Length")).thenReturn(null);
            when(vertxResponse.body()).thenReturn(Buffer.buffer("x".repeat(2048)));
            when(vertxResponse.statusCode()).thenReturn(200);
            when(vertxResponse.statusMessage()).thenReturn("OK");
            when(vertxResponse.headers()).thenReturn(HeadersMultiMap.httpHeaders());

            doAnswer(inv -> {
                Handler<AsyncResult<HttpResponse<Buffer>>> handler = inv.getArgument(0);
                handler.handle(Future.succeededFuture(vertxResponse));
                return null;
            }).when(mockVertxRequest).send(any(Handler.class));

            IRequest request = wrapper.newRequest(URI.create("http://example.com"));
            request.setMaxResponseSize(1024);

            ICompleteListener listener = mock(ICompleteListener.class);
            request.send(listener);

            verify(listener).onComplete(argThat(response -> response.getHttpCode() == 503));
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("null body → empty content string")
        void nullBody() throws Exception {
            HttpResponse<Buffer> vertxResponse = mock(HttpResponse.class);
            when(vertxResponse.getHeader("Content-Length")).thenReturn(null);
            when(vertxResponse.body()).thenReturn(null);
            when(vertxResponse.statusCode()).thenReturn(204);
            when(vertxResponse.statusMessage()).thenReturn("No Content");
            when(vertxResponse.headers()).thenReturn(HeadersMultiMap.httpHeaders());

            doAnswer(inv -> {
                Handler<AsyncResult<HttpResponse<Buffer>>> handler = inv.getArgument(0);
                handler.handle(Future.succeededFuture(vertxResponse));
                return null;
            }).when(mockVertxRequest).send(any(Handler.class));

            IRequest request = wrapper.newRequest(URI.create("http://example.com"));
            ICompleteListener listener = mock(ICompleteListener.class);
            request.send(listener);

            verify(listener).onComplete(argThat(response -> response.getHttpCode() == 204 && "".equals(response.getContentAsString())));
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("empty body (length 0) → empty content string")
        void emptyBody() throws Exception {
            HttpResponse<Buffer> vertxResponse = mock(HttpResponse.class);
            when(vertxResponse.getHeader("Content-Length")).thenReturn(null);
            when(vertxResponse.body()).thenReturn(Buffer.buffer());
            when(vertxResponse.statusCode()).thenReturn(200);
            when(vertxResponse.statusMessage()).thenReturn("OK");
            when(vertxResponse.headers()).thenReturn(HeadersMultiMap.httpHeaders());

            doAnswer(inv -> {
                Handler<AsyncResult<HttpResponse<Buffer>>> handler = inv.getArgument(0);
                handler.handle(Future.succeededFuture(vertxResponse));
                return null;
            }).when(mockVertxRequest).send(any(Handler.class));

            IRequest request = wrapper.newRequest(URI.create("http://example.com"));
            ICompleteListener listener = mock(ICompleteListener.class);
            request.send(listener);

            verify(listener).onComplete(argThat(response -> "".equals(response.getContentAsString())));
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("invalid Content-Length header (NumberFormatException) — ignored, continues normally")
        void invalidContentLengthHeader() throws Exception {
            HttpResponse<Buffer> vertxResponse = mock(HttpResponse.class);
            when(vertxResponse.getHeader("Content-Length")).thenReturn("not-a-number");
            when(vertxResponse.body()).thenReturn(Buffer.buffer("ok"));
            when(vertxResponse.statusCode()).thenReturn(200);
            when(vertxResponse.statusMessage()).thenReturn("OK");
            when(vertxResponse.headers()).thenReturn(HeadersMultiMap.httpHeaders());

            doAnswer(inv -> {
                Handler<AsyncResult<HttpResponse<Buffer>>> handler = inv.getArgument(0);
                handler.handle(Future.succeededFuture(vertxResponse));
                return null;
            }).when(mockVertxRequest).send(any(Handler.class));

            IRequest request = wrapper.newRequest(URI.create("http://example.com"));
            ICompleteListener listener = mock(ICompleteListener.class);
            request.send(listener);

            verify(listener).onComplete(argThat(response -> response.getHttpCode() == 200 && "ok".equals(response.getContentAsString())));
        }
    }

    // ==================== doSend with body + encoding ====================

    @Nested
    @DisplayName("doSend body encoding branches")
    class DoSendBodyTests {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("body with encoding — uses sendBuffer with encoding")
        void bodyWithEncoding() throws Exception {
            HttpResponse<Buffer> vertxResponse = mock(HttpResponse.class);
            when(vertxResponse.statusCode()).thenReturn(200);
            when(vertxResponse.statusMessage()).thenReturn("OK");
            when(vertxResponse.getHeader("Content-Length")).thenReturn(null);
            when(vertxResponse.body()).thenReturn(Buffer.buffer("ok"));
            when(vertxResponse.headers()).thenReturn(HeadersMultiMap.httpHeaders());

            doAnswer(inv -> {
                Handler<AsyncResult<HttpResponse<Buffer>>> handler = inv.getArgument(1);
                handler.handle(Future.succeededFuture(vertxResponse));
                return null;
            }).when(mockVertxRequest).sendBuffer(any(Buffer.class), any(Handler.class));

            IRequest request = wrapper.newRequest(URI.create("http://example.com"), IHttpClient.Method.POST);
            request.setBodyEntity("{}", "UTF-8", "application/json");

            ICompleteListener listener = mock(ICompleteListener.class);
            request.send(listener);

            verify(mockVertxRequest).sendBuffer(any(Buffer.class), any(Handler.class));
            verify(listener).onComplete(any());
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("body without encoding — uses sendBuffer without encoding")
        void bodyWithoutEncoding() throws Exception {
            HttpResponse<Buffer> vertxResponse = mock(HttpResponse.class);
            when(vertxResponse.statusCode()).thenReturn(200);
            when(vertxResponse.statusMessage()).thenReturn("OK");
            when(vertxResponse.getHeader("Content-Length")).thenReturn(null);
            when(vertxResponse.body()).thenReturn(Buffer.buffer("ok"));
            when(vertxResponse.headers()).thenReturn(HeadersMultiMap.httpHeaders());

            doAnswer(inv -> {
                Handler<AsyncResult<HttpResponse<Buffer>>> handler = inv.getArgument(1);
                handler.handle(Future.succeededFuture(vertxResponse));
                return null;
            }).when(mockVertxRequest).sendBuffer(any(Buffer.class), any(Handler.class));

            IRequest request = wrapper.newRequest(URI.create("http://example.com"), IHttpClient.Method.POST);
            request.setBodyEntity("{}", null, null);

            ICompleteListener listener = mock(ICompleteListener.class);
            request.send(listener);

            verify(mockVertxRequest).sendBuffer(any(Buffer.class), any(Handler.class));
        }
    }

    // ==================== Synchronous send() exception paths ====================

    @Nested
    @DisplayName("Synchronous send() exception paths")
    class SyncSendExceptionTests {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("send() wraps timeout in HttpRequestException")
        void syncSend_timeout() throws Exception {
            // Don't trigger the handler — future will time out
            IRequest request = wrapper.newRequest(URI.create("http://example.com"));
            request.setTimeout(1, TimeUnit.MILLISECONDS);

            assertThrows(IRequest.HttpRequestException.class, () -> request.send());
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("send() wraps ExecutionException cause in HttpRequestException")
        void syncSend_executionException() throws Exception {
            doAnswer(inv -> {
                Handler<AsyncResult<HttpResponse<Buffer>>> handler = inv.getArgument(0);
                handler.handle(Future.failedFuture(new RuntimeException("connect refused")));
                return null;
            }).when(mockVertxRequest).send(any(Handler.class));

            IRequest request = wrapper.newRequest(URI.create("http://example.com"));

            var ex = assertThrows(IRequest.HttpRequestException.class, () -> request.send());
            assertTrue(ex.getMessage().contains("connect refused"));
        }
    }
}
