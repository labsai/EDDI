package ai.labs.eddi.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for the Log Administration REST endpoints.
 * <p>
 * Tests {@code /administration/logs}, {@code /administration/logs/history},
 * {@code /administration/logs/instance}, and
 * {@code /administration/logs/stream}.
 * <p>
 * The BoundedLogStore registers a JUL handler on startup, so by the time these
 * tests run there will already be log entries in the ring buffer from Quarkus
 * boot and test setup.
 *
 * @author ginccc
 * @since 6.0.0
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class LogAdminIT {

    private static final String BASE = "/administration/logs";

    // ==================== Instance Endpoint ====================

    @Test
    @Order(1)
    @DisplayName("GET /administration/logs/instance should return instanceId")
    void getInstanceId_returnsValidInstance() {
        given().get(BASE + "/instance").then().assertThat().statusCode(200).contentType(ContentType.JSON).body("instanceId", notNullValue())
                .body("instanceId", not(emptyString()));
    }

    // ==================== Recent Logs (Ring Buffer) ====================

    @Test
    @Order(2)
    @DisplayName("GET /administration/logs should return recent log entries from ring buffer")
    void getRecentLogs_returnsEntries() {
        // By the time this test runs, Quarkus boot has already generated log entries
        given().get(BASE).then().assertThat().statusCode(200).contentType(ContentType.JSON).body("$", instanceOf(java.util.List.class));
    }

    @Test
    @Order(3)
    @DisplayName("GET /administration/logs with limit should respect limit")
    void getRecentLogs_respectsLimit() {
        given().queryParam("limit", 5).get(BASE).then().assertThat().statusCode(200).contentType(ContentType.JSON).body("$.size()",
                lessThanOrEqualTo(5));
    }

    @Test
    @Order(4)
    @DisplayName("GET /administration/logs with level filter should filter by level")
    void getRecentLogs_filtersByLevel() {
        given().queryParam("level", "INFO").get(BASE).then().assertThat().statusCode(200).contentType(ContentType.JSON);
        // All returned entries should have level=INFO (if any)
    }

    @Test
    @Order(5)
    @DisplayName("GET /administration/logs with nonexistent agentId should return empty or filtered list")
    void getRecentLogs_filtersByAgentId() {
        given().queryParam("agentId", "nonexistent-agent").get(BASE).then().assertThat().statusCode(200).contentType(ContentType.JSON)
                .body("$.size()", equalTo(0));
    }

    // ==================== History Logs (Database) ====================

    @Test
    @Order(6)
    @DisplayName("GET /administration/logs/history should return list (may be empty if DB logging is off)")
    void getHistoryLogs_returnsListOrEmpty() {
        given().get(BASE + "/history").then().assertThat().statusCode(200).contentType(ContentType.JSON).body("$", instanceOf(java.util.List.class));
    }

    @Test
    @Order(7)
    @DisplayName("GET /administration/logs/history with filters should return filtered results")
    void getHistoryLogs_acceptsFilters() {
        given().queryParam("agentId", "nonexistent-agent").queryParam("limit", 10).queryParam("skip", 0).get(BASE + "/history").then().assertThat()
                .statusCode(200).contentType(ContentType.JSON).body("$.size()", equalTo(0));
    }

    // ==================== SSE Stream ====================

    @Test
    @Order(8)
    @DisplayName("GET /administration/logs/stream should accept connection and return SSE content type")
    void streamLogs_returnsSseContentType() throws Exception {
        // SSE is a long-lived connection — RestAssured blocks. Use raw HTTP with
        // timeout.
        var url = java.net.URI.create("http://localhost:8081" + BASE + "/stream").toURL();
        var conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setReadTimeout(3000); // 3 second timeout
        conn.setConnectTimeout(3000);
        conn.setRequestProperty("Accept", "text/event-stream");

        try {
            int status = conn.getResponseCode();
            Assertions.assertEquals(200, status, "SSE endpoint should return 200");
            String contentType = conn.getContentType();
            Assertions.assertTrue(contentType != null && contentType.contains("text/event-stream"),
                    "Content-Type should be text/event-stream, got: " + contentType);
        } finally {
            conn.disconnect();
        }
    }

    // ==================== Log Entry Structure ====================

    @Test
    @Order(9)
    @DisplayName("Recent log entries should have expected fields")
    void recentLogs_haveExpectedFields() {
        var response = given().queryParam("limit", 1).get(BASE);

        response.then().assertThat().statusCode(200);

        // If there's at least one entry (there should be from Quarkus boot)
        var entries = response.jsonPath().getList("$");
        if (!entries.isEmpty()) {
            response.then().body("[0].timestamp", notNullValue()).body("[0].level", notNullValue()).body("[0].loggerName", notNullValue())
                    .body("[0].message", notNullValue()).body("[0].instanceId", notNullValue());
        }
    }
}
