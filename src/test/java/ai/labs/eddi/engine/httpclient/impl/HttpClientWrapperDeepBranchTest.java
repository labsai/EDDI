/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.impl;

import ai.labs.eddi.engine.httpclient.IHttpClient;
import ai.labs.eddi.engine.httpclient.IRequest;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.WebClientSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Additional branch coverage tests for {@link HttpClientWrapper} — covers
 * branches not exercised by HttpClientWrapperRequestWrapperTest:
 * <ul>
 * <li>PUT, DELETE, PATCH methods</li>
 * <li>setBodyEntity encoding variations</li>
 * <li>truncateAndClean static method branches</li>
 * <li>convertHeaderToMap static method</li>
 * <li>toMap with different body states</li>
 * <li>toString with exact TEXT_LIMIT body</li>
 * <li>ResponseWrapper equals/hashCode/toString</li>
 * <li>URI query param edge cases (= at position 0, multiple = signs)</li>
 * </ul>
 */
@DisplayName("HttpClientWrapper — Deep Branch Coverage")
class HttpClientWrapperDeepBranchTest {

    private HttpClientWrapper wrapper;
    private WebClientSession webClient;
    private HttpRequest<Buffer> mockVertxRequest;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
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

    // ==================== HTTP Methods ====================

    @Nested
    @DisplayName("HTTP Methods")
    class HttpMethodTests {

        @Test
        @DisplayName("PUT request creates valid request")
        void putRequest() {
            IRequest request = wrapper.newRequest(URI.create("http://example.com/api"), IHttpClient.Method.PUT);
            assertNotNull(request);
            Map<String, Object> map = request.toMap();
            assertEquals("PUT", map.get("method"));
        }

        @Test
        @DisplayName("DELETE request creates valid request")
        void deleteRequest() {
            IRequest request = wrapper.newRequest(URI.create("http://example.com/api"), IHttpClient.Method.DELETE);
            assertNotNull(request);
            Map<String, Object> map = request.toMap();
            assertEquals("DELETE", map.get("method"));
        }

        @Test
        @DisplayName("PATCH request creates valid request")
        void patchRequest() {
            IRequest request = wrapper.newRequest(URI.create("http://example.com/api"), IHttpClient.Method.PATCH);
            assertNotNull(request);
            Map<String, Object> map = request.toMap();
            assertEquals("PATCH", map.get("method"));
        }

        @Test
        @DisplayName("HEAD request creates valid request")
        void headRequest() {
            IRequest request = wrapper.newRequest(URI.create("http://example.com/api"), IHttpClient.Method.HEAD);
            assertNotNull(request);
            Map<String, Object> map = request.toMap();
            assertEquals("HEAD", map.get("method"));
        }
    }

    // ==================== truncateAndClean ====================

    @Nested
    @DisplayName("truncateAndClean")
    class TruncateAndCleanTests {

        @Test
        @DisplayName("null text returns null")
        void nullText() {
            assertNull(HttpClientWrapper.truncateAndClean(null));
        }

        @Test
        @DisplayName("text shorter than limit returns cleaned text")
        void shortText() {
            assertEquals("hello world", HttpClientWrapper.truncateAndClean("hello\nworld"));
        }

        @Test
        @DisplayName("text at exact limit is not truncated")
        void exactLimit() {
            String text = "a".repeat(150);
            String result = HttpClientWrapper.truncateAndClean(text);
            assertEquals(150, result.length());
            assertFalse(result.contains("..."));
        }

        @Test
        @DisplayName("text longer than limit is truncated with ...")
        void longText() {
            String text = "a".repeat(200);
            String result = HttpClientWrapper.truncateAndClean(text);
            assertEquals(153, result.length()); // 150 + "..."
            assertTrue(result.endsWith("..."));
        }

        @Test
        @DisplayName("carriage return + newline replaced with space")
        void carriageReturnNewline() {
            assertEquals("line1 line2", HttpClientWrapper.truncateAndClean("line1\r\nline2"));
        }

        @Test
        @DisplayName("newline without CR replaced with space")
        void justNewline() {
            assertEquals("line1 line2", HttpClientWrapper.truncateAndClean("line1\nline2"));
        }

        @Test
        @DisplayName("empty string returns empty string")
        void emptyString() {
            assertEquals("", HttpClientWrapper.truncateAndClean(""));
        }
    }

    // ==================== convertHeaderToMap ====================

    @Nested
    @DisplayName("convertHeaderToMap")
    class ConvertHeaderTests {

        @Test
        @DisplayName("converts MultiMap to Map<String, String>")
        void convertsHeaders() {
            var multiMap = HeadersMultiMap.httpHeaders();
            multiMap.add("Content-Type", "application/json");
            multiMap.add("X-Custom", "value");

            Map<String, String> result = HttpClientWrapper.convertHeaderToMap(multiMap);
            assertEquals("application/json", result.get("Content-Type"));
            assertEquals("value", result.get("X-Custom"));
        }

        @Test
        @DisplayName("empty MultiMap returns empty map")
        void emptyHeaders() {
            var multiMap = HeadersMultiMap.httpHeaders();
            Map<String, String> result = HttpClientWrapper.convertHeaderToMap(multiMap);
            assertTrue(result.isEmpty());
        }
    }

    // ==================== URI query param edge cases ====================

