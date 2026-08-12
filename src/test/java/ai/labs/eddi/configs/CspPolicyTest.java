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
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The CSP headers are two hand-edited strings in one properties block, and the
 * difference between them is the whole policy: the application may reach
 * api.github.com (the Manager's release check runs in the browser), Swagger UI
 * may not, because it never calls GitHub.
 * <p>
 * {@code InfrastructureIT} asserts this over HTTP, which is the real proof —
 * but its Swagger case is guarded by an {@code Assumptions.assumeFalse} and is
 * skipped whenever the profile does not serve Swagger UI, which is every
 * integration run today. So the half of the boundary that says "do not widen
 * this one" was asserted nowhere that executes.
 * <p>
 * Reading the configuration directly costs no container and no assumption, so
 * both halves hold on every build.
 *
 * @since 6.3.0
 */
@DisplayName("CSP policy")
class CspPolicyTest {

    private static final String DEFAULT_HEADER = "quarkus.http.filter.csp-default.header.\"Content-Security-Policy\"";
    private static final String SWAGGER_HEADER = "quarkus.http.filter.csp-swagger.header.\"Content-Security-Policy\"";

    private static final String GITHUB_API = "https://api.github.com";

    /**
     * Read from the source tree rather than the classpath: {@code
     * src/test/resources/application.properties} shadows the main file for tests
     * and defines no CSP at all, so a classpath lookup finds nothing. The file
     * below is the artefact these assertions exist to protect.
     */
    private static Properties applicationProperties() throws Exception {
        var path = Path.of(System.getProperty("basedir", "."))
                .resolve("src/main/resources/application.properties");
        assertTrue(Files.isRegularFile(path), "Expected the application config at " + path);

        var properties = new Properties();
        // Properties.load joins the trailing-backslash continuations the CSP
        // headers are written across.
        try (Reader in = Files.newBufferedReader(path)) {
            properties.load(in);
        }
        return properties;
    }

    /** Isolates one directive, so a source in a neighbouring one cannot match. */
    private static String directive(String csp, String name) {
        for (var part : csp.split(";")) {
            var trimmed = part.trim();
            var tokens = trimmed.split("\\s+", 2);
            if (tokens.length > 0 && tokens[0].equals(name)) {
                return trimmed;
            }
        }
        return "";
    }

    /**
     * Whether a directive lists exactly this source.
     * <p>
     * Sources are whitespace-delimited, and equality is the only safe test on the
     * permissive side: {@code contains} would also accept
     * {@code https://api.github.com.evil}, a different host entirely that permits
     * nothing we want. The prohibitive assertions below stay substring checks on
     * purpose — there, matching more broadly is the stricter reading.
     */
    private static boolean allows(String directive, String source) {
        for (var token : directive.trim().split("\\s+")) {
            if (token.equals(source)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("the application policy may reach the GitHub API")
    void applicationConnectSrcAllowsGitHubApi() throws Exception {
        var csp = applicationProperties().getProperty(DEFAULT_HEADER);
        assertNotNull(csp, DEFAULT_HEADER + " must be configured");

        var connectSrc = directive(csp, "connect-src");
        assertTrue(allows(connectSrc, GITHUB_API),
                "The Manager's update check reads api.github.com from the browser; without this "
                        + "source the browser refuses it before it leaves the page: " + connectSrc);
    }

    @Test
    @DisplayName("the Swagger UI policy does not")
    void swaggerConnectSrcDoesNotAllowGitHubApi() throws Exception {
        var csp = applicationProperties().getProperty(SWAGGER_HEADER);
        assertNotNull(csp, SWAGGER_HEADER + " must be configured");

        var connectSrc = directive(csp, "connect-src");
        assertFalse(connectSrc.contains("api.github.com"),
                "Swagger UI never calls GitHub, so widening its connect-src is pure surface: "
                        + connectSrc);
    }

    @Test
    @DisplayName("neither policy relaxes anything else to reach it")
    void theExceptionIsScopedToConnectSrc() throws Exception {
        var properties = applicationProperties();

        for (var key : new String[]{DEFAULT_HEADER, SWAGGER_HEADER}) {
            var csp = properties.getProperty(key);
            assertNotNull(csp, key + " must be configured");
            // A source pasted into default-src would grant it to every fetch
            // directive that falls back, which is the opposite of a narrow
            // exception.
            assertFalse(directive(csp, "default-src").contains("api.github.com"),
                    key + " must keep the GitHub source out of default-src: " + csp);
            assertFalse(directive(csp, "script-src").contains("api.github.com"),
                    key + " must not allow scripts from GitHub: " + csp);
        }
    }
}
