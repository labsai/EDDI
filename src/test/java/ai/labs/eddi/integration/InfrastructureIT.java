/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
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
        // Served at /openapi, not the Quarkus default /q/openapi.
        // See quarkus.smallrye-openapi.path in application.properties.
        // Accepting 404 here would let the endpoint break unnoticed.
        given()
                .get("/openapi")
                .then().assertThat()
                .statusCode(200)
                .body(containsString("openapi"))
                .body(containsString("/agentstore/agents"));
    }

    @Test
    @Order(6)
    @DisplayName("Swagger UI should be accessible")
    void swaggerUi() {
        given().get("/q/swagger-ui")
                .then().assertThat()
                .statusCode(anyOf(equalTo(200), equalTo(301), equalTo(302)));
    }

    @Test
    @Order(10)
    @DisplayName("Swagger UI should receive exactly one relaxed CSP header")
    void swaggerUiCspHeader() {
        // Regression guard: if both the default and swagger CSP filters match this
        // path,
        // the browser receives two Content-Security-Policy headers and enforces the
        // most-restrictive intersection — breaking Swagger UI's inline scripts.
        // Follow redirects — /q/swagger-ui may 301→/q/swagger-ui/ in some profiles.
        var response = given().redirects().follow(true).get("/q/swagger-ui/");
        var cspHeaders = response.headers().getValues("Content-Security-Policy");
        // In test profiles, swagger-ui filters may not be active — skip gracefully
        Assumptions.assumeFalse(cspHeaders.isEmpty(),
                "Swagger UI CSP header not present in test profile — skipping assertion");
        Assertions.assertEquals(1, cspHeaders.size(),
                "Expected exactly 1 CSP header on /q/swagger-ui/ but got " + cspHeaders.size()
                        + ": " + cspHeaders);
        var csp = cspHeaders.getFirst();
        Assertions.assertTrue(csp.contains("'unsafe-inline'"),
                "Swagger UI CSP must allow 'unsafe-inline' for inline scripts: " + csp);
        Assertions.assertTrue(csp.contains("'unsafe-eval'"),
                "Swagger UI CSP must allow 'unsafe-eval' for JSON schema rendering: " + csp);
        // The GitHub exception belongs to the application policy alone. Swagger UI
        // never calls GitHub, and the two headers sit in one properties block edited
        // by hand — so a copy-paste that widens this one has to fail here.
        var swaggerConnectSrc = extractDirective(csp, "connect-src");
        Assertions.assertFalse(swaggerConnectSrc.contains("api.github.com"),
                "Swagger UI connect-src must NOT carry the GitHub exception: " + swaggerConnectSrc);
    }

    @Test
    @Order(11)
    @DisplayName("Non-Swagger paths should receive exactly one strict CSP header")
    void apiPathCspHeader() {
        var response = given().get("/q/health/ready");
        var cspHeaders = response.headers().getValues("Content-Security-Policy");
        Assertions.assertEquals(1, cspHeaders.size(),
                "Expected exactly 1 CSP header on /q/health/ready but got " + cspHeaders.size()
                        + ": " + cspHeaders);
        var csp = cspHeaders.getFirst();
        Assertions.assertTrue(csp.contains("script-src 'self'"),
                "Non-Swagger CSP must contain strict script-src: " + csp);
        // Extract just the script-src directive — style-src also has 'unsafe-inline'
        var scriptSrc = extractDirective(csp, "script-src");
        Assertions.assertFalse(scriptSrc.contains("'unsafe-inline'"),
                "Non-Swagger script-src must NOT allow 'unsafe-inline': " + scriptSrc);
        // The Manager's update check reads api.github.com from the browser. Without
        // this source the browser refuses the request before it leaves the page, and
        // the rejection is indistinguishable from an unreachable host — so tightening
        // this back kills the feature quietly rather than loudly.
        var connectSrc = extractDirective(csp, "connect-src");
        Assertions.assertTrue(allowsSource(connectSrc, "https://api.github.com"),
                "Non-Swagger connect-src must allow the Manager's release check: " + connectSrc);
    }

    /**
     * Extracts a single CSP directive value (e.g. "script-src 'self'") from a full
     * CSP string.
     */
    private static String extractDirective(String csp, String directive) {
        for (var part : csp.split(";")) {
            var trimmed = part.trim();
            var tokens = trimmed.split("\\s+", 2);
            if (tokens.length > 0 && tokens[0].equals(directive)) {
                return trimmed;
            }
        }
        return "";
    }

    /**
     * Whether a CSP directive lists exactly this source. Sources are
     * whitespace-delimited, and equality is the only safe test on the permissive
     * side: a substring match would also accept https://api.github.com.evil, which
     * is a different host permitting nothing we want. The prohibitive assertions
     * stay substring checks, where matching more broadly is stricter.
     */
    private static boolean allowsSource(String directive, String source) {
        for (var token : directive.trim().split("\\s+")) {
            if (token.equals(source)) {
                return true;
            }
        }
        return false;
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
