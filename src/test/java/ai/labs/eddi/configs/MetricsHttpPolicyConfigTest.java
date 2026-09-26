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
 * with OIDC on got 401 and there was nothing to set that changed it. It is now
 * an explicit rule whose policy comes from {@code eddi.metrics.http-policy}:
 * still {@code authenticated} by default, openable deliberately where the path
 * is not reachable from outside.
 * <p>
 * Read from the source tree for the same reason as {@link CspPolicyTest}: the
 * test-classpath application.properties shadows the main file.
 */
@DisplayName("metrics endpoint HTTP policy")
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
    @DisplayName("/q/metrics has its own rule, driven by eddi.metrics.http-policy, authenticated by default")
    void metricsPolicyIsExplicitAndAuthenticatedByDefault() throws Exception {
        var properties = applicationProperties();

        assertEquals(List.of("/q/metrics", "/q/metrics/*"),
                List.of(properties.getProperty("quarkus.http.auth.permission.metrics.paths").split(",")),
                "the metrics rule must cover the exposition path exactly");
        assertEquals("${eddi.metrics.http-policy:authenticated}",
                properties.getProperty("quarkus.http.auth.permission.metrics.policy"),
                "the rule's policy must come from eddi.metrics.http-policy and fall back to authenticated");
        assertEquals("authenticated", properties.getProperty("eddi.metrics.http-policy"),
                "the exposition describes the deployment; opening it is an operator's decision, not a default");
        assertEquals("GET,HEAD", properties.getProperty("quarkus.http.auth.permission.metrics.methods"));
    }
}
