/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.apicalls.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ApiCall model — defaults and round-trip getters/setters.
 */
class ApiCallTest {

    @Test
    @DisplayName("defaults — saveResponse false, fireAndForget false, isBatchCalls false")
    void defaults() {
        var call = new ApiCall();
        assertNull(call.getName());
        assertNull(call.getDescription());
        assertNull(call.getParameters());
        assertNull(call.getActions());
        assertFalse(call.getSaveResponse());
        assertNull(call.getResponseObjectName());
        assertNull(call.getResponseHeaderObjectName());
        assertFalse(call.getFireAndForget());
        assertFalse(call.getIsBatchCalls());
        assertNull(call.getIterationObjectName());
        assertNull(call.getPreRequest());
        assertNull(call.getRequest());
        assertNull(call.getPostResponse());
    }

    @Test
    @DisplayName("round-trip all fields")
    void roundTrip() {
        var call = new ApiCall();
        call.setName("getWeather");
        call.setDescription("Fetches weather data");
        call.setParameters(Map.of("city", "City name"));
        call.setActions(List.of("weather_lookup"));
        call.setSaveResponse(true);
        call.setResponseObjectName("weatherData");
        call.setResponseHeaderObjectName("weatherHeaders");
        call.setFireAndForget(true);
        call.setIsBatchCalls(true);
        call.setIterationObjectName("cities");

        assertEquals("getWeather", call.getName());
        assertEquals("Fetches weather data", call.getDescription());
        assertEquals(Map.of("city", "City name"), call.getParameters());
        assertEquals(List.of("weather_lookup"), call.getActions());
        assertTrue(call.getSaveResponse());
        assertEquals("weatherData", call.getResponseObjectName());
        assertEquals("weatherHeaders", call.getResponseHeaderObjectName());
        assertTrue(call.getFireAndForget());
        assertTrue(call.getIsBatchCalls());
        assertEquals("cities", call.getIterationObjectName());
    }

    @Test
    @DisplayName("request and pre/post setters")
    void requestPrePostSetters() {
        var call = new ApiCall();
        var pre = new HttpPreRequest();
        var req = new Request();
        var post = new HttpPostResponse();
        call.setPreRequest(pre);
        call.setRequest(req);
        call.setPostResponse(post);

        assertSame(pre, call.getPreRequest());
        assertSame(req, call.getRequest());
        assertSame(post, call.getPostResponse());
    }
}
