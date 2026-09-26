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
import java.util.regex.Pattern;

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

    /**
     * Which filter owns which path. Two matching filters send two CSP headers and
     * the browser enforces their intersection, so /chat must match the chat filter
     * and NOT the default one, and the swagger split must survive the new
     * exclusion. Vert.x matches a filter's regex against the whole path.
     */
    @Test
    @DisplayName("each path is matched by exactly one CSP filter")
    void everyPathHasExactlyOnePolicy() throws Exception {
        var properties = applicationProperties();
        var defaultFilter = Pattern.compile(properties.getProperty("quarkus.http.filter.csp-default.matches"));
        var swaggerFilter = Pattern.compile(properties.getProperty("quarkus.http.filter.csp-swagger.matches"));
        var chatFilter = Pattern.compile(properties.getProperty("quarkus.http.filter.csp-chat.matches"));

        for (var path : new String[]{"/chat", "/chat/", "/chat/production/6630a1b2c3d4e5f6a7b8c9d0"}) {
            assertTrue(chatFilter.matcher(path).matches(), path + " must get the chat policy");
            assertFalse(defaultFilter.matcher(path).matches(), path + " must not ALSO get the default policy");
            assertFalse(swaggerFilter.matcher(path).matches(), path);
        }
        for (var path : new String[]{"/manage", "/manage/agents", "/", "/q/health/ready", "/chatter", "/agents/x"}) {
            assertTrue(defaultFilter.matcher(path).matches(), path + " must get the default policy");
            assertFalse(chatFilter.matcher(path).matches(), path + " must not get the chat policy");
        }
        for (var path : new String[]{"/q/swagger-ui", "/q/swagger-ui/index.html"}) {
            assertTrue(swaggerFilter.matcher(path).matches(), path);
            assertFalse(defaultFilter.matcher(path).matches(), path);
            assertFalse(chatFilter.matcher(path).matches(), path);
        }
    }

    /**
     * {@code X-Frame-Options: DENY} used to be a global header, and
     * {@code frame-ancestors 'none'} covered /chat as well — so the iframe embed
     * the Chat UI documents could never render. The chat's framing is an operator
     * setting now, still refusing every embedder by default; everything else keeps
     * refusing to be framed.
     */
    @Test
    @DisplayName("the chat's frame-ancestors is configurable and closed by default; the rest stays DENY")
    void chatFramingIsConfigurableAndClosedByDefault() throws Exception {
        var properties = applicationProperties();
        assertNull(properties.getProperty("quarkus.http.header.X-Frame-Options.value"),
                "X-Frame-Options must not be a global header — it also reaches /chat, where it cannot express an "
                        + "allow-list and blocks the documented embed");
        assertEquals("DENY", properties.getProperty("quarkus.http.filter.csp-default.header.X-Frame-Options"));
        assertEquals("DENY", properties.getProperty("quarkus.http.filter.csp-swagger.header.X-Frame-Options"));

        assertEquals("'none'", properties.getProperty("eddi.chat.frame-ancestors"),
                "the chat must refuse every embedder until an operator lists one");
        var chat = properties.getProperty(CHAT_HEADER);
        assertNotNull(chat, CHAT_HEADER + " must be configured");
        assertEquals("frame-ancestors ${eddi.chat.frame-ancestors:'none'}", directive(chat, "frame-ancestors"),
                "the chat policy must take frame-ancestors from eddi.chat.frame-ancestors, falling back to 'none'");
        for (var key : new String[]{DEFAULT_HEADER, SWAGGER_HEADER}) {
            assertEquals("frame-ancestors 'none'", directive(properties.getProperty(key), "frame-ancestors"), key);
        }
        // The chat is otherwise the application policy: no script relaxation rides
        // in with the framing change.
        assertEquals(directive(properties.getProperty(DEFAULT_HEADER), "script-src"), directive(chat, "script-src"));
        assertFalse(directive(chat, "connect-src").contains("api.github.com"),
                "the chat never calls GitHub; only the Manager's update check does");
    }

    /**
     * The Manager previews staged image attachments through
     * {@code URL.createObjectURL}, and the agent wizard can fetch an OpenAPI spec
     * from a URL. Under {@code img-src 'self' data:} every thumbnail was a broken
     * image; the spec fetch has no host that could be named in advance, so it is an
     * operator setting that stays empty (strict) unless set.
     */
    @Test
    @DisplayName("the application policy renders blob: previews and takes extra connect sources from config")
    void applicationPolicyAllowsBlobImagesAndConfiguredConnectSources() throws Exception {
        var properties = applicationProperties();
        var csp = properties.getProperty(DEFAULT_HEADER);
        assertTrue(allows(directive(csp, "img-src"), "blob:"), "img-src must allow blob: previews: " + csp);
        assertTrue(allows(directive(csp, "connect-src"), "${eddi.csp.extra-connect-sources:}"),
                "connect-src must append eddi.csp.extra-connect-sources: " + csp);
        assertEquals("", properties.getProperty("eddi.csp.extra-connect-sources"),
                "extra connect sources must default to none — widening connect-src is an operator's call");
        assertFalse(allows(directive(csp, "connect-src"), "https:"),
                "connect-src must not allow every https host by default: " + csp);
    }
}
