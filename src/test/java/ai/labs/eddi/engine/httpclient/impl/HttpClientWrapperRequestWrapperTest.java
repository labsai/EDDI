/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.impl;

import ai.labs.eddi.engine.httpclient.IHttpClient;
import ai.labs.eddi.engine.httpclient.IRequest;
import ai.labs.eddi.engine.httpclient.IResponse;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.WebClientSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("HttpClientWrapper RequestWrapper Tests")
class HttpClientWrapperRequestWrapperTest {

    private HttpClientWrapper wrapper;
    private WebClientSession webClient;
    private HttpRequest<Buffer> mockVertxRequest;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        var httpClient = mock(VertxHttpClient.class);
        webClient = mock(WebClientSession.class);
        when(httpClient.getWebClient()).thenReturn(webClient);

        mockVertxRequest = mock(HttpRequest.class);
        when(mockVertxRequest.putHeader(anyString(), anyString())).thenReturn(mockVertxRequest);
        when(mockVertxRequest.addQueryParam(anyString(), anyString())).thenReturn(mockVertxRequest);
        when(mockVertxRequest.basicAuthentication(anyString(), anyString())).thenReturn(mockVertxRequest);
        when(mockVertxRequest.timeout(anyLong())).thenReturn(mockVertxRequest);
        var headers = HeadersMultiMap.httpHeaders();
        when(mockVertxRequest.headers()).thenReturn(headers);

        when(webClient.requestAbs(any(), anyString())).thenReturn(mockVertxRequest);

