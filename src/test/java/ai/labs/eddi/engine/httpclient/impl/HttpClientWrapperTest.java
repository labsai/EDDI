/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.impl;

import ai.labs.eddi.engine.httpclient.IHttpClient;
import ai.labs.eddi.engine.httpclient.IRequest;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.WebClientSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link HttpClientWrapper}.
 * <p>
 * Tests the public API surface (IRequest/IResponse) by mocking the Vert.x
 * WebClientSession layer. Private inner classes (RequestWrapper,
 * ResponseWrapper) are exercised through the public interface.
 *
 * @since 6.0.2
 */
@SuppressWarnings("unchecked")
class HttpClientWrapperTest {

    private HttpClientWrapper httpClientWrapper;
    private WebClientSession mockWebClient;
    private HttpRequest<Buffer> mockHttpRequest;
    private MultiMap mockHeaders;

    @BeforeEach
    void setUp() {
        mockWebClient = mock(WebClientSession.class);
        mockHttpRequest = mock(HttpRequest.class);
        mockHeaders = MultiMap.caseInsensitiveMultiMap();

        when(mockWebClient.requestAbs(any(HttpMethod.class), anyString()))
                .thenReturn(mockHttpRequest);
        when(mockHttpRequest.putHeader(anyString(), anyString()))
                .thenReturn(mockHttpRequest);
        when(mockHttpRequest.addQueryParam(anyString(), anyString()))
                .thenReturn(mockHttpRequest);
        when(mockHttpRequest.basicAuthentication(anyString(), anyString()))
                .thenReturn(mockHttpRequest);
        when(mockHttpRequest.timeout(anyLong())).thenReturn(mockHttpRequest);
        when(mockHttpRequest.headers()).thenReturn(mockHeaders);

        var vertxHttpClient = mock(VertxHttpClient.class);
        when(vertxHttpClient.getWebClient()).thenReturn(mockWebClient);

        httpClientWrapper = new HttpClientWrapper(vertxHttpClient, "eddi.labs.ai", "6.0.2");
    }

    // ==================== Factory Methods ====================

    @Nested
    @DisplayName("Factory Method Tests")
    class FactoryTests {

        @Test
        @DisplayName("newRequest(URI) defaults to GET method")
        void newRequest_defaultsToGet() {
            IRequest request = httpClientWrapper.newRequest(URI.create("https://example.com/api"));

            assertNotNull(request);
            verify(mockWebClient).requestAbs(HttpMethod.GET, "https://example.com/api");
        }

        @Test
        @DisplayName("newRequest(URI, Method) uses specified method")
        void newRequest_withMethod() {
            IRequest request = httpClientWrapper.newRequest(
                    URI.create("https://example.com/api"), IHttpClient.Method.POST);

            assertNotNull(request);
            verify(mockWebClient).requestAbs(HttpMethod.POST, "https://example.com/api");
        }

        @Test
        @DisplayName("newRequest sets User-Agent header from config")
        void newRequest_setsUserAgent() {
            httpClientWrapper.newRequest(URI.create("https://example.com"));

            verify(mockHttpRequest).putHeader("User-Agent", "EDDI.LABS.AI/6.0.2");
        }

        @Test
        @DisplayName("All HTTP methods can be used")
        void newRequest_allMethods() {
            for (IHttpClient.Method method : IHttpClient.Method.values()) {
                IRequest request = httpClientWrapper.newRequest(
                        URI.create("https://example.com"), method);
                assertNotNull(request, "Method " + method + " should create a request");
            }
        }
    }

    // ==================== Request Configuration ====================

    @Nested
    @DisplayName("Request Configuration Tests")
    class RequestConfigTests {

        @Test
        @DisplayName("setQueryParam delegates to Vert.x and tracks internally")
        void setQueryParam_delegatesAndTracks() {
            IRequest request = httpClientWrapper.newRequest(URI.create("https://example.com"));
            IRequest result = request.setQueryParam("key", "value");

            assertSame(request, result, "Should return this for fluent chaining");
            verify(mockHttpRequest).addQueryParam("key", "value");
        }

