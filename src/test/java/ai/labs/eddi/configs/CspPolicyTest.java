/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.vertx.core.http.impl.HttpUtils;

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

    /**
     * Agent designers configure image output items whose URLs are hosted elsewhere.
     * Under {@code img-src 'self' data: blob:} alone the widget's own CSP refused
     * every one of them. The extra sources are an operator setting, defaulting to
     * {@code https:} so the feature works out of the box, and the widening stays on
     * /chat: the Manager and Swagger UI keep their own img-src.
     */
    @Test
    @DisplayName("the chat widget's img-src takes designer image hosts from config (https: by default), and only there")
    void chatImgSrcIsConfigurableAndScopedToChat() throws Exception {
        var properties = applicationProperties();
        var chatImg = directive(properties.getProperty(CHAT_HEADER), "img-src");
        assertTrue(allows(chatImg, "${eddi.chat.img-sources:}"),
                "chat img-src must append eddi.chat.img-sources: " + chatImg);
        assertTrue(allows(chatImg, "'self'") && allows(chatImg, "data:") && allows(chatImg, "blob:"),
                "chat img-src must keep 'self' data: blob: " + chatImg);
        assertEquals("https:", properties.getProperty("eddi.chat.img-sources"),
                "designer images from https hosts must render by default");

        for (var key : new String[]{DEFAULT_HEADER, SWAGGER_HEADER}) {
            var img = directive(properties.getProperty(key), "img-src");
            assertFalse(img.contains("eddi.chat.img-sources") || allows(img, "https:"),
                    key + " must not inherit the chat widget's image sources: " + img);
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
        for (var path : new String[]{"/q/swagger-ui", "/q/swagger-ui/", "/q/swagger-ui/index.html",
                "/q/swagger-ui/swagger-ui.css"}) {
            assertTrue(swaggerFilter.matcher(path).matches(), path + " must get the Swagger policy");
            assertFalse(defaultFilter.matcher(path).matches(), path + " must not ALSO get the default policy");
            assertFalse(chatFilter.matcher(path).matches(), path);
        }
        // The other /q routes are not Swagger UI and keep the strict policy.
        for (var path : new String[]{"/q/openapi", "/q/metrics", "/q/swagger-ui-x", "/q/dev-ui"}) {
            assertTrue(defaultFilter.matcher(path).matches(), path + " must get the default policy");
            assertFalse(swaggerFilter.matcher(path).matches(), path + " must not get the Swagger policy");
        }
    }

    /**
     * The regexes above are matched against the path as Vert.x normalises it
     * ({@code RoutingContext.normalizedPath()}, which is {@code
     * HttpUtils.normalizePath}: dot segments resolved, unreserved characters
     * decoded, {@code %2F} and {@code //} left alone), so the spellings an attacker
     * would try are fed through the same function here. What has to hold: the chat
     * policy — the one whose frame-ancestors is an operator allow-list — is applied
     * only to a path that is unambiguously under {@code /chat}, and everything else
     * keeps the blanket deny.
     */
    @Test
    @DisplayName("path tricks cannot borrow the chat policy's framing allow-list")
    void pathTricksKeepTheStrictPolicy() throws Exception {
        var properties = applicationProperties();
        var defaultFilter = Pattern.compile(properties.getProperty("quarkus.http.filter.csp-default.matches"));
        var chatFilter = Pattern.compile(properties.getProperty("quarkus.http.filter.csp-chat.matches"));

        // A dot segment is resolved before any route or filter sees the path, so
        // this is /manage and gets the default policy. A percent-encoded slash is
        // not decoded, and a different case is a different path: both 404 with the
        // default policy. A matrix parameter on the first segment ("/chat;x=1")
        // is stripped by JAX-RS when it picks the chat shell, but the filter saw
        // "chat;" and gave it the default policy — strict, so safe.
        assertEquals("/manage", HttpUtils.normalizePath("/chat/../manage"));
        for (var raw : new String[]{"/chat/../manage", "/%2Fchat", "/%2fchat", "/CHAT", "/Chat/production/x",
                "/chat%2F../manage", "/chat;x=1/production/x", "/chatter"}) {
            var path = HttpUtils.normalizePath(raw);
            assertTrue(defaultFilter.matcher(path).matches(), raw + " (normalised: " + path + ") must get the default policy");
            assertFalse(chatFilter.matcher(path).matches(), raw + " (normalised: " + path + ") must not get the chat policy");
        }
        // Collapsed and matrix-parameter spellings that remain the chat shell —
        // Vert.x folds "//chat" to "/chat", and "..;" is not a dot segment, so
        // "/chat/..;/manage" stays under /chat and the chat catch-all serves
        // chat.html (verified live). The chat policy is the right one for the chat
        // shell, and the default filter must not match too, or the browser
        // enforces the intersection and frames nothing.
        assertEquals("/chat", HttpUtils.normalizePath("//chat"));
        for (var raw : new String[]{"//chat", "//chat/production/x", "/chat/..;/manage"}) {
            var path = HttpUtils.normalizePath(raw);
            assertTrue(path.startsWith("/chat"), raw + " normalised to " + path);
            assertTrue(chatFilter.matcher(path).matches(), raw + " (normalised: " + path + ") stays a chat path");
            assertFalse(defaultFilter.matcher(path).matches(), raw + " must not ALSO get the default policy");
        }
    }

    /**
     * Quarkus hands a filter's {@code order} to Vert.x {@code Route.order()} as is,
     * and Vert.x runs routes in ascending order. The {@code /q} sub-router (health,
     * metrics, Swagger UI) is mounted on the same router as a route whose order is
     * its creation sequence number — zero or above — so a filter with a positive
     * order can run after that mount has already answered the request. At
     * {@code order=20} the Swagger filter never ran: {@code
     * /q/swagger-ui/} was served with no Content-Security-Policy and no
     * X-Frame-Options at all (2026-10-15 security review, L11). A negative order
     * runs before every sequence-ordered route.
     */
    @Test
    @DisplayName("every CSP filter runs before the /q sub-router mount (negative order)")
    void everyCspFilterRunsBeforeTheFrameworkRouter() throws Exception {
        var properties = applicationProperties();
        for (var filter : new String[]{"csp-default", "csp-chat", "csp-swagger"}) {
            var key = "quarkus.http.filter." + filter + ".order";
            var order = properties.getProperty(key);
            assertNotNull(order, key + " must be set: unset, Quarkus uses Integer.MIN_VALUE, which is fine, "
                    + "but the three filters are ordered relative to each other on purpose");
            assertTrue(Integer.parseInt(order.trim()) < 0,
                    key + "=" + order + " is not negative: Vert.x runs routes in ascending order and the /q "
                            + "sub-router mount has a non-negative sequence order, so a positive filter order can "
                            + "sit behind it and never run — which is how Swagger UI lost its CSP");
        }
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
