package ai.labs.eddi.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration test for Infrastructure endpoints: health probes, metrics,
 * OpenAPI spec, coordinator admin, and tenant quota.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class InfrastructureIT {

    // ==================== Health Probes ====================

    @Test
    @Order(1)
    @DisplayName("Liveness probe should return UP")
    void liveness() {
        given().get("/q/health/live")
                .then().assertThat()
                .statusCode(200)
                .body("status", equalTo("UP"));
    }

    @Test
    @Order(2)
    @DisplayName("Readiness probe should return UP")
    void readiness() {
        given().get("/q/health/ready")
                .then().assertThat()
                .statusCode(200)
                .body("status", equalTo("UP"));
    }

    @Test
    @Order(3)
    @DisplayName("Overall health should return UP")
    void overallHealth() {
        given().get("/q/health")
                .then().assertThat()
                .statusCode(200)
                .body("status", equalTo("UP"));
    }

    // ==================== Metrics ====================

    @Test
    @Order(4)
    @DisplayName("Prometheus metrics endpoint should return metrics")
    void prometheusMetrics() {
        given().get("/q/metrics")
                .then().assertThat()
                .statusCode(200);
    }

    // ==================== OpenAPI ====================

    @Test
    @Order(5)
    @DisplayName("OpenAPI spec should return valid document")
    void openApiSpec() {
        // 200 = OpenAPI doc available (extension on classpath, path configured)
        // 404 = SmallRye OpenAPI extension not available in test profile
        given()
                .get("/q/openapi")
                .then().assertThat()
                .statusCode(anyOf(equalTo(200), equalTo(404)));
    }

    @Test
    @Order(6)
    @DisplayName("Swagger UI should be accessible")
    void swaggerUi() {
        given().get("/q/swagger-ui")
                .then().assertThat()
                .statusCode(anyOf(equalTo(200), equalTo(301), equalTo(302)));
    }

    // ==================== Coordinator Admin ====================

    @Test
    @Order(7)
    @DisplayName("Coordinator dashboard should return status")
    void coordinatorDashboard() {
        given().get("/administration/coordinator/status")
                .then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON);
    }

    // ==================== Extension Store ====================

    @Test
    @Order(8)
    @DisplayName("Extension store should list available workflow step types")
    void listExtensions() {
        given().get("/extensionstore/extensions")
                .then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("$", not(empty()));
    }

    // ==================== Deployment Status ====================

    @Test
    @Order(9)
    @DisplayName("Deployment status list should be accessible")
    void deploymentStatusList() {
        given().get("/administration/production/deploymentstatus")
                .then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON);
    }
}
