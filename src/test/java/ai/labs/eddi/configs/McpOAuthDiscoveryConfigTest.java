/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.Reader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EDDI advertises {@code /mcp} as an OAuth 2.0 protected resource (RFC 9728) so
 * an MCP client discovers where to authenticate and then refreshes its own
 * token. Serving the document is quarkus-oidc's job, which means the whole
 * feature is five properties — and every way it can be wrong is silent:
 * <ul>
 * <li>Without the {@code permit} rule the document sits behind the catch-all
 * {@code authenticated} policy and answers 401. A client cannot read the thing
 * that tells it how to authenticate, so discovery never starts and the feature
 * is a no-op. quarkus-oidc registers its handler as a Vert.x filter at priority
 * 50 while {@code SecurityHandlerPriorities.AUTHORIZATION} is 100, and higher
 * runs first — so authorization genuinely gets there first.</li>
 * <li>If {@code authorization-server} falls back to {@code auth-server-url},
 * the document names the cluster-internal Keycloak address that every shipped
 * deployment uses, and a client on a laptop is told to log in at a host it
 * cannot resolve.</li>
 * <li>If the permit rule is widened to {@code /.well-known/*} or beyond, it
 * starts pre-permitting paths nobody reviewed.</li>
 * </ul>
 * None of that shows up in a unit test of any class, because there is no class.
 * It shows up here, or in production.
 *
 * @since 6.4.0
 */
@DisplayName("MCP OAuth discovery configuration")
class McpOAuthDiscoveryConfigTest {

    private static final String ENABLED = "quarkus.oidc.resource-metadata.enabled";
    private static final String RESOURCE = "quarkus.oidc.resource-metadata.resource";
    private static final String AUTHORIZATION_SERVER = "quarkus.oidc.resource-metadata.authorization-server";
    private static final String SCOPES = "quarkus.oidc.resource-metadata.scopes";

    private static final String METADATA_PATHS = "quarkus.http.auth.permission.oauth-resource-metadata.paths";
    private static final String METADATA_POLICY = "quarkus.http.auth.permission.oauth-resource-metadata.policy";
    private static final String METADATA_METHODS = "quarkus.http.auth.permission.oauth-resource-metadata.methods";

    private static final String MCP_ROOT_PATH = "quarkus.mcp.server.http.root-path";
    private static final String MCP_POLICY = "quarkus.http.auth.permission.mcp.policy";

    /** RFC 9728 §3.1 — the fixed prefix quarkus-oidc serves the document under. */
    private static final String WELL_KNOWN = "/.well-known/oauth-protected-resource";

    /**
     * Read from the source tree rather than the classpath: {@code
     * src/test/resources/application.properties} shadows the main file for tests,
     * and the file below is the artefact these assertions exist to protect.
     */
    private static Properties applicationProperties() throws Exception {
        var path = Path.of(System.getProperty("basedir", "."))
                .resolve("src/main/resources/application.properties");
        assertTrue(Files.isRegularFile(path), "Expected the application config at " + path);

        var properties = new Properties();
        // Properties.load joins the trailing-backslash continuation the permit
        // rule's two paths are written across.
        try (Reader in = Files.newBufferedReader(path)) {
            properties.load(in);
        }
        return properties;
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        assertTrue(value != null && !value.isBlank(),
                key + " is not set — MCP OAuth discovery does not work without it");
        return value.trim();
    }

    /**
     * An unauthenticated instance has no authorization server to name, and
     * publishing a discovery document for one would invite a flow that cannot
     * succeed. Pinning the expression rather than the resolved value is the point:
     * a hardcoded {@code true} would advertise on a demo instance, and a hardcoded
     * {@code false} would silently disable the feature everywhere.
     */
    @Test
    @DisplayName("metadata is advertised exactly when authentication is on")
    void metadataTracksTenantEnabled() throws Exception {
        assertEquals("${quarkus.oidc.tenant-enabled}", required(applicationProperties(), ENABLED),
                ENABLED + " must track tenant-enabled rather than carry a literal");
    }

    /**
     * The advertised resource identifier has to name the endpoint the client is
     * actually being challenged on. A client validates this — EDDI's own outbound
     * client does, in {@code McpAuthChallengeParser.describesResource} — and
     * rejects metadata describing something else.
     */
    @Test
    @DisplayName("the advertised resource is the MCP endpoint")
    void advertisedResourceIsTheMcpEndpoint() throws Exception {
        var properties = applicationProperties();
        String resource = required(properties, RESOURCE);
        String mcpRootPath = required(properties, MCP_ROOT_PATH);

        if (resource.startsWith("/")) {
            assertEquals(mcpRootPath, resource,
                    RESOURCE + " must name the path MCP is actually served at (" + MCP_ROOT_PATH + ")");
        } else {
            // An absolute URL is legitimate for a deployment behind a proxy, but it
            // then has to be https and still point at the MCP path.
            URI absolute = URI.create(resource);
            assertEquals("https", absolute.getScheme(),
                    RESOURCE + " is absolute, so it must be https — an http identifier invites a downgrade");
            assertEquals(mcpRootPath, absolute.getPath(),
                    RESOURCE + " is absolute but does not point at " + mcpRootPath);
        }
    }

    /**
     * {@code docker-compose.auth.yml} and the helm chart both point
     * {@code auth-server-url} at an in-cluster address and set the public one as
     * {@code token.issuer}. Defaulting to the former publishes a URL only the
     * cluster can reach, and RFC 8414 wants the advertised authorization server to
     * equal the issuer in any case.
     */
    @Test
    @DisplayName("the advertised authorization server is the public issuer")
    void authorizationServerPrefersThePublicIssuer() throws Exception {
        // The whole expression, not a substring: a bare ${quarkus.oidc.token.issuer}
        // without the fallback also "prefers the issuer" and fails config expansion on
        // every deployment that does not set one — dev, the quickstart, the test
        // profile. Both halves have to be there.
        assertEquals("${quarkus.oidc.token.issuer:${quarkus.oidc.auth-server-url}}",
                required(applicationProperties(), AUTHORIZATION_SERVER),
                AUTHORIZATION_SERVER + " must be the issuer with auth-server-url as its fallback: the issuer "
                        + "alone breaks where none is set, and auth-server-url alone advertises the "
                        + "cluster-internal Keycloak address that no client outside the cluster can resolve");
    }

    /**
     * {@code user-info-required=true} makes EDDI call Keycloak's userinfo endpoint
     * on every request, and Keycloak refuses userinfo for a token issued without
     * the {@code openid} scope. Clients copy {@code scopes_supported} straight into
     * their authorize request, so omitting it here produces tokens that
     * authenticate and then fail on the first call.
     */
    @Test
    @DisplayName("openid is advertised as a supported scope")
    void openidScopeIsAdvertised() throws Exception {
        var properties = applicationProperties();
        assertTrue(Arrays.asList(required(properties, SCOPES).split(",")).contains("openid"),
                SCOPES + " must include openid while user-info-required is on");
        assertEquals("true", properties.getProperty("quarkus.oidc.authentication.user-info-required", "").trim(),
                "this test's premise changed: user-info-required is no longer true, so revisit whether "
                        + SCOPES + " still has to carry openid");
    }

    /**
     * {@code quarkus.http.proxy.*} is configured nowhere in this file, so the
     * scheme in the advertised identifier is whatever the request carried — which
     * behind a TLS-terminating ingress is {@code http}. A deployment with
     * authentication on advertising an {@code http} resource identifier is a
     * downgrade it should never publish, so the default is pinned the other way and
     * the one plain-http deployment (the auth E2E tier) overrides it explicitly.
     */
    @Test
    @DisplayName("the advertised identifier is https unless a deployment opts out")
    void httpsSchemeIsForced() throws Exception {
        assertEquals("true", required(applicationProperties(), "quarkus.oidc.resource-metadata.force-https-scheme"),
                "an authenticated deployment must not advertise an http resource identifier by default");
    }

    /**
     * The document must be readable by an anonymous client — that is the whole
     * point of it — and by nothing more than a GET.
     */
    @Test
    @DisplayName("the metadata document is anonymously readable, GET/HEAD only")
    void metadataDocumentIsPermitted() throws Exception {
        var properties = applicationProperties();

        assertEquals("permit", required(properties, METADATA_POLICY),
                "the discovery document must be readable without a token, or discovery cannot start");
        assertEquals(List.of("GET", "HEAD"), splitList(required(properties, METADATA_METHODS)),
                METADATA_METHODS + " must allow reads only");
        assertEquals(List.of(WELL_KNOWN, WELL_KNOWN + "/*"), splitList(required(properties, METADATA_PATHS)),
                METADATA_PATHS + " must name the two exact well-known paths — the bare form and the "
                        + "path-inserted form quarkus-oidc serves when the resource is a relative path");
    }

    /**
     * The narrow paths above are load-bearing. {@code /.well-known/*} would permit
     * whatever else is served under that prefix later, and a stray {@code /*} would
     * open the whole instance. Asserting the strings is not enough — this asserts
     * what they <em>match</em>.
     */
    @Test
    @DisplayName("the permit rule cannot match anything but the metadata document")
    void permitRuleMatchesNothingElse() throws Exception {
        List<String> patterns = splitList(required(applicationProperties(), METADATA_PATHS));

        for (String forbidden : List.of("/mcp", "/mcp/messages", "/secretstore", "/agents", "/",
                "/.well-known/agent.json", "/.well-known/openid-configuration")) {
            assertFalse(patterns.stream().anyMatch(pattern -> matches(pattern, forbidden)),
                    "the metadata permit rule must not match " + forbidden + " — patterns: " + patterns);
        }
        for (String allowed : List.of(WELL_KNOWN, WELL_KNOWN + "/mcp")) {
            assertTrue(patterns.stream().anyMatch(pattern -> matches(pattern, allowed)),
                    "the metadata permit rule must match " + allowed + " — patterns: " + patterns);
        }
    }

    /**
     * Adding a permit rule next to the MCP rule is exactly the change that could
     * loosen it by accident. {@code /mcp} keeps the explicit {@code authenticated}
     * policy {@code HighValueSurfaceGuard} exists to back up.
     */
    @Test
    @DisplayName("/mcp itself stays authenticated")
    void mcpStaysAuthenticated() throws Exception {
        assertEquals("authenticated", required(applicationProperties(), MCP_POLICY),
                "/mcp must stay behind the authenticated policy");
    }

    /** Quarkus path patterns: an exact path, or a prefix ending in {@code /*}. */
    private static boolean matches(String pattern, String path) {
        if (pattern.endsWith("/*")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            return path.equals(prefix) || path.startsWith(prefix + "/");
        }
        return pattern.equals(path);
    }

    private static List<String> splitList(String value) {
        return Arrays.stream(value.split(",")).map(String::trim).filter(part -> !part.isEmpty()).toList();
    }
}