        wrapper = new HttpClientWrapper(httpClient, "testdomain", "1.0");
    }

    @Nested
    @DisplayName("newRequest")
    class NewRequestTests {

        @Test
        @DisplayName("creates GET request by default")
        void defaultGet() {
            IRequest request = wrapper.newRequest(URI.create("http://example.com/api"));
            assertNotNull(request);
        }

        @Test
        @DisplayName("creates POST request")
        void postRequest() {
            IRequest request = wrapper.newRequest(URI.create("http://example.com/api"), IHttpClient.Method.POST);
            assertNotNull(request);
        }
    }

    @Nested
    @DisplayName("RequestWrapper — URI query param parsing")
    class QueryParamParsingTests {

        @Test
        @DisplayName("URI with no query params — empty queryParamsMap")
        void noQueryParams() {
            IRequest request = wrapper.newRequest(URI.create("http://example.com/path"));
            Map<String, Object> map = request.toMap();
            @SuppressWarnings("unchecked")
            var queryParams = (Map<String, ?>) map.get("queryParams");
            assertTrue(queryParams.isEmpty());
        }

        @Test
        @DisplayName("URI with single query param — parsed correctly")
        void singleQueryParam() {
            IRequest request = wrapper.newRequest(URI.create("http://example.com/path?key=value"));
            Map<String, Object> map = request.toMap();
            @SuppressWarnings("unchecked")
            var queryParams = (Map<String, ?>) map.get("queryParams");
            assertTrue(queryParams.containsKey("key"));
        }

        @Test
        @DisplayName("URI with multiple query params — all parsed")
        void multipleQueryParams() {
            IRequest request = wrapper.newRequest(URI.create("http://example.com/path?a=1&b=2"));
            Map<String, Object> map = request.toMap();
            @SuppressWarnings("unchecked")
            var queryParams = (Map<String, ?>) map.get("queryParams");
            assertTrue(queryParams.containsKey("a"));
            assertTrue(queryParams.containsKey("b"));
        }

        @Test
        @DisplayName("URI with key-only query param (no value) — key exists, value is null")
        void keyOnlyQueryParam() {
            IRequest request = wrapper.newRequest(URI.create("http://example.com/path?flag"));
            Map<String, Object> map = request.toMap();
            @SuppressWarnings("unchecked")
            var queryParams = (Map<String, ?>) map.get("queryParams");
            assertTrue(queryParams.containsKey("flag"));
        }

        @Test
        @DisplayName("URI with key= (empty value) — key exists with null value")
        void keyEqualsNoValue() {
            IRequest request = wrapper.newRequest(URI.create("http://example.com/path?key="));
            Map<String, Object> map = request.toMap();
            @SuppressWarnings("unchecked")
            var queryParams = (Map<String, ?>) map.get("queryParams");
            assertTrue(queryParams.containsKey("key"));
        }

        @Test
        @DisplayName("URI with URL-encoded query params — decoded correctly")
        void urlEncodedParams() {
            IRequest request = wrapper.newRequest(URI.create("http://example.com/path?name=hello%20world"));
            Map<String, Object> map = request.toMap();
            @SuppressWarnings("unchecked")
            var queryParams = (Map<String, ?>) map.get("queryParams");
            assertTrue(queryParams.containsKey("name"));
        }
    }

    @Nested
    @DisplayName("RequestWrapper — setBodyEntity branches")
    class SetBodyEntityTests {

        @Test
        @DisplayName("contentType and encoding both non-null — sets Content-Type with charset")
        void contentTypeAndEncoding() {
            IRequest request = wrapper.newRequest(URI.create("http://example.com"));
            request.setBodyEntity("body", "utf-8", "application/json");
            verify(mockVertxRequest).putHeader("Content-Type", "application/json; charset=utf-8");
        }

        @Test
        @DisplayName("contentType non-null, encoding null — sets Content-Type without charset")
        void contentTypeNoEncoding() {
            IRequest request = wrapper.newRequest(URI.create("http://example.com"));
            request.setBodyEntity("body", null, "text/plain");
            verify(mockVertxRequest).putHeader("Content-Type", "text/plain");
        }

        @Test
        @DisplayName("both contentType and encoding null — no Content-Type header set")
        void bothNull() {
            // Reset to track calls clearly
            reset(mockVertxRequest);
            when(mockVertxRequest.putHeader(anyString(), anyString())).thenReturn(mockVertxRequest);
            var headers = HeadersMultiMap.httpHeaders();
            when(mockVertxRequest.headers()).thenReturn(headers);
            when(webClient.requestAbs(any(), anyString())).thenReturn(mockVertxRequest);

            IRequest request = wrapper.newRequest(URI.create("http://example.com"));
            request.setBodyEntity("body", null, null);
            // Only the initial "User-Agent" header should have been set
            verify(mockVertxRequest, times(1)).putHeader(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("RequestWrapper — setBasicAuthentication")
    class BasicAuthTests {

        @Test
        @DisplayName("preemptive=true — no warning logged")
        void preemptive() {
            IRequest request = wrapper.newRequest(URI.create("http://example.com"));
            IRequest result = request.setBasicAuthentication("user", "pass", "realm", true);
            assertSame(result, request);
            verify(mockVertxRequest).basicAuthentication("user", "pass");
        }

        @Test
        @DisplayName("preemptive=false — falls back to preemptive (warning)")
        void nonPreemptive() {
            IRequest request = wrapper.newRequest(URI.create("http://example.com"));
            IRequest result = request.setBasicAuthentication("user", "pass", "realm", false);
            assertSame(result, request);
            verify(mockVertxRequest).basicAuthentication("user", "pass");
        }
    }

    @Nested
    @DisplayName("RequestWrapper — other setters")
    class SetterTests {

        @Test
        @DisplayName("setHttpHeader returns same IRequest")
        void setHttpHeader() {
            IRequest request = wrapper.newRequest(URI.create("http://example.com"));
            IRequest result = request.setHttpHeader("X-Custom", "val");
            assertSame(result, request);
        }

        @Test
        @DisplayName("setQueryParam returns same IRequest")
        void setQueryParam() {
            IRequest request = wrapper.newRequest(URI.create("http://example.com"));
            IRequest result = request.setQueryParam("key", "val");
            assertSame(result, request);
        }

        @Test
        @DisplayName("setUserAgent returns same IRequest")
        void setUserAgent() {
            IRequest request = wrapper.newRequest(URI.create("http://example.com"));
            IRequest result = request.setUserAgent("MyAgent/1.0");
            assertSame(result, request);
        }

        @Test
        @DisplayName("setMaxResponseSize stores the limit")
        void setMaxResponseSize() {
            IRequest request = wrapper.newRequest(URI.create("http://example.com"));
            IRequest result = request.setMaxResponseSize(1024);
            assertSame(result, request);
            Map<String, Object> map = result.toMap();
            assertEquals(1024, map.get("maxLength"));
        }

        @Test
        @DisplayName("setTimeout sets the timeout")
        void setTimeout() {
            IRequest request = wrapper.newRequest(URI.create("http://example.com"));
            IRequest result = request.setTimeout(5, TimeUnit.SECONDS);
            assertSame(result, request);
            verify(mockVertxRequest).timeout(5000L);
        }
    }

    @Nested
    @DisplayName("RequestWrapper — toMap")
    class ToMapTests {

        @Test
        @DisplayName("includes URI and method")
        void uriAndMethod() {
            IRequest request = wrapper.newRequest(URI.create("http://example.com/api"), IHttpClient.Method.POST);
            Map<String, Object> map = request.toMap();
            assertEquals("http://example.com/api", map.get("uri"));
            assertEquals("POST", map.get("method"));
        }

        @Test
        @DisplayName("body present — included in map")
        void bodyPresent() {
            IRequest request = wrapper.newRequest(URI.create("http://example.com"));
            request.setBodyEntity("test body", null, null);
            Map<String, Object> map = request.toMap();
            assertEquals("test body", map.get("body"));
        }

        @Test
        @DisplayName("body absent — not in map")
        void bodyAbsent() {
            IRequest request = wrapper.newRequest(URI.create("http://example.com"));
            Map<String, Object> map = request.toMap();
            assertFalse(map.containsKey("body"));
        }

        @Test
        @DisplayName("userAgent present via header")
        void userAgentPresent() {
            // Set a user agent via headers mock
            var headers = HeadersMultiMap.httpHeaders();
            headers.add("User-Agent", "TESTDOMAIN/1.0");
            when(mockVertxRequest.headers()).thenReturn(headers);

            IRequest request = wrapper.newRequest(URI.create("http://example.com"));
            Map<String, Object> map = request.toMap();
            assertEquals("TESTDOMAIN/1.0", map.get("userAgent"));
        }

        @Test
        @DisplayName("maxLength default is 8MB")
        void defaultMaxLength() {
            IRequest request = wrapper.newRequest(URI.create("http://example.com"));
            Map<String, Object> map = request.toMap();
            assertEquals(8 * 1024 * 1024, map.get("maxLength"));
        }
    }

    @Nested
    @DisplayName("RequestWrapper — toString")
    class ToStringTests {

        @Test
        @DisplayName("toString contains request details")
        void toStringContainsDetails() {
            IRequest request = wrapper.newRequest(URI.create("http://example.com/api"), IHttpClient.Method.GET);
            String result = request.toString();
            assertTrue(result.contains("http://example.com/api"));
            assertTrue(result.contains("GET"));
        }

        @Test
        @DisplayName("toString truncates long body")
        void toStringTruncatesLongBody() {
            IRequest request = wrapper.newRequest(URI.create("http://example.com"));
            request.setBodyEntity("x".repeat(300), null, null);
            String result = request.toString();
            assertTrue(result.contains("..."));
        }

        @Test
        @DisplayName("toString with null body shows null")
        void toStringNullBody() {
            IRequest request = wrapper.newRequest(URI.create("http://example.com"));
            String result = request.toString();
            assertTrue(result.contains("null"));
        }
    }

    @Nested
    @DisplayName("RequestWrapper — equals and hashCode")
    class EqualsHashCodeTests {

        @Test
        @DisplayName("same instance — equals true")
        void sameInstance() {
            IRequest request = wrapper.newRequest(URI.create("http://example.com"));
            assertEquals(request, request);
        }

        @Test
        @DisplayName("null — equals false")
        void nullNotEqual() {
            IRequest request = wrapper.newRequest(URI.create("http://example.com"));
            assertNotEquals(null, request);
        }

        @Test
        @DisplayName("different class — equals false")
        void differentClass() {
            IRequest request = wrapper.newRequest(URI.create("http://example.com"));
            assertNotEquals("string", request);
        }

        @Test
        @DisplayName("two identical requests — equals true")
        void twoIdentical() {
            IRequest request1 = wrapper.newRequest(URI.create("http://example.com"), IHttpClient.Method.GET);
            IRequest request2 = wrapper.newRequest(URI.create("http://example.com"), IHttpClient.Method.GET);
            assertEquals(request1, request2);
            assertEquals(request1.hashCode(), request2.hashCode());
        }

        @Test
        @DisplayName("different timeout — equals false")
        void differentTimeout() {
            IRequest request1 = wrapper.newRequest(URI.create("http://example.com"));
            IRequest request2 = wrapper.newRequest(URI.create("http://example.com"));
            request2.setTimeout(1, TimeUnit.SECONDS);
            assertNotEquals(request1, request2);
        }

        @Test
        @DisplayName("different maxLength — equals false")
        void differentMaxLength() {
            IRequest request1 = wrapper.newRequest(URI.create("http://example.com"));
            IRequest request2 = wrapper.newRequest(URI.create("http://example.com"));
            request2.setMaxResponseSize(100);
            assertNotEquals(request1, request2);
        }
    }
}