    @Nested
    @DisplayName("URI Query Param Edge Cases")
    class QueryParamEdgeCases {

        @Test
        @DisplayName("query param with = sign at position 0 produces empty key")
        void equalsAtPositionZero() {
            IRequest request = wrapper.newRequest(URI.create("http://example.com?=value"));
            Map<String, Object> map = request.toMap();
            assertNotNull(map.get("queryParams"));
        }

        @Test
        @DisplayName("query param with multiple = signs takes first as delimiter")
        void multipleEquals() {
            IRequest request = wrapper.newRequest(URI.create("http://example.com?key=val=ue"));
            Map<String, Object> map = request.toMap();
            @SuppressWarnings("unchecked")
            var queryParams = (Map<String, ?>) map.get("queryParams");
            assertTrue(queryParams.containsKey("key"));
        }

        @Test
        @DisplayName("empty query string after ? does not add params")
        void emptyQueryAfterQuestionMark() {
            // URI with "?" but empty query — URI.getQuery() returns ""
            IRequest request = wrapper.newRequest(URI.create("http://example.com?"));
            Map<String, Object> map = request.toMap();
            @SuppressWarnings("unchecked")
            var queryParams = (Map<String, ?>) map.get("queryParams");
            assertTrue(queryParams.isEmpty());
        }
    }

    // ==================== setBodyEntity edge cases ====================

    @Nested
    @DisplayName("setBodyEntity Edge Cases")
    class SetBodyEntityEdgeCases {

        @Test
        @DisplayName("encoding non-null but contentType null → no Content-Type header")
        void encodingOnlyNoContentType() {
            reset(mockVertxRequest);
            when(mockVertxRequest.putHeader(anyString(), anyString())).thenReturn(mockVertxRequest);
            var headers = HeadersMultiMap.httpHeaders();
            when(mockVertxRequest.headers()).thenReturn(headers);
            when(webClient.requestAbs(any(), anyString())).thenReturn(mockVertxRequest);

            IRequest request = wrapper.newRequest(URI.create("http://example.com"));
            request.setBodyEntity("body", "utf-8", null);

            // With contentType=null, no Content-Type header should be set
            // Only User-Agent header from constructor
            verify(mockVertxRequest, times(1)).putHeader(anyString(), anyString());
        }

        @Test
        @DisplayName("body stored in toMap after setBodyEntity")
        void bodyInToMap() {
            IRequest request = wrapper.newRequest(URI.create("http://example.com"));
            request.setBodyEntity("{\"json\":true}", "utf-8", "application/json");
            Map<String, Object> map = request.toMap();
            assertEquals("{\"json\":true}", map.get("body"));
        }
    }

    // ==================== equals edge cases ====================

    @Nested
    @DisplayName("RequestWrapper equals Edge Cases")
    class EqualsEdgeCases {

        @Test
        @DisplayName("different body → not equal")
        void differentBody() {
            IRequest r1 = wrapper.newRequest(URI.create("http://example.com"));
            IRequest r2 = wrapper.newRequest(URI.create("http://example.com"));
            r1.setBodyEntity("body1", null, null);
            r2.setBodyEntity("body2", null, null);
            assertNotEquals(r1, r2);
        }

        @Test
        @DisplayName("different encoding → not equal")
        void differentEncoding() {
            IRequest r1 = wrapper.newRequest(URI.create("http://example.com"));
            IRequest r2 = wrapper.newRequest(URI.create("http://example.com"));
            r1.setBodyEntity("body", "utf-8", "text/plain");
            r2.setBodyEntity("body", "ascii", "text/plain");
            assertNotEquals(r1, r2);
        }

        @Test
        @DisplayName("different URI → not equal")
        void differentUri() {
            IRequest r1 = wrapper.newRequest(URI.create("http://example.com/a"));
            IRequest r2 = wrapper.newRequest(URI.create("http://example.com/b"));
            assertNotEquals(r1, r2);
        }

        @Test
        @DisplayName("different method → not equal")
        void differentMethod() {
            IRequest r1 = wrapper.newRequest(URI.create("http://example.com"), IHttpClient.Method.GET);
            IRequest r2 = wrapper.newRequest(URI.create("http://example.com"), IHttpClient.Method.POST);
            assertNotEquals(r1, r2);
        }
    }

    // ==================== toString edge cases ====================

    @Nested
    @DisplayName("RequestWrapper toString Edge Cases")
    class ToStringEdgeCases {

        @Test
        @DisplayName("toString with body exactly at TEXT_LIMIT has no truncation")
        void bodyExactLimit() {
            IRequest request = wrapper.newRequest(URI.create("http://example.com"));
            request.setBodyEntity("x".repeat(150), null, null);
            String result = request.toString();
            assertFalse(result.contains("..."));
        }

        @Test
        @DisplayName("toString with body 1 over TEXT_LIMIT has truncation")
        void bodyOneOverLimit() {
            IRequest request = wrapper.newRequest(URI.create("http://example.com"));
            request.setBodyEntity("x".repeat(151), null, null);
            String result = request.toString();
            assertTrue(result.contains("..."));
        }

        @Test
        @DisplayName("toString includes query params")
        void includesQueryParams() {
            IRequest request = wrapper.newRequest(URI.create("http://example.com?k=v"));
            String result = request.toString();
            assertTrue(result.contains("queryParams"));
        }
    }
}
