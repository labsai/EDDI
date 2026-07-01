/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.impl;

import ai.labs.eddi.engine.httpclient.IResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@code HttpClientWrapper.ResponseWrapper}, which had 0% coverage.
 * <p>
 * ResponseWrapper is a private static inner class, so we test it via
 * reflection.
 */
@DisplayName("HttpClientWrapper ResponseWrapper — Full Coverage")
class HttpClientWrapperResponseWrapperTest {

    /**
     * Creates a ResponseWrapper instance via reflection since it's package-private.
     */
    private IResponse createResponseWrapper() throws Exception {
        Class<?> clazz = Class.forName("ai.labs.eddi.engine.httpclient.impl.HttpClientWrapper$ResponseWrapper");
        Constructor<?> ctor = clazz.getDeclaredConstructor();
        ctor.setAccessible(true);
        return (IResponse) ctor.newInstance();
    }

    private void invokeSet(Object obj, String methodName, Object value) throws Exception {
        for (Method m : obj.getClass().getDeclaredMethods()) {
            if (m.getName().equals(methodName) && m.getParameterCount() == 1) {
                m.setAccessible(true);
                m.invoke(obj, value);
                return;
            }
        }
        throw new NoSuchMethodException(methodName);
    }

    private Object invokeGet(Object obj, String methodName) throws Exception {
        Method m = obj.getClass().getDeclaredMethod(methodName);
        m.setAccessible(true);
        return m.invoke(obj);
    }

    @Nested
    @DisplayName("Getters and Setters")
    class GetterSetterTests {

        @Test
        @DisplayName("getContentAsString returns set content")
        void contentAsString() throws Exception {
            IResponse rw = createResponseWrapper();
            invokeSet(rw, "setContentAsString", "hello");
            assertEquals("hello", rw.getContentAsString());
        }

        @Test
        @DisplayName("getHttpCode returns set code")
        void httpCode() throws Exception {
            IResponse rw = createResponseWrapper();
            invokeSet(rw, "setHttpCode", 200);
            assertEquals(200, rw.getHttpCode());
        }

        @Test
        @DisplayName("getHttpCodeMessage returns set message")
        void httpCodeMessage() throws Exception {
            IResponse rw = createResponseWrapper();
            invokeSet(rw, "setHttpCodeMessage", "OK");
            assertEquals("OK", rw.getHttpCodeMessage());
        }

        @Test
        @DisplayName("getHttpHeader returns set headers")
        void httpHeader() throws Exception {
            IResponse rw = createResponseWrapper();
            Map<String, String> headers = Map.of("X-Custom", "val");
            invokeSet(rw, "setHttpHeader", headers);
            assertEquals(headers, rw.getHttpHeader());
        }
    }

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        @DisplayName("toString includes httpCode and httpCodeMessage")
        void toStringBasic() throws Exception {
            IResponse rw = createResponseWrapper();
            invokeSet(rw, "setHttpCode", 404);
            invokeSet(rw, "setHttpCodeMessage", "Not Found");
            invokeSet(rw, "setContentAsString", "page not found");
            invokeSet(rw, "setHttpHeader", Map.of("Content-Type", "text/plain"));

            String result = rw.toString();
            assertTrue(result.contains("404"));
            assertTrue(result.contains("Not Found"));
            assertTrue(result.contains("page not found"));
        }

        @Test
        @DisplayName("toString with null httpHeader shows null")
        void toStringNullHeader() throws Exception {
            IResponse rw = createResponseWrapper();
            invokeSet(rw, "setHttpCode", 200);
            invokeSet(rw, "setHttpCodeMessage", "OK");
            invokeSet(rw, "setContentAsString", "body");
            // httpHeader is null by default

            String result = rw.toString();
            assertTrue(result.contains("null"));
        }

        @Test
        @DisplayName("toString truncates long content")
        void toStringLongContent() throws Exception {
            IResponse rw = createResponseWrapper();
            invokeSet(rw, "setHttpCode", 200);
            invokeSet(rw, "setHttpCodeMessage", "OK");
            invokeSet(rw, "setContentAsString", "x".repeat(200));
            invokeSet(rw, "setHttpHeader", Map.of());

            String result = rw.toString();
            assertTrue(result.contains("..."));
        }