        @Test
        @DisplayName("setHttpHeader delegates to Vert.x request")
        void setHttpHeader_delegates() {
            IRequest request = httpClientWrapper.newRequest(URI.create("https://example.com"));
            request.setHttpHeader("X-Custom", "test-value");

            verify(mockHttpRequest).putHeader("X-Custom", "test-value");
        }

        @Test
        @DisplayName("setUserAgent overrides default User-Agent")
        void setUserAgent_overridesDefault() {
            IRequest request = httpClientWrapper.newRequest(URI.create("https://example.com"));
            request.setUserAgent("CustomAgent/1.0");

            // First call is from constructor, second from setUserAgent
            verify(mockHttpRequest, times(2)).putHeader(eq("User-Agent"), anyString());
            verify(mockHttpRequest).putHeader("User-Agent", "CustomAgent/1.0");
        }

        @Test
        @DisplayName("setBasicAuthentication delegates to Vert.x")
        void setBasicAuth_delegates() {
            IRequest request = httpClientWrapper.newRequest(URI.create("https://example.com"));
            IRequest result = request.setBasicAuthentication("user", "pass", "realm", true);

            assertSame(request, result);
            verify(mockHttpRequest).basicAuthentication("user", "pass");
        }

        @Test
        @DisplayName("setBasicAuthentication with non-preemptive falls back to preemptive")
        void setBasicAuth_nonPreemptiveFallback() {
            IRequest request = httpClientWrapper.newRequest(URI.create("https://example.com"));
            // Should log a warning but still work
            request.setBasicAuthentication("user", "pass", "realm", false);

            verify(mockHttpRequest).basicAuthentication("user", "pass");
        }

        @Test
        @DisplayName("setTimeout converts to millis and delegates")
        void setTimeout_convertsAndDelegates() {
            IRequest request = httpClientWrapper.newRequest(URI.create("https://example.com"));
            request.setTimeout(5, TimeUnit.SECONDS);

            verify(mockHttpRequest).timeout(5000L);
        }

        @Test
        @DisplayName("setBodyEntity with content-type and encoding")
        void setBodyEntity_withEncodingAndContentType() {
            IRequest request = httpClientWrapper.newRequest(URI.create("https://example.com"));
            request.setBodyEntity("{\"key\":\"value\"}", "UTF-8", "application/json");

            verify(mockHttpRequest).putHeader("Content-Type", "application/json; charset=UTF-8");
        }

        @Test
        @DisplayName("setBodyEntity with content-type only (no encoding)")
        void setBodyEntity_contentTypeOnly() {
            IRequest request = httpClientWrapper.newRequest(URI.create("https://example.com"));
            request.setBodyEntity("body", null, "text/plain");

            verify(mockHttpRequest).putHeader("Content-Type", "text/plain");
        }

        @Test
        @DisplayName("setBodyEntity with no content-type does not set header")
        void setBodyEntity_noContentType() {
            IRequest request = httpClientWrapper.newRequest(URI.create("https://example.com"));
            request.setBodyEntity("body", "UTF-8", null);

            // Only User-Agent header should be set, not Content-Type
            verify(mockHttpRequest, never()).putHeader(eq("Content-Type"), anyString());
        }

        @Test
        @DisplayName("setMaxResponseSize returns fluent this")
        void setMaxResponseSize_fluent() {
            IRequest request = httpClientWrapper.newRequest(URI.create("https://example.com"));
            IRequest result = request.setMaxResponseSize(1024);

            assertSame(request, result);
        }
    }

    // ==================== Query Param Parsing ====================

    @Nested
    @DisplayName("URI Query Parameter Parsing")
    class QueryParamParsingTests {

        @Test
        @DisplayName("URI with no query params produces empty queryParams in toMap")
        void noQueryParams() {
            IRequest request = httpClientWrapper.newRequest(URI.create("https://example.com/path"));
            Map<String, Object> map = request.toMap();

            Map<String, ?> queryParams = (Map<String, ?>) map.get("queryParams");
            assertNotNull(queryParams);
            assertTrue(queryParams.isEmpty());
        }

