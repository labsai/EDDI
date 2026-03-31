package ai.labs.eddi.modules.llm.tools.impl;

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
        weatherTool = new WeatherTool();

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
}
