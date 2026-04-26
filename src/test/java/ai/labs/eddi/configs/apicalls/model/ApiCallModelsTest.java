/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.apicalls.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ApiCallModelsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // --- RetryApiCallInstruction ---

    @Test
    void retryInstruction_defaultValues() {
        var retry = new RetryApiCallInstruction();
        assertEquals(3, retry.getMaxRetries());
        assertEquals(1000, retry.getExponentialBackoffDelayInMillis());
        assertTrue(retry.getRetryOnHttpCodes().contains(502));
        assertTrue(retry.getRetryOnHttpCodes().contains(503));
    }

    @Test
    void retryInstruction_constructor() {
        var retry = new RetryApiCallInstruction(5, 2000, List.of(500, 502), null);
        assertEquals(5, retry.getMaxRetries());
        assertEquals(2000, retry.getExponentialBackoffDelayInMillis());
        assertEquals(2, retry.getRetryOnHttpCodes().size());
        assertNull(retry.getResponseValuePathMatchers());
    }

    @Test
    void retryInstruction_setters() {
        var retry = new RetryApiCallInstruction();
        retry.setMaxRetries(10);
        retry.setExponentialBackoffDelayInMillis(5000);
        retry.setRetryOnHttpCodes(List.of(429));
        assertEquals(10, retry.getMaxRetries());
        assertEquals(5000, retry.getExponentialBackoffDelayInMillis());
    }

    @Test
    void matchingInfo_settersAndGetters() {
        var info = new RetryApiCallInstruction.MatchingInfo();
        info.setValuePath("response.status");
        info.setContains("error");
        info.setEquals("FAILED");
        info.setTrueIfNoMatch(true);

        assertEquals("response.status", info.getValuePath());
        assertEquals("error", info.getContains());
        assertEquals("FAILED", info.getEquals());
        assertTrue(info.getTrueIfNoMatch());
    }

    @Test
    void matchingInfo_defaultTrueIfNoMatch() {
        var info = new RetryApiCallInstruction.MatchingInfo();
        assertFalse(info.getTrueIfNoMatch());
    }

    @Test
    void retryInstruction_jacksonRoundTrip() throws Exception {
        var retry = new RetryApiCallInstruction(2, 500, List.of(503), null);
        String json = mapper.writeValueAsString(retry);
        assertTrue(json.contains("\"maxRetries\":2"));

        var deserialized = mapper.readValue(json, RetryApiCallInstruction.class);
        assertEquals(2, deserialized.getMaxRetries());
        assertEquals(500, deserialized.getExponentialBackoffDelayInMillis());
    }

    // --- Request ---

    @Test
    void request_defaultValues() {
        var request = new Request();
        assertEquals("", request.getPath());
        assertEquals("", request.getBody());
        assertNotNull(request.getHeaders());
        assertEquals("GET", request.getMethod());
    }

    @Test
    void request_setters() {
        var request = new Request();
        request.setPath("/api/weather");
        request.setMethod("POST");
        request.setBody("{ \"key\": \"value\" }");
        request.setContentType("application/json");

        assertEquals("/api/weather", request.getPath());
        assertEquals("POST", request.getMethod());
        assertEquals("{ \"key\": \"value\" }", request.getBody());
        assertEquals("application/json", request.getContentType());
    }

    @Test
    void request_headersAndQueryParams() {
        var request = new Request();
        request.getHeaders().put("Authorization", "Bearer token");
        request.getQueryParams().put("q", "search");

        assertEquals("Bearer token", request.getHeaders().get("Authorization"));
        assertEquals("search", request.getQueryParams().get("q"));
    }

    // --- PostResponse ---

    @Test
    void postResponse_defaultNull() {
        var pr = new PostResponse();
        assertNull(pr.getPropertyInstructions());
        assertNull(pr.getOutputBuildInstructions());
        assertNull(pr.getQrBuildInstructions());
    }

    @Test
    void postResponse_setters() {
        var pr = new PostResponse();
        pr.setPropertyInstructions(List.of());
        pr.setOutputBuildInstructions(List.of());
        pr.setQrBuildInstructions(List.of());
        assertNotNull(pr.getPropertyInstructions());
        assertNotNull(pr.getOutputBuildInstructions());
        assertNotNull(pr.getQrBuildInstructions());
    }

    // --- ApiCall ---

    @Test
    void apiCall_setters() {
        var call = new ApiCall();
        call.setName("weather-api");
        call.setActions(List.of("fetch_weather"));
        call.setSaveResponse(true);

        assertEquals("weather-api", call.getName());
        assertEquals(List.of("fetch_weather"), call.getActions());
        assertTrue(call.getSaveResponse());
    }

    @Test
    void apiCall_defaultSaveResponse() {
        var call = new ApiCall();
        assertFalse(call.getSaveResponse()); // defaults to false
    }
}