        @Test
        @DisplayName("URI with single query param is parsed")
        void singleQueryParam() {
            IRequest request = httpClientWrapper.newRequest(
                    URI.create("https://example.com/path?key=value"));
            Map<String, Object> map = request.toMap();

            Map<String, ?> queryParams = (Map<String, ?>) map.get("queryParams");
            assertTrue(queryParams.containsKey("key"));
        }

        @Test
        @DisplayName("URI with multiple query params are parsed")
        void multipleQueryParams() {
            IRequest request = httpClientWrapper.newRequest(
                    URI.create("https://example.com?a=1&b=2&c=3"));
            Map<String, Object> map = request.toMap();

            Map<String, ?> queryParams = (Map<String, ?>) map.get("queryParams");
            assertEquals(3, queryParams.size());
        }

        @Test
        @DisplayName("URI with URL-encoded query params are decoded")
        void encodedQueryParams() {
            IRequest request = httpClientWrapper.newRequest(
                    URI.create("https://example.com?hello%20world=foo%20bar"));
            Map<String, Object> map = request.toMap();

            Map<String, ?> queryParams = (Map<String, ?>) map.get("queryParams");
            assertTrue(queryParams.containsKey("hello world"));
        }

        @Test
        @DisplayName("URI with key-only param (no value) is parsed")
        void keyOnlyParam() {
            IRequest request = httpClientWrapper.newRequest(
                    URI.create("https://example.com?flag"));
            Map<String, Object> map = request.toMap();

            Map<String, ?> queryParams = (Map<String, ?>) map.get("queryParams");
            assertTrue(queryParams.containsKey("flag"));
        }
    }

    // ==================== toMap ====================

    @Nested
    @DisplayName("toMap() Tests")
    class ToMapTests {

        @Test
        @DisplayName("toMap includes URI, method, headers, queryParams, maxLength")
        void toMap_containsAllKeys() {
            IRequest request = httpClientWrapper.newRequest(
                    URI.create("https://example.com/api"), IHttpClient.Method.POST);
            Map<String, Object> map = request.toMap();

            assertEquals("https://example.com/api", map.get("uri"));
            assertEquals("POST", map.get("method"));
            assertNotNull(map.get("headers"));
            assertNotNull(map.get("queryParams"));
            assertNotNull(map.get("maxLength"));
        }

        @Test
        @DisplayName("toMap includes body when set")
        void toMap_includesBody() {
            IRequest request = httpClientWrapper.newRequest(URI.create("https://example.com"));
            request.setBodyEntity("test body", null, null);
            Map<String, Object> map = request.toMap();

            assertEquals("test body", map.get("body"));
        }

        @Test
        @DisplayName("toMap excludes body when not set")
        void toMap_excludesBodyWhenNull() {
            IRequest request = httpClientWrapper.newRequest(URI.create("https://example.com"));
            Map<String, Object> map = request.toMap();

            assertFalse(map.containsKey("body"));
        }

        @Test
        @DisplayName("toMap includes userAgent from headers")
        void toMap_includesUserAgent() {
            mockHeaders.add("User-Agent", "EDDI.LABS.AI/6.0.2");

            IRequest request = httpClientWrapper.newRequest(URI.create("https://example.com"));
            Map<String, Object> map = request.toMap();

            assertEquals("EDDI.LABS.AI/6.0.2", map.get("userAgent"));
        }
    }

    // ==================== toString ====================

    @Nested
    @DisplayName("toString() Tests")
    class ToStringTests {

        @Test
        @DisplayName("RequestWrapper toString includes URI and method")
        void requestToString_basic() {
            IRequest request = httpClientWrapper.newRequest(
                    URI.create("https://example.com"), IHttpClient.Method.GET);
            String str = request.toString();

            assertTrue(str.contains("https://example.com"));
            assertTrue(str.contains("GET"));
        }

