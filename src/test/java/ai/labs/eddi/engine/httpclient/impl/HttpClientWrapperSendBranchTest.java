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
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link HttpClientWrapper} send / doSend / handleResponse branches.
 * <p>
 * The response body now streams into a
 * {@link HttpClientWrapper.CappedBufferSink} (size-capped) rather than being
 * buffered whole and measured afterwards. The one part that needs a live Vert.x
 * exchange — {@code executePipedSend}, which wires the pipe and drives the
 * transport — is stubbed; everything the wrapper decides ({@code doSend} body
 * encoding, {@code handleResponse} Content-Length / size-cap / body-from-sink /
 * header handling, the {@code send(listener)} 503 mapping, the sync
 * {@code send()} exception wrapping) is the real code. The stub simulates the
 * transport by writing the body into the sink and completing the response
 * handler.
 */
@DisplayName("HttpClientWrapper — Send / HandleResponse Branch Coverage")
class HttpClientWrapperSendBranchTest {

    private HttpClientWrapper wrapper;
    private HttpRequest<Buffer> mockVertxRequest;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        var httpClient = mock(VertxHttpClient.class);
        WebClient webClient = mock(WebClient.class);
        when(httpClient.getWebClient()).thenReturn(webClient);

        mockVertxRequest = mock(HttpRequest.class);
        when(mockVertxRequest.putHeader(anyString(), anyString())).thenReturn(mockVertxRequest);
        when(mockVertxRequest.addQueryParam(anyString(), anyString())).thenReturn(mockVertxRequest);
        when(mockVertxRequest.timeout(anyLong())).thenReturn(mockVertxRequest);
        var headers = HeadersMultiMap.httpHeaders();
        when(mockVertxRequest.headers()).thenReturn(headers);
        when(webClient.requestAbs(any(), anyString())).thenReturn(mockVertxRequest);

