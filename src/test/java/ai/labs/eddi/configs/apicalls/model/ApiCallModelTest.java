/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.apicalls.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ApiCallModelTest {

    // ==================== ApiCall ====================

    @Test
    void apiCall_defaults() {
        var call = new ApiCall();
        assertNull(call.getName());
        assertNull(call.getDescription());
        assertNull(call.getParameters());
        assertNull(call.getActions());
        assertFalse(call.getSaveResponse());
        assertFalse(call.getFireAndForget());
        assertFalse(call.getIsBatchCalls());
    }

    @Test
    void apiCall_settersAndGetters() {
        var call = new ApiCall();
        call.setName("weatherApi");
        call.setDescription("Get weather for a city");
        call.setParameters(Map.of("city", "Name of the city"));
        call.setActions(List.of("api_weather"));
        call.setSaveResponse(true);
        call.setResponseObjectName("weatherResult");
        call.setResponseHeaderObjectName("weatherHeaders");
        call.setFireAndForget(false);
        call.setIsBatchCalls(true);
        call.setIterationObjectName("cities");

        assertEquals("weatherApi", call.getName());
        assertEquals("Get weather for a city", call.getDescription());
        assertEquals(1, call.getParameters().size());
        assertEquals(List.of("api_weather"), call.getActions());
        assertTrue(call.getSaveResponse());
        assertEquals("weatherResult", call.getResponseObjectName());
        assertEquals("weatherHeaders", call.getResponseHeaderObjectName());
        assertFalse(call.getFireAndForget());
        assertTrue(call.getIsBatchCalls());
        assertEquals("cities", call.getIterationObjectName());
    }

    @Test
    void apiCall_preAndPostRequest() {
        var call = new ApiCall();

        var preReq = new HttpPreRequest();
        call.setPreRequest(preReq);
        assertSame(preReq, call.getPreRequest());

        var request = new Request();
        call.setRequest(request);
        assertSame(request, call.getRequest());

        var postResp = new HttpPostResponse();
        call.setPostResponse(postResp);
        assertSame(postResp, call.getPostResponse());
    }

    // ==================== PostResponse ====================

    @Test
    void postResponse_settersAndGetters() {
        var pr = new PostResponse();
        assertNull(pr.getPropertyInstructions());
        assertNull(pr.getOutputBuildInstructions());
        assertNull(pr.getQrBuildInstructions());

        pr.setPropertyInstructions(List.of());
        pr.setOutputBuildInstructions(List.of());
        pr.setQrBuildInstructions(List.of());

        assertNotNull(pr.getPropertyInstructions());
        assertNotNull(pr.getOutputBuildInstructions());
        assertNotNull(pr.getQrBuildInstructions());
    }

    // ==================== HttpCodeValidator ====================

    @Test
    void httpCodeValidator_default() {
        var def = HttpCodeValidator.DEFAULT;
        assertNotNull(def.getRunOnHttpCode());
        assertNotNull(def.getSkipOnHttpCode());
        assertTrue(def.getRunOnHttpCode().contains(200));
    }

    @Test
    void httpCodeValidator_settersAndGetters() {
        var validator = new HttpCodeValidator();
        validator.setRunOnHttpCode(List.of(200, 201));
        validator.setSkipOnHttpCode(List.of(204));

        assertEquals(2, validator.getRunOnHttpCode().size());
        assertEquals(1, validator.getSkipOnHttpCode().size());
    }

    // ==================== Request ====================

    @Test
    void request_defaults() {
        var request = new Request();
        assertEquals("", request.getPath());
        assertEquals("GET", request.getMethod());
        assertEquals("", request.getContentType());
        assertEquals("", request.getBody());
        assertNotNull(request.getHeaders());
        assertTrue(request.getHeaders().isEmpty());
        assertNotNull(request.getQueryParams());
        assertTrue(request.getQueryParams().isEmpty());
    }

    @Test
    void request_settersAndGetters() {
        var request = new Request();
        request.setPath("https://api.example.com/weather");
        request.setMethod("POST");
        request.setContentType("application/json");
        request.setBody("{\"city\":\"Berlin\"}");
        request.setHeaders(Map.of("Authorization", "Bearer token"));
        request.setQueryParams(Map.of("units", "metric"));

        assertEquals("https://api.example.com/weather", request.getPath());
        assertEquals("POST", request.getMethod());
        assertEquals("application/json", request.getContentType());
        assertEquals("{\"city\":\"Berlin\"}", request.getBody());
        assertEquals("Bearer token", request.getHeaders().get("Authorization"));
        assertEquals("metric", request.getQueryParams().get("units"));
    }

    // ==================== Jackson round-trip ====================

    @Test
    void apiCall_jacksonRoundTrip() throws Exception {
        var call = new ApiCall();
        call.setName("testCall");
        call.setActions(List.of("action1"));
        call.setSaveResponse(true);

        var mapper = new ObjectMapper();
        var json = mapper.writeValueAsString(call);
        var deserialized = mapper.readValue(json, ApiCall.class);

        assertEquals("testCall", deserialized.getName());
        assertEquals(List.of("action1"), deserialized.getActions());
        assertTrue(deserialized.getSaveResponse());
    }

    @Test
    void request_jacksonRoundTrip() throws Exception {
        var request = new Request();
        request.setPath("/api/v1/data");
        request.setMethod("PUT");
        request.setBody("{\"key\":\"val\"}");

        var mapper = new ObjectMapper();
        var json = mapper.writeValueAsString(request);
        var deserialized = mapper.readValue(json, Request.class);

        assertEquals("/api/v1/data", deserialized.getPath());
        assertEquals("PUT", deserialized.getMethod());
        assertEquals("{\"key\":\"val\"}", deserialized.getBody());
    }
}
