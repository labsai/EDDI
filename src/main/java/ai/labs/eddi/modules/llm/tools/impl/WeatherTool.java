/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.httpclient.SafeHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Weather service tool for retrieving current weather information. Supports
 * OpenWeatherMap API (free tier available). Uses IJsonSerialization for proper
 * JSON parsing.
 */
@ApplicationScoped
public class WeatherTool {
    private static final Logger LOGGER = Logger.getLogger(WeatherTool.class);
    private final SafeHttpClient httpClient;

    @Inject
    IJsonSerialization jsonSerialization;

    @ConfigProperty(name = "eddi.tools.weather.openweathermap.api-key")
    Optional<String> openWeatherMapApiKey;

    @Inject
    public WeatherTool(SafeHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Tool("Gets current weather information for a city. Returns temperature, conditions, humidity, and wind speed.")
    public String getCurrentWeather(@P("city") String city, @P("units") String units) {

        if (openWeatherMapApiKey.isEmpty()) {
            return "Error: Weather API key not configured. Please set eddi.tools.weather.openweathermap.api-key in application.properties";
        }

        if (units == null || units.isEmpty()) {
            units = "metric";
        }

        try {
            LOGGER.info("Getting weather for: " + city);

            String encodedCity = URLEncoder.encode(city, StandardCharsets.UTF_8);
            String url = String.format("https://api.openweathermap.org/data/2.5/weather?q=%s&appid=%s&units=%s", encodedCity,
                    openWeatherMapApiKey.get(), units);

            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 404) {
                return "Error: City '" + city + "' not found. Please check the city name.";
            }

            if (response.statusCode() != 200) {
                throw new IOException("Weather API returned status: " + response.statusCode());
            }

            return formatWeatherResponse(response.body(), city, units);

        } catch (Exception e) {
            LOGGER.error("Weather lookup error for " + city + ": " + e.getMessage(), e);
            return "Error: Could not retrieve weather information - " + e.getMessage();
        }
    }

    @Tool("Gets weather forecast for the next few days")
    public String getWeatherForecast(@P("city") String city, @P("days") Integer days, @P("units") String units) {

        if (openWeatherMapApiKey.isEmpty()) {
            return "Error: Weather API key not configured.";
        }

        if (units == null || units.isEmpty()) {
            units = "metric";
        }

        if (days == null || days < 1) {
            days = 3;
        }
        if (days > 5) {
            days = 5;
        }

        try {
            LOGGER.info("Getting " + days + "-day forecast for: " + city);

            String encodedCity = URLEncoder.encode(city, StandardCharsets.UTF_8);
            String url = String.format("https://api.openweathermap.org/data/2.5/forecast?q=%s&appid=%s&units=%s&cnt=%d", encodedCity,
                    openWeatherMapApiKey.get(), units, days * 8);

            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 404) {
                return "Error: City '" + city + "' not found.";
            }

            if (response.statusCode() != 200) {
                throw new IOException("Weather API returned status: " + response.statusCode());
            }

            return formatForecastResponse(response.body(), city, days, units);

        } catch (Exception e) {
            LOGGER.error("Weather forecast error: " + e.getMessage(), e);
            return "Error: Could not retrieve weather forecast - " + e.getMessage();
        }
    }

    private String formatWeatherResponse(String jsonResponse, String city, String units) {
        try {
            JsonNode root = jsonSerialization.deserialize(jsonResponse, JsonNode.class);

            StringBuilder result = new StringBuilder();
            result.append("Current weather for ").append(city).append(":\n\n");

            JsonNode main = root.get("main");
            JsonNode weather = root.get("weather");
            JsonNode wind = root.get("wind");

            String tempUnit = "metric".equals(units) ? "°C" : "°F";
            String speedUnit = "metric".equals(units) ? "m/s" : "mph";

            if (main != null) {
                double temp = main.get("temp").asDouble();
                double feelsLike = main.get("feels_like").asDouble();
                int humidity = main.get("humidity").asInt();

                result.append("Temperature: ").append(String.format("%.1f", temp)).append(tempUnit);
                result.append(" (feels like ").append(String.format("%.1f", feelsLike)).append(tempUnit).append(")\n");
                result.append("Humidity: ").append(humidity).append("%\n");
            }

            if (weather != null && weather.isArray() && !weather.isEmpty()) {
                String description = weather.get(0).get("description").asText();
                result.append("Conditions: ").append(description).append("\n");
            }

            if (wind != null) {
                double speed = wind.get("speed").asDouble();
                result.append("Wind Speed: ").append(String.format("%.1f", speed)).append(" ").append(speedUnit).append("\n");
            }

            LOGGER.debug("Weather data formatted for " + city);
            return result.toString();

        } catch (Exception e) {
            LOGGER.error("Error formatting weather response", e);
            return "Weather data retrieved but could not be formatted: " + e.getMessage();
        }
    }

    private String formatForecastResponse(String jsonResponse, String city, int days, String units) {
        try {
            JsonNode root = jsonSerialization.deserialize(jsonResponse, JsonNode.class);
            JsonNode list = root.get("list");

            if (list == null || !list.isArray()) {
                return "No forecast data available";
            }

            StringBuilder result = new StringBuilder();
            result.append(days).append("-day forecast for ").append(city).append(":\n\n");

            String tempUnit = "metric".equals(units) ? "°C" : "°F";
            String lastDate = "";
            int count = 0;

            for (JsonNode forecast : list) {
                if (count >= days)
                    break;

                String dateTime = forecast.get("dt_txt").asText();
                String date = dateTime.substring(0, 10);

                if (!date.equals(lastDate)) {
                    if (count > 0)
                        result.append("\n");

                    JsonNode main = forecast.get("main");
                    JsonNode weather = forecast.get("weather");

                    double temp = main.get("temp").asDouble();
                    String description = weather.get(0).get("description").asText();

                    result.append("Day ").append(count + 1).append(" (").append(date).append("): ");
                    result.append(String.format("%.1f", temp)).append(tempUnit);
                    result.append(", ").append(description).append("\n");

                    lastDate = date;
                    count++;
                }
            }

            return result.toString();

        } catch (Exception e) {
            LOGGER.error("Error formatting forecast response", e);
            return "Forecast data retrieved but could not be formatted: " + e.getMessage();
        }
    }
}