        @Test
        @DisplayName("toString with newlines in content replaces with space")
        void toStringNewlineContent() throws Exception {
            IResponse rw = createResponseWrapper();
            invokeSet(rw, "setHttpCode", 200);
            invokeSet(rw, "setHttpCodeMessage", "OK");
            invokeSet(rw, "setContentAsString", "line1\r\nline2\nline3");
            invokeSet(rw, "setHttpHeader", Map.of());

            String result = rw.toString();
            assertTrue(result.contains("line1 line2 line3"));
        }
    }

    @Nested
    @DisplayName("equals and hashCode")
    class EqualsHashCodeTests {

        @Test
        @DisplayName("same instance equals true")
        void sameInstance() throws Exception {
            IResponse rw = createResponseWrapper();
            assertEquals(rw, rw);
        }

        @Test
        @DisplayName("null equals false")
        void nullNotEqual() throws Exception {
            IResponse rw = createResponseWrapper();
            assertNotEquals(null, rw);
        }

        @Test
        @DisplayName("different class equals false")
        void differentClass() throws Exception {
            IResponse rw = createResponseWrapper();
            assertNotEquals("a string", rw);
        }

        @Test
        @DisplayName("two ResponseWrappers with same state are equal")
        void identicalState() throws Exception {
            IResponse rw1 = createResponseWrapper();
            invokeSet(rw1, "setHttpCode", 200);
            invokeSet(rw1, "setHttpCodeMessage", "OK");
            invokeSet(rw1, "setContentAsString", "body");
            invokeSet(rw1, "setHttpHeader", new HashMap<>(Map.of("X-A", "1")));

            IResponse rw2 = createResponseWrapper();
            invokeSet(rw2, "setHttpCode", 200);
            invokeSet(rw2, "setHttpCodeMessage", "OK");
            invokeSet(rw2, "setContentAsString", "body");
            invokeSet(rw2, "setHttpHeader", new HashMap<>(Map.of("X-A", "1")));

            assertEquals(rw1, rw2);
            assertEquals(rw1.hashCode(), rw2.hashCode());
        }

        @Test
        @DisplayName("different httpCode → not equal")
        void differentHttpCode() throws Exception {
            IResponse rw1 = createResponseWrapper();
            invokeSet(rw1, "setHttpCode", 200);

            IResponse rw2 = createResponseWrapper();
            invokeSet(rw2, "setHttpCode", 404);

            assertNotEquals(rw1, rw2);
        }

        @Test
        @DisplayName("different content → not equal")
        void differentContent() throws Exception {
            IResponse rw1 = createResponseWrapper();
            invokeSet(rw1, "setContentAsString", "a");

            IResponse rw2 = createResponseWrapper();
            invokeSet(rw2, "setContentAsString", "b");

            assertNotEquals(rw1, rw2);
        }

        @Test
        @DisplayName("different httpCodeMessage → not equal")
        void differentMessage() throws Exception {
            IResponse rw1 = createResponseWrapper();
            invokeSet(rw1, "setHttpCodeMessage", "OK");

            IResponse rw2 = createResponseWrapper();
            invokeSet(rw2, "setHttpCodeMessage", "Not Found");

            assertNotEquals(rw1, rw2);
        }

        @Test
        @DisplayName("different headers → not equal")
        void differentHeaders() throws Exception {
            IResponse rw1 = createResponseWrapper();
            invokeSet(rw1, "setHttpHeader", Map.of("A", "1"));

            IResponse rw2 = createResponseWrapper();
            invokeSet(rw2, "setHttpHeader", Map.of("B", "2"));

            assertNotEquals(rw1, rw2);
        }

        @Test
        @DisplayName("hashCode differs when content differs")
        void hashCodeDiffers() throws Exception {
            IResponse rw1 = createResponseWrapper();
            invokeSet(rw1, "setContentAsString", "aaa");

            IResponse rw2 = createResponseWrapper();
            invokeSet(rw2, "setContentAsString", "bbb");

            assertNotEquals(rw1.hashCode(), rw2.hashCode());
        }
    }
}
