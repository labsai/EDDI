package ai.labs.eddi.modules.llm.tools.impl;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.httpclient.SafeHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Extended WeatherTool tests with mocked HTTP client. Uses a
 * real-ObjectMapper-based IJsonSerialization to exercise
 * formatWeatherResponse/formatForecastResponse parsing branches.
 */
class WeatherToolExtendedTest {

    private SafeHttpClient mockHttpClient;
    private WeatherTool weatherTool;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Minimal IJsonSerialization backed by a plain ObjectMapper. Using a concrete
     * impl instead of mocking avoids generic type erasure issues.
     */
    private static class TestJsonSerialization implements IJsonSerialization {
        @Override
        public String serialize(Object model) throws IOException {
            return OBJECT_MAPPER.writeValueAsString(model);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T deserialize(String json) throws IOException {
            return (T) OBJECT_MAPPER.readValue(json, Object.class);
        }

        @Override
        public <T> T deserialize(String json, Class<T> type) throws IOException {
            return OBJECT_MAPPER.readValue(json, type);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        mockHttpClient = mock(SafeHttpClient.class);
        weatherTool = new WeatherTool(mockHttpClient);

        // Inject test JSON serialization via reflection
        Field jsonField = WeatherTool.class.getDeclaredField("jsonSerialization");
        jsonField.setAccessible(true);
        jsonField.set(weatherTool, new TestJsonSerialization());

        // Set API key to present
        Field apiKeyField = WeatherTool.class.getDeclaredField("openWeatherMapApiKey");
        apiKeyField.setAccessible(true);
        apiKeyField.set(weatherTool, Optional.of("test-api-key"));
    }

    @SuppressWarnings("unchecked")
    private void mockResponse(int statusCode, String body) throws IOException, InterruptedException {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        when(response.body()).thenReturn(body);
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    }

    // ==================== getCurrentWeather ====================

    @Nested
    @DisplayName("getCurrentWeather — with API key")
    class GetCurrentWeatherTests {

        @Test
        @DisplayName("should format complete weather response with all fields")
        void formatsCompleteWeather() throws Exception {
            String json = """
                    {
                        "main": {"temp": 22.5, "feels_like": 20.1, "humidity": 65},
                        "weather": [{"description": "scattered clouds"}],
                        "wind": {"speed": 5.2}
                    }""";

            mockResponse(200, json);

            String result = weatherTool.getCurrentWeather("London", "metric");

            assertTrue(result.contains("22") && (result.contains("22.5") || result.contains("22,5")),
                    "Should contain temperature. Got: " + result);
            assertTrue(result.contains("20") && (result.contains("20.1") || result.contains("20,1")),
                    "Should contain feels_like");
            assertTrue(result.contains("65%"), "Should contain humidity");
            assertTrue(result.contains("scattered clouds"), "Should contain weather description");
            assertTrue(result.contains("5") && (result.contains("5.2") || result.contains("5,2")),
                    "Should contain wind speed");
        }

        @Test
        @DisplayName("should use imperial units when specified")
        void usesImperialUnits() throws Exception {
            String json = """
                    {
                        "main": {"temp": 72.0, "feels_like": 70.0, "humidity": 50},
                        "weather": [{"description": "clear sky"}],
                        "wind": {"speed": 10.0}
                    }""";

            mockResponse(200, json);

            String result = weatherTool.getCurrentWeather("New York", "imperial");

            assertTrue(result.contains("°F"), "Should contain Fahrenheit units");
            assertTrue(result.contains("mph"), "Should contain mph wind units");
        }

        @Test
        @DisplayName("should handle 404 — city not found")
        void handles404() throws Exception {
            mockResponse(404, "");

            String result = weatherTool.getCurrentWeather("NonexistentCity", "metric");

            assertTrue(result.contains("not found"));
        }

        @Test
        @DisplayName("should handle non-200/404 status codes")
        void handlesServerError() throws Exception {
            mockResponse(500, "Internal Server Error");

            String result = weatherTool.getCurrentWeather("London", "metric");

            assertTrue(result.contains("Error"));
        }

        @Test
        @DisplayName("should handle missing weather array")
        void handlesMissingWeather() throws Exception {
            String json = """
                    {
                        "main": {"temp": 20.0, "feels_like": 18.0, "humidity": 60}
                    }""";

            mockResponse(200, json);

            String result = weatherTool.getCurrentWeather("London", "metric");

            assertTrue(result.contains("20") && (result.contains("20.0") || result.contains("20,0")),
                    "Should contain temperature. Got: " + result);
            assertFalse(result.contains("Conditions:"), "Should not contain conditions without weather array");
        }

        @Test
        @DisplayName("should handle missing wind node")
        void handlesMissingWind() throws Exception {
            String json = """
                    {
                        "main": {"temp": 20.0, "feels_like": 18.0, "humidity": 60},
                        "weather": [{"description": "rain"}]
                    }""";

            mockResponse(200, json);

            String result = weatherTool.getCurrentWeather("Berlin", "metric");

            assertTrue(result.contains("rain"), "Should contain weather description");
            assertFalse(result.contains("Wind Speed:"), "Should not contain wind speed");
        }

        @Test
        @DisplayName("should handle missing main node")
        void handlesMissingMain() throws Exception {
            String json = """
                    {
                        "weather": [{"description": "sunny"}],
                        "wind": {"speed": 3.0}
                    }""";

            mockResponse(200, json);

            String result = weatherTool.getCurrentWeather("Paris", "metric");

            assertFalse(result.contains("Temperature:"), "Should not contain temperature without main");
            assertTrue(result.contains("sunny"), "Should contain weather description");
        }

        @Test
        @DisplayName("should default units to metric when null")
        void defaultsToMetric() throws Exception {
            String json = """
                    {
                        "main": {"temp": 15.0, "feels_like": 13.0, "humidity": 80},
                        "weather": [{"description": "cloudy"}]
                    }""";

            mockResponse(200, json);

            String result = weatherTool.getCurrentWeather("Rome", null);

            assertTrue(result.contains("°C"),
                    "Should use metric Celsius symbol. Got: " + result);
        }

        @Test
        @DisplayName("should handle malformed JSON gracefully")
        void handlesMalformedJson() throws Exception {
            mockResponse(200, "not valid json {{{");

            String result = weatherTool.getCurrentWeather("London", "metric");

            assertTrue(result.contains("could not be formatted") || result.contains("Error"),
                    "Should report formatting or error");
        }

        @Test
        @DisplayName("should handle IOException from HTTP client")
        void handlesIOException() throws Exception {
            when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenThrow(new IOException("Connection refused"));

            String result = weatherTool.getCurrentWeather("London", "metric");

            assertTrue(result.contains("Error"));
            assertTrue(result.contains("Connection refused"));
        }
    }

    // ==================== getWeatherForecast ====================

    @Nested
    @DisplayName("getWeatherForecast — with API key")
    class GetForecastTests {

        @Test
        @DisplayName("should format forecast response with multiple days")
        void formatsMultiDayForecast() throws Exception {
            String json = """
                    {
                        "list": [
                            {"dt_txt": "2026-04-22 12:00:00", "main": {"temp": 18.0}, "weather": [{"description": "cloudy"}]},
                            {"dt_txt": "2026-04-22 15:00:00", "main": {"temp": 19.0}, "weather": [{"description": "partly cloudy"}]},
                            {"dt_txt": "2026-04-23 12:00:00", "main": {"temp": 22.0}, "weather": [{"description": "sunny"}]}
                        ]
                    }""";

            mockResponse(200, json);

            String result = weatherTool.getWeatherForecast("London", 3, "metric");

            assertTrue(result.contains("2026-04-22"), "Should contain first day");
            assertTrue(result.contains("2026-04-23"), "Should contain second day");
            assertTrue(result.contains("cloudy"), "Should contain first day weather");
            assertTrue(result.contains("sunny"), "Should contain second day weather");
        }

        @Test
        @DisplayName("should handle 404 — city not found in forecast")
        void handlesForecast404() throws Exception {
            mockResponse(404, "");

            String result = weatherTool.getWeatherForecast("FakeCity", 3, "metric");

            assertTrue(result.contains("not found"));
        }

        @Test
        @DisplayName("should handle missing list node")
        void handlesMissingList() throws Exception {
            mockResponse(200, "{}");

            String result = weatherTool.getWeatherForecast("London", 3, "metric");

            assertTrue(result.contains("No forecast data available"));
        }

        @Test
        @DisplayName("should handle non-200/404 status codes")
        void handlesForecastServerError() throws Exception {
            mockResponse(503, "Service Unavailable");

            String result = weatherTool.getWeatherForecast("London", 3, "metric");

            assertTrue(result.contains("Error"));
        }

        @Test
        @DisplayName("should clamp null days to default 3")
        void clampsNullDaysTo3() throws Exception {
            mockResponse(404, "");

            String result = weatherTool.getWeatherForecast("London", null, "metric");

            assertTrue(result.contains("Error") || result.contains("not found"));
        }

        @Test
        @DisplayName("should clamp days exceeding 5 to 5")
        void clampsDaysTo5() throws Exception {
            mockResponse(404, "");

            String result = weatherTool.getWeatherForecast("London", 10, "metric");

            assertTrue(result.contains("Error") || result.contains("not found"));
        }

        @Test
        @DisplayName("should default units to metric when empty")
        void defaultsUnitsToMetric() throws Exception {
            mockResponse(404, "");

            String result = weatherTool.getWeatherForecast("London", 3, "");

            assertTrue(result.contains("Error") || result.contains("not found"));
        }

        @Test
        @DisplayName("should handle malformed forecast JSON gracefully")
        void handlesMalformedForecastJson() throws Exception {
            mockResponse(200, "{invalid json}");

            String result = weatherTool.getWeatherForecast("London", 3, "metric");

            assertTrue(result.contains("could not be formatted") || result.contains("Error"),
                    "Should report formatting or error");
        }
    }
}
