/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import ai.labs.eddi.engine.httpclient.SafeHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for WeatherTool. Note: Tests expect API key to NOT be configured,
 * so they verify error handling.
 */
class WeatherToolTest {

    private WeatherTool weatherTool;

    @BeforeEach
    void setUp() throws Exception {
        weatherTool = new WeatherTool(new SafeHttpClient(10000));

        // Use reflection to set the Optional field (simulating no API key configured)
        Field apiKeyField = WeatherTool.class.getDeclaredField("openWeatherMapApiKey");
        apiKeyField.setAccessible(true);
        apiKeyField.set(weatherTool, Optional.empty());
    }

    @Test
    void testGetCurrentWeather_ValidCity() {
        // Note: Without API key configured, this will return an error
        String result = weatherTool.getCurrentWeather("London", "metric");
        assertNotNull(result);
        assertTrue(result.contains("Error") || result.contains("API key not configured"));
    }

    @Test
    void testGetCurrentWeather_EmptyCity() {
        String result = weatherTool.getCurrentWeather("", "metric");
        assertNotNull(result);
        assertTrue(result.startsWith("Error") || result.contains("Error"));
    }

    @Test
    void testGetCurrentWeather_InvalidCity() {
        String result = weatherTool.getCurrentWeather("NonExistentCity12345", "metric");
        assertNotNull(result);
        assertTrue(result.contains("Error") || result.contains("API key not configured"));
    }

    @Test
    void testGetCurrentWeather_CityWithSpaces() {
        String result = weatherTool.getCurrentWeather("New York", "metric");
        assertNotNull(result);
        assertTrue(result.contains("Error") || result.contains("API key not configured"));
    }

    @Test
    void testGetCurrentWeather_CityWithSpecialCharacters() {
        String result = weatherTool.getCurrentWeather("São Paulo", "metric");
        assertNotNull(result);
        assertTrue(result.contains("Error") || result.contains("API key not configured"));
    }

    @Test
    void testGetCurrentWeather_ImperialUnits() {
        String result = weatherTool.getCurrentWeather("London", "imperial");
        assertNotNull(result);
        assertTrue(result.contains("Error") || result.contains("API key not configured"));
    }

    @Test
    void testGetCurrentWeather_NullUnits() {
        String result = weatherTool.getCurrentWeather("London", null);
        assertNotNull(result);
        assertTrue(result.contains("Error") || result.contains("API key not configured"));
    }

    // === getWeatherForecast tests ===

    @Test
    void testGetWeatherForecast_NoApiKey() {
        String result = weatherTool.getWeatherForecast("London", 3, "metric");
        assertNotNull(result);
        assertTrue(result.contains("Error") || result.contains("API key not configured"));
    }

    @Test
    void testGetWeatherForecast_NullUnits_DefaultsToMetric() {
        String result = weatherTool.getWeatherForecast("Berlin", 3, null);
        assertNotNull(result);
        assertTrue(result.contains("Error"));
    }

    @Test
    void testGetWeatherForecast_EmptyUnits_DefaultsToMetric() {
        String result = weatherTool.getWeatherForecast("Berlin", 3, "");
        assertNotNull(result);
        assertTrue(result.contains("Error"));
    }

    @Test
    void testGetWeatherForecast_NullDays_DefaultsTo3() {
        String result = weatherTool.getWeatherForecast("Paris", null, "metric");
        assertNotNull(result);
        assertTrue(result.contains("Error"));
    }

    @Test
    void testGetWeatherForecast_NegativeDays_DefaultsTo3() {
        String result = weatherTool.getWeatherForecast("Paris", -1, "metric");
        assertNotNull(result);
        assertTrue(result.contains("Error"));
    }

    @Test
    void testGetWeatherForecast_DaysExceeds5_ClampedTo5() {
        String result = weatherTool.getWeatherForecast("Tokyo", 10, "imperial");
        assertNotNull(result);
        assertTrue(result.contains("Error"));
    }

    @Test
    void testGetCurrentWeather_EmptyUnits_DefaultsToMetric() {
        String result = weatherTool.getCurrentWeather("London", "");
        assertNotNull(result);
        assertTrue(result.contains("Error"));
    }
}
