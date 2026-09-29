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
    private static final String CHAT_HEADER = "quarkus.http.filter.csp-chat.header.\"Content-Security-Policy\"";

    private static final String DEFAULT_XFO = "quarkus.http.filter.csp-default.header.\"X-Frame-Options\"";
    private static final String SWAGGER_XFO = "quarkus.http.filter.csp-swagger.header.\"X-Frame-Options\"";
    private static final String CHAT_XFO = "quarkus.http.filter.csp-chat.header.\"X-Frame-Options\"";
    private static final String GLOBAL_XFO = "quarkus.http.header.X-Frame-Options.value";

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

    @Test
    @DisplayName("every policy sets form-action, base-uri and object-src (none fall back to default-src)")
    void everyPolicyLocksDownFormActionBaseUriObjectSrc() throws Exception {
        var properties = applicationProperties();
        for (var key : new String[]{DEFAULT_HEADER, SWAGGER_HEADER, CHAT_HEADER}) {
            var csp = properties.getProperty(key);
            assertNotNull(csp, key + " must be configured");
            assertTrue(allows(directive(csp, "form-action"), "'self'"),
                    key + " must restrict form-action to 'self': " + csp);
            assertTrue(allows(directive(csp, "base-uri"), "'self'"),
                    key + " must restrict base-uri to 'self': " + csp);
            assertTrue(allows(directive(csp, "object-src"), "'none'"),
                    key + " must forbid plugins via object-src 'none': " + csp);
        }
    }

    @Test
    @DisplayName("the chat widget's framing is allow-list driven and denied by default")
    void chatFramingIsAllowListDrivenAndDeniedByDefault() throws Exception {
        var properties = applicationProperties();
        var chat = properties.getProperty(CHAT_HEADER);
        assertNotNull(chat, CHAT_HEADER + " must be configured");
        var frameAncestors = directive(chat, "frame-ancestors");
        // Operator-set allow-list, defaulting to 'none' so /chat stays un-framable
        // until EDDI_CHAT_FRAME_ANCESTORS is provided.
        assertTrue(frameAncestors.contains("eddi.chat.frame-ancestors"),
                "chat frame-ancestors must be operator-configurable: " + frameAncestors);
        assertTrue(frameAncestors.contains("'none'"),
                "chat frame-ancestors must default to 'none': " + frameAncestors);

        // Every non-chat surface stays a blanket deny.
        for (var key : new String[]{DEFAULT_HEADER, SWAGGER_HEADER}) {
            var csp = properties.getProperty(key);
            assertTrue(allows(directive(csp, "frame-ancestors"), "'none'"),
                    key + " must keep frame-ancestors 'none': " + csp);
        }
    }

    @Test
    @DisplayName("X-Frame-Options is DENY everywhere except /chat, and never set globally")
    void xFrameOptionsIsPerPathAndAbsentOnChat() throws Exception {
        var properties = applicationProperties();
        // A global XFO would be sent on /chat too and veto its frame-ancestors
        // allow-list — it must live on the path-scoped filters instead.
        assertNull(properties.getProperty(GLOBAL_XFO),
                "X-Frame-Options must not be set globally (it would override /chat's frame-ancestors)");
        assertEquals("DENY", properties.getProperty(DEFAULT_XFO),
                "the default filter must send X-Frame-Options: DENY");
        assertEquals("DENY", properties.getProperty(SWAGGER_XFO),
                "the Swagger filter must send X-Frame-Options: DENY");
        assertNull(properties.getProperty(CHAT_XFO),
                "/chat must send NO X-Frame-Options so its frame-ancestors allow-list governs framing");
    }
}
