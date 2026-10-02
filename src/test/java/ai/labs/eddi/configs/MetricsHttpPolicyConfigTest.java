/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /q/metrics} used to be protected only by falling through to the
 * catch-all {@code authenticated} rule, so a Prometheus scraping an instance
 * with OIDC on got 401 and there was nothing to set that changed it. It is an
 * explicit rule whose policy comes from {@code eddi.metrics.http-policy}.
 * <p>
 * F7: {@code authenticated} admitted any token the realm issues, a role-less
 * one included, so the default is now a named role policy. The API description
 * ({@code /openapi}, Swagger UI) gets the same treatment with any EDDI role.
 * <p>
 * Read from the source tree for the same reason as {@link CspPolicyTest}: the
 * test-classpath application.properties shadows the main file.
 */
@DisplayName("metrics and API-description HTTP policies")
class MetricsHttpPolicyConfigTest {

    private static Properties applicationProperties() throws Exception {
        var path = Path.of(System.getProperty("basedir", ".")).resolve("src/main/resources/application.properties");
        assertTrue(Files.isRegularFile(path), "Expected the application config at " + path);
        var properties = new Properties();
        try (Reader in = Files.newBufferedReader(path)) {
            properties.load(in);
        }
        return properties;
    }

    @Test
    @DisplayName("/q/metrics has its own rule, driven by eddi.metrics.http-policy, a role by default")
    void metricsPolicyIsExplicitAndRoleGatedByDefault() throws Exception {
        var properties = applicationProperties();

        assertEquals(List.of("/q/metrics", "/q/metrics/*"),
                List.of(properties.getProperty("quarkus.http.auth.permission.metrics.paths").split(",")),
                "the metrics rule must cover the exposition path exactly");
        assertEquals("${eddi.metrics.http-policy:eddi-metrics-reader}",
                properties.getProperty("quarkus.http.auth.permission.metrics.policy"),
                "the rule's policy must come from eddi.metrics.http-policy and fall back to the role policy");
        assertEquals("eddi-metrics-reader", properties.getProperty("eddi.metrics.http-policy"),
                "the exposition describes the deployment; a token without a metrics role must not read it");
        assertEquals("${eddi.metrics.roles-allowed:eddi-admin,eddi-metrics}",
                properties.getProperty("quarkus.http.auth.policy.eddi-metrics-reader.roles-allowed"));
        assertEquals("eddi-admin,eddi-metrics", properties.getProperty("eddi.metrics.roles-allowed"));
        assertEquals("GET,HEAD", properties.getProperty("quarkus.http.auth.permission.metrics.methods"));
    }

    @Test
    @DisplayName("the API description has its own rule, any EDDI role by default")
    void apiDocsPolicyIsRoleGatedByDefault() throws Exception {
        var properties = applicationProperties();

        assertEquals(List.of("/openapi", "/openapi/*", "/q/openapi", "/q/openapi/*", "/q/swagger-ui", "/q/swagger-ui/*"),
                List.of(properties.getProperty("quarkus.http.auth.permission.api-docs.paths").split(",")));
        assertEquals("${eddi.api-docs.http-policy:eddi-api-docs-reader}",
                properties.getProperty("quarkus.http.auth.permission.api-docs.policy"));
        assertEquals("eddi-api-docs-reader", properties.getProperty("eddi.api-docs.http-policy"));
        assertEquals("${eddi.api-docs.roles-allowed:eddi-admin,eddi-editor,eddi-user,eddi-approver,eddi-viewer}",
                properties.getProperty("quarkus.http.auth.policy.eddi-api-docs-reader.roles-allowed"));
    }
}