        @Test
        @DisplayName("RequestWrapper toString truncates long body")
        void requestToString_truncatesBody() {
            IRequest request = httpClientWrapper.newRequest(URI.create("https://example.com"));
            String longBody = "x".repeat(200);
            request.setBodyEntity(longBody, null, null);
            String str = request.toString();

            // Body should be truncated at 150 chars + "..."
            assertTrue(str.contains("..."));
        }
    }

    // ==================== equals / hashCode ====================

    @Nested
    @DisplayName("equals() / hashCode() Tests")
    class EqualsHashCodeTests {

        @Test
        @DisplayName("Same request is equal to itself")
        void equals_reflexive() {
            IRequest request = httpClientWrapper.newRequest(URI.create("https://example.com"));
            assertEquals(request, request);
        }

        @Test
        @DisplayName("Request is not equal to null")
        void equals_nullReturnsFalse() {
            IRequest request = httpClientWrapper.newRequest(URI.create("https://example.com"));
            assertNotEquals(null, request);
        }

        @Test
        @DisplayName("Request is not equal to different type")
        void equals_differentTypeReturnsFalse() {
            IRequest request = httpClientWrapper.newRequest(URI.create("https://example.com"));
            assertNotEquals("not a request", request);
        }

        @Test
        @DisplayName("Equal requests have same hashCode")
        void hashCode_consistency() {
            IRequest request = httpClientWrapper.newRequest(URI.create("https://example.com"));
            int hash1 = request.hashCode();
            int hash2 = request.hashCode();
            assertEquals(hash1, hash2, "hashCode should be consistent");
        }
    }

    // ==================== truncateAndClean ====================

    @Nested
    @DisplayName("truncateAndClean() Tests")
    class TruncateAndCleanTests {

        @Test
        @DisplayName("null input returns null")
        void nullInput() {
            assertNull(HttpClientWrapper.truncateAndClean(null));
        }

        @Test
        @DisplayName("Short text is returned unchanged")
        void shortText() {
            assertEquals("hello", HttpClientWrapper.truncateAndClean("hello"));
        }

        @Test
        @DisplayName("Text longer than 150 chars is truncated with ellipsis")
        void longText() {
            String longText = "a".repeat(200);
            String result = HttpClientWrapper.truncateAndClean(longText);

            assertEquals(153, result.length()); // 150 + "..."
            assertTrue(result.endsWith("..."));
        }

        @Test
        @DisplayName("Newlines are replaced with spaces")
        void newlinesReplaced() {
            String result = HttpClientWrapper.truncateAndClean("line1\nline2\r\nline3");
            assertFalse(result.contains("\n"));
            assertFalse(result.contains("\r"));
            assertTrue(result.contains("line1 line2 line3"));
        }

        @Test
        @DisplayName("Empty string returns empty string")
        void emptyString() {
            assertEquals("", HttpClientWrapper.truncateAndClean(""));
        }

        @Test
        @DisplayName("Exactly 150 chars is not truncated")
        void exactly150Chars() {
            String exact = "b".repeat(150);
            String result = HttpClientWrapper.truncateAndClean(exact);
            assertEquals(150, result.length());
            assertFalse(result.endsWith("..."));
        }
    }

    // ==================== convertHeaderToMap ====================

    @Nested
    @DisplayName("convertHeaderToMap() Tests")
    class ConvertHeaderTests {

        @Test
        @DisplayName("Empty MultiMap produces empty map")
        void emptyHeaders() {
            MultiMap headers = MultiMap.caseInsensitiveMultiMap();
            Map<String, String> result = HttpClientWrapper.convertHeaderToMap(headers);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Multiple headers are converted correctly")
        void multipleHeaders() {
            MultiMap headers = MultiMap.caseInsensitiveMultiMap();
            headers.add("Content-Type", "application/json");
            headers.add("Authorization", "Bearer token");

            Map<String, String> result = HttpClientWrapper.convertHeaderToMap(headers);
            assertEquals(2, result.size());
            assertEquals("application/json", result.get("Content-Type"));
            assertEquals("Bearer token", result.get("Authorization"));
        }
    }
}
