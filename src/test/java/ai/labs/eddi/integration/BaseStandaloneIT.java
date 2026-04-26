/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration;

import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;

import java.net.URI;

/**
 * Base class for standalone integration tests that run against a <b>live EDDI
 * instance</b> (not {@code @QuarkusTest}).
 * <p>
 * Provides shared infrastructure:
 * <ul>
 * <li>Configures base URL from system property
 * ({@code -Deddi.base-url=...})</li>
 * <li>Skips all tests if EDDI is not running (via JUnit Assumptions)</li>
 * <li>Shared helper methods for resource creation and cleanup</li>
 * </ul>
 * <p>
 * Running:
 *
 * <pre>
 *   # Start EDDI first (e.g. mvn quarkus:dev), then:
 *   mvn verify -Dit.test=McpEndpointIT -Deddi.base-url=http://localhost:7070
 * </pre>
 */
@Tag("running-instance")
public abstract class BaseStandaloneIT {

    protected static String baseUrl;

    @BeforeAll
    static void configureAndCheckConnection() {
        baseUrl = System.getProperty("eddi.base-url", "http://localhost:7070");
        RestAssured.baseURI = baseUrl;

        // Skip all tests if EDDI is not running
        boolean reachable = false;
        try {
            Response response = RestAssured.given()
                    .relaxedHTTPSValidation()
                    .get("/q/health/live");
            reachable = response.getStatusCode() == 200;
        } catch (Exception e) {
            // connection refused, timeout, etc.
        }

        Assumptions.assumeTrue(reachable,
                "EDDI is not running at " + baseUrl + " — skipping standalone tests. "
                        + "Start EDDI first (e.g. 'mvn quarkus:dev') or set -Deddi.base-url=<url>");
    }

    // ==================== Helpers ====================

    protected static ResourceId extractResourceId(String uriString) {
        URI uri = URI.create(uriString);
        String path = uri.getPath();
        String id = path.substring(path.lastIndexOf('/') + 1);
        String query = uri.getQuery();
        int version = 1;
        if (query != null && query.startsWith("version=")) {
            version = Integer.parseInt(query.substring("version=".length()));
        }
        return new ResourceId(id, version);
    }

    public record ResourceId(String id, int version) {
    }
}