        // A spy so the transport seam (executePipedSend) can be stubbed while the
        // wrapper's own send/handleResponse logic runs for real.
        wrapper = spy(new HttpClientWrapper(httpClient, "testdomain", "1.0"));
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<Void> voidResponse(int status, String message, String contentLength) {
        HttpResponse<Void> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.statusMessage()).thenReturn(message);
        when(response.getHeader("Content-Length")).thenReturn(contentLength);
        when(response.headers()).thenReturn(HeadersMultiMap.httpHeaders());
        return response;
    }

    /**
     * Stub the transport to write {@code bodyToSink} (if any) into the sink and
     * complete with {@code response}, mirroring a successful streamed body.
     */
    @SuppressWarnings("unchecked")
    private void stubTransportSuccess(HttpResponse<Void> response, String bodyToSink) {
        doAnswer(inv -> {
            HttpClientWrapper.CappedBufferSink sink = inv.getArgument(2);
            Handler<AsyncResult<HttpResponse<Void>>> responseHandler = inv.getArgument(3);
            if (bodyToSink != null) {
                sink.write(Buffer.buffer(bodyToSink));
            }
            responseHandler.handle(Future.succeededFuture(response));
            return null;
        }).when(wrapper).executePipedSend(any(), any(), any(), any());
    }

    @SuppressWarnings("unchecked")
    private void stubTransportFailure(Throwable cause) {
        doAnswer(inv -> {
            Handler<AsyncResult<HttpResponse<Void>>> responseHandler = inv.getArgument(3);
            responseHandler.handle(Future.failedFuture(cause));
            return null;
        }).when(wrapper).executePipedSend(any(), any(), any(), any());
    }

    @Nested
    @DisplayName("send(ICompleteListener)")
    class AsyncSendTests {

        @Test
        @DisplayName("success path — calls completeListener.onComplete with response and streamed body")
        void asyncSend_success() throws Exception {
            stubTransportSuccess(voidResponse(200, "OK", null), "hello");

            IRequest request = wrapper.newRequest(URI.create("http://example.com"));
            ICompleteListener listener = mock(ICompleteListener.class);
            request.send(listener);

            verify(listener).onComplete(argThat(response -> response.getHttpCode() == 200 && "hello".equals(response.getContentAsString())));
        }

        @Test
        @DisplayName("failure path — calls completeListener with synthetic 503")
        void asyncSend_failure() throws Exception {
            stubTransportFailure(new RuntimeException("connection refused"));

            IRequest request = wrapper.newRequest(URI.create("http://example.com"));
            ICompleteListener listener = mock(ICompleteListener.class);
            request.send(listener);

            verify(listener).onComplete(argThat(response -> response.getHttpCode() == 503));
        }

        @Test
        @DisplayName("success but listener throws — logs error silently")
        void asyncSend_listenerThrows() {
            stubTransportSuccess(voidResponse(200, "OK", null), "hello");

            IRequest request = wrapper.newRequest(URI.create("http://example.com"));
            ICompleteListener listener = response -> {
                throw new IResponse.HttpResponseException("listener error");
            };

            assertDoesNotThrow(() -> request.send(listener));
        }

        @Test
        @DisplayName("failure + listener throws HttpResponseException — logged silently")
        void asyncSend_failureListenerThrows() {
            stubTransportFailure(new RuntimeException("network error"));

            IRequest request = wrapper.newRequest(URI.create("http://example.com"));
            ICompleteListener listener = response -> {
                throw new IResponse.HttpResponseException("listener error on 503");
            };

            assertDoesNotThrow(() -> request.send(listener));
        }
    }

    @Nested
    @DisplayName("handleResponse branches")
    class HandleResponseTests {

        @Test
        @DisplayName("Content-Length exceeds max → fails, listener gets 503")
        void contentLengthExceedsMax() throws Exception {
            stubTransportSuccess(voidResponse(200, "OK", "999999999"), null);

            IRequest request = wrapper.newRequest(URI.create("http://example.com"));
            request.setMaxResponseSize(1024);

            ICompleteListener listener = mock(ICompleteListener.class);
            request.send(listener);

            verify(listener).onComplete(argThat(response -> response.getHttpCode() == 503));
        }

        @Test
        @DisplayName("body exceeds max (sink fails mid-stream) → fails, listener gets 503")
        void bodyExceedsMax() throws Exception {
            // The real sink raises ResponseSizeExceededException while streaming; the
            // transport surfaces it as a failed send.
            stubTransportFailure(new HttpClientWrapper.ResponseSizeExceededException(
                    "Response body exceeds maximum allowed length 1024"));

            IRequest request = wrapper.newRequest(URI.create("http://example.com"));
            request.setMaxResponseSize(1024);

            ICompleteListener listener = mock(ICompleteListener.class);
            request.send(listener);

            verify(listener).onComplete(argThat(response -> response.getHttpCode() == 503));
        }

        @Test
        @DisplayName("the sink actually caps a body larger than maxResponseSize")
        void sinkCapsOversizeBody() {
            // A direct check that the size cap is real, independent of the transport
            // stub: writing past the cap fails the sink.
            HttpClientWrapper.CappedBufferSink sink = new HttpClientWrapper.CappedBufferSink(1024);
            assertTrue(sink.write(Buffer.buffer("x".repeat(1024))).succeeded());
            assertTrue(sink.write(Buffer.buffer("y")).failed(), "one byte past the cap must fail the sink");
            assertEquals(1024, sink.captured().length(), "nothing past the cap is retained");
        }

        @Test
        @DisplayName("null body → empty content string")
        void nullBody() throws Exception {
            stubTransportSuccess(voidResponse(204, "No Content", null), null);

            IRequest request = wrapper.newRequest(URI.create("http://example.com"));
            ICompleteListener listener = mock(ICompleteListener.class);
            request.send(listener);

            verify(listener).onComplete(argThat(response -> response.getHttpCode() == 204 && "".equals(response.getContentAsString())));
        }

        @Test
        @DisplayName("empty body (nothing written to sink) → empty content string")
        void emptyBody() throws Exception {
            stubTransportSuccess(voidResponse(200, "OK", null), "");

            IRequest request = wrapper.newRequest(URI.create("http://example.com"));
            ICompleteListener listener = mock(ICompleteListener.class);
            request.send(listener);

            verify(listener).onComplete(argThat(response -> "".equals(response.getContentAsString())));
        }

        @Test
        @DisplayName("invalid Content-Length header (NumberFormatException) — ignored, streams normally")
        void invalidContentLengthHeader() throws Exception {
            stubTransportSuccess(voidResponse(200, "OK", "not-a-number"), "ok");

            IRequest request = wrapper.newRequest(URI.create("http://example.com"));
            ICompleteListener listener = mock(ICompleteListener.class);
            request.send(listener);

            verify(listener).onComplete(argThat(response -> response.getHttpCode() == 200 && "ok".equals(response.getContentAsString())));
        }
    }

    @Nested
    @DisplayName("doSend body encoding branches")
    class DoSendBodyTests {

        @Test
        @DisplayName("body with encoding — passes an encoded Buffer to the transport")
        void bodyWithEncoding() throws Exception {
            stubTransportSuccess(voidResponse(200, "OK", null), "ok");

            IRequest request = wrapper.newRequest(URI.create("http://example.com"), IHttpClient.Method.POST);
            request.setBodyEntity("{}", "UTF-8", "application/json");

            ICompleteListener listener = mock(ICompleteListener.class);
            request.send(listener);

            ArgumentCaptor<Buffer> body = ArgumentCaptor.forClass(Buffer.class);
            verify(wrapper).executePipedSend(any(), body.capture(), any(), any());
            assertNotNull(body.getValue(), "a request body must be sent as a Buffer");
            assertEquals("{}", body.getValue().toString());
            verify(listener).onComplete(any());
        }

        @Test
        @DisplayName("body without encoding — passes a Buffer to the transport")
        void bodyWithoutEncoding() throws Exception {
            stubTransportSuccess(voidResponse(200, "OK", null), "ok");

            IRequest request = wrapper.newRequest(URI.create("http://example.com"), IHttpClient.Method.POST);
            request.setBodyEntity("{}", null, null);

            ICompleteListener listener = mock(ICompleteListener.class);
            request.send(listener);

            ArgumentCaptor<Buffer> body = ArgumentCaptor.forClass(Buffer.class);
            verify(wrapper).executePipedSend(any(), body.capture(), any(), any());
            assertEquals("{}", body.getValue().toString());
        }

        @Test
        @DisplayName("invalid encoding — never reaches the transport, listener gets 503")
        void invalidEncoding() throws Exception {
            // The bad encoding fails in doSend before executePipedSend is ever called.
            IRequest request = wrapper.newRequest(URI.create("http://example.com"), IHttpClient.Method.POST);
            request.setBodyEntity("{}", "NO-SUCH-CHARSET", "application/json");

            ICompleteListener listener = mock(ICompleteListener.class);
            request.send(listener);

            verify(wrapper, never()).executePipedSend(any(), any(), any(), any());
            verify(listener).onComplete(argThat(response -> response.getHttpCode() == 503));
        }
    }

    @Nested
    @DisplayName("Synchronous send() exception paths")
    class SyncSendExceptionTests {

        @Test
        @DisplayName("send() wraps timeout in HttpRequestException")
        void syncSend_timeout() {
            // The transport never completes the handler, so the sync future times out.
            doNothing().when(wrapper).executePipedSend(any(), any(), any(), any());

            IRequest request = wrapper.newRequest(URI.create("http://example.com"));
            request.setTimeout(1, TimeUnit.MILLISECONDS);

            assertThrows(IRequest.HttpRequestException.class, request::send);
        }

        @Test
        @DisplayName("send() wraps the failure cause in HttpRequestException")
        void syncSend_executionException() {
            stubTransportFailure(new RuntimeException("connect refused"));

            IRequest request = wrapper.newRequest(URI.create("http://example.com"));

            var ex = assertThrows(IRequest.HttpRequestException.class, request::send);
            assertTrue(ex.getMessage().contains("connect refused"));
        }
    }
}
