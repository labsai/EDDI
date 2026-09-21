/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.a2a;

import io.quarkus.security.Authenticated;
import io.quarkus.vertx.http.runtime.security.HttpSecurityUtils;
import io.quarkus.vertx.http.runtime.security.ImmutablePathMatcher;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which A2A endpoints an anonymous caller can reach, decided by
 * {@code quarkus.http.auth.permission.*} in {@code application.properties}.
 * <p>
 * <b>Why this test exists.</b> Quarkus evaluates those path policies
 * <em>before</em> declarative RBAC, so {@code @PermitAll} on a JAX-RS method
 * does not exempt its path from the {@code /*} catch-all. Five A2A endpoints
 * carried {@code @PermitAll} and were named in no permit entry, so on any
 * instance with {@code quarkus.oidc.tenant-enabled=true} they answered 401 to
 * exactly the credential-less peers they exist for — while the code read as if
 * they were open. Nothing caught it: every backend test tier but
 * {@code ui/manager/e2e/auth} runs with authorization switched off, where
 * {@link ai.labs.eddi.engine.security.DisabledAuthController} turns the path
 * policies off entirely and both postures look identical.
 * <p>
 * The auth E2E tier now asserts the resulting status codes against a real
 * Keycloak. This test is the cheap half: it resolves the shipped configuration
 * through Quarkus's own {@link ImmutablePathMatcher}, so it runs in
 * {@code ./mvnw test} with no container, and it fails the moment the
 * annotations and the configuration stop agreeing — in either direction.
 *
 * @since 6.4.0
 */
@DisplayName("A2A endpoint permissions")
class A2aEndpointPermissionsTest {

    private static final String PERMIT = "permit";
    private static final String AUTHENTICATED = "authenticated";
    /**
     * Quarkus's answer when a path matches a permission entry but the request
     * method does not: it denies rather than falling through to the catch-all.
     */
    private static final String DENIED_BY_METHOD = "<denied: method not matched>";
    /**
     * No entry matched at all — impossible here, the catch-all covers {@code /*}.
     */
    private static final String NO_POLICY = "<no policy>";

    /** Stands in for {@code {agentId}} when a template path is resolved. */
    private static final String SAMPLE_AGENT_ID = "6512a1b2c3d4e5f607182930";

    /** One {@code quarkus.http.auth.permission.<name>.*} entry. */
    private record Entry(String name, String policy, Set<String> methods) {
    }

    private static ImmutablePathMatcher<List<Entry>> matcher;

    @BeforeAll
    static void buildMatcherFromShippedConfig() throws Exception {
        var properties = applicationProperties();
        var builder = ImmutablePathMatcher.<List<Entry>>builder().handlerAccumulator(List::addAll);

        for (var name : permissionNames(properties)) {
            var prefix = "quarkus.http.auth.permission." + name + ".";
            var policy = properties.getProperty(prefix + "policy");
            assertTrue(policy != null && !policy.isBlank(),
                    "Permission entry '" + name + "' declares paths but no policy");
            var methods = splitCsv(properties.getProperty(prefix + "methods"));
            for (var path : splitCsv(properties.getProperty(prefix + "paths"))) {
                // One list per path, exactly as AbstractPathMatchingHttpSecurityPolicy
                // builds it; the accumulator merges entries sharing a path.
                var entries = new ArrayList<Entry>();
                entries.add(new Entry(name, resolveExpression(policy), methods));
                builder.addPath(HttpSecurityUtils.normalizePath(path), entries);
            }
        }
        matcher = builder.build();
    }

    // ==================== The five endpoints in question ====================

    @Nested
    @DisplayName("anonymous, because a peer holds no EDDI credential")
    class Public {

        @Test
        @DisplayName("GET /.well-known/agent.json — the discovery entry point")
        void defaultAgentCard() {
            assertPolicies("/.well-known/agent.json", "GET", PERMIT);
        }

        @Test
        @DisplayName("GET /a2a/agents/{agentId}/agent.json — the card a peer is pointed at")
        void perAgentCard() {
            assertPolicies("/a2a/agents/" + SAMPLE_AGENT_ID + "/agent.json", "GET", PERMIT);
        }

        @Test
        @DisplayName("GET /.well-known/capabilities — eddi.a2a.capabilities.public is the gate, not the HTTP layer")
        void capabilities() {
            assertPolicies("/.well-known/capabilities", "GET", PERMIT);
        }

        @Test
        @DisplayName("GET /.well-known/capabilities/skills")
        void capabilitySkills() {
            assertPolicies("/.well-known/capabilities/skills", "GET", PERMIT);
        }
    }

    @Nested
    @DisplayName("authenticated, deliberately")
    class Protected {

        @Test
        @DisplayName("GET /a2a/agents — the roster is not part of peer discovery")
        void agentListing() {
            assertPolicies("/a2a/agents", "GET", AUTHENTICATED);
        }

        @Test
        @DisplayName("POST /a2a/agents/{agentId} — the JSON-RPC surface that actually runs conversations")
        void jsonRpc() {
            assertPolicies("/a2a/agents/" + SAMPLE_AGENT_ID, "POST", AUTHENTICATED);
        }

        @Test
        @DisplayName("the card entry permits GET only — a write under it is not permitted")
        void cardPathIsReadOnly() {
            var policies = policiesFor("/a2a/agents/" + SAMPLE_AGENT_ID + "/agent.json", "POST");
            assertTrue(!policies.equals(List.of(PERMIT)),
                    "A write under the Agent Card path must not inherit its permit, but resolved to " + policies);
        }
    }

    // ==================== The constraints on the entries ====================

    @Test
    @DisplayName("/.well-known is not wildcarded — a future sibling must be decided, not inherited")
    void wellKnownIsEnumerated() {
        // This test was written with RFC 9728 protected-resource metadata as its
        // example of the sibling a /.well-known/* permit would open with nobody
        // deciding to. That decision has since been taken deliberately: EDDI
        // advertises /mcp as an OAuth protected resource, so the document is
        // permitted by its own narrow entry (exact paths, GET/HEAD), asserted in
        // McpOAuthDiscoveryConfigTest. The guard this test exists for is the line
        // below it: a path nobody decided on still resolves to authenticated.
        assertPolicies("/.well-known/oauth-protected-resource", "GET", PERMIT);
        assertPolicies("/.well-known/anything-else", "GET", AUTHENTICATED);
    }

    @Test
    @DisplayName("the permit entries name only A2A discovery paths")
    void permitEntriesAreNarrow() throws Exception {
        var properties = applicationProperties();
        for (var name : List.of("a2a-agent-card", "a2a-capabilities")) {
            var prefix = "quarkus.http.auth.permission." + name + ".";
            assertEquals(PERMIT, properties.getProperty(prefix + "policy"),
                    name + " must be a permit entry");
            assertEquals(Set.of("GET"), splitCsv(properties.getProperty(prefix + "methods")),
                    name + " must be GET-only, like the health and static-asset entries");
        }
    }

    // ==================== Code and configuration must agree ====================

    /**
     * The generic guard. Every annotated endpoint on {@link RestA2AEndpoint} is
     * resolved through the same matcher, so adding an endpoint with
     * {@code @PermitAll} and forgetting the permit entry — the original bug — fails
     * here rather than in production.
     */
    @Test
    @DisplayName("every @PermitAll / @Authenticated endpoint resolves to the policy it claims")
    void annotationsMatchConfiguration() {
        int checked = 0;
        for (var method : RestA2AEndpoint.class.getDeclaredMethods()) {
            var path = method.getAnnotation(Path.class);
            if (path == null) {
                continue;
            }
            var resolved = "/" + path.value().replaceAll("\\{[^}]+}", SAMPLE_AGENT_ID);

            if (method.isAnnotationPresent(PermitAll.class)) {
                assertPolicies(resolved, httpVerbOf(method), PERMIT,
                        method.getName() + " is @PermitAll, but Quarkus checks the path policy first and"
                                + " this path does not resolve to permit — add it to a permit entry in"
                                + " application.properties, or drop the annotation");
                checked++;
            } else if (method.isAnnotationPresent(Authenticated.class)) {
                assertPolicies(resolved, httpVerbOf(method), AUTHENTICATED,
                        method.getName() + " is @Authenticated, but the path policy does not require"
                                + " authentication — a permit entry is overriding the annotation");
                checked++;
            }
        }
        assertEquals(6, checked,
                "Expected the four @PermitAll cards/capabilities plus the two @Authenticated endpoints;"
                        + " if an endpoint was added or removed, say so here deliberately");
    }

    // ==================== Helpers ====================

    /**
     * The HTTP verb an endpoint answers, read from whichever annotation is itself
     * meta-annotated {@link HttpMethod} — so {@code @PUT}, {@code @DELETE} and a
     * custom verb resolve rather than being guessed.
     * <p>
     * This was {@code isAnnotationPresent(GET.class) ? "GET" : "POST"}, which
     * graded every non-GET endpoint as a POST. The permit entries are GET-only, so
     * a {@code @PermitAll @PUT} would have been checked against a method it does
     * not serve — and this is the generic guard, so guessing is exactly what it
     * exists not to do.
     */
    private static String httpVerbOf(Method method) {
        var verbs = Arrays.stream(method.getAnnotations())
                .map(a -> a.annotationType().getAnnotation(HttpMethod.class))
                .filter(Objects::nonNull)
                .map(HttpMethod::value)
                .distinct()
                .toList();
        assertEquals(1, verbs.size(),
                method.getName() + " carries " + verbs.size() + " JAX-RS verb annotations " + verbs
                        + "; this guard resolves a path policy for exactly one");
        return verbs.get(0);
    }

    /**
     * Replicates {@code AbstractPathMatchingHttpSecurityPolicy.findHttpMatchers}:
     * entries naming the request method win; entries naming no method apply
     * otherwise; if neither matches, Quarkus denies rather than falling through.
     * Every surviving policy must pass, so an anonymous caller gets through only
     * when the result is exactly {@code [permit]}.
     */
    private static List<String> policiesFor(String path, String method) {
        var match = matcher.match(HttpSecurityUtils.normalizePath(path));
        if (match.getValue() == null || match.getValue().isEmpty()) {
            return List.of(NO_POLICY);
        }
        var applicable = applicableEntries(path, method);
        if (applicable.isEmpty()) {
            return List.of(DENIED_BY_METHOD);
        }
        return applicable.stream().map(Entry::policy).distinct().sorted().toList();
    }

    /** The entries that survive Quarkus's method filtering, in match order. */
    private static List<Entry> applicableEntries(String path, String method) {
        var entries = matcher.match(HttpSecurityUtils.normalizePath(path)).getValue();
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        var byMethod = entries.stream().filter(e -> e.methods().contains(method)).toList();
        return !byMethod.isEmpty() ? byMethod : entries.stream().filter(e -> e.methods().isEmpty()).toList();
    }

    private static void assertPolicies(String path, String method, String expected) {
        assertPolicies(path, method, expected, null);
    }

    private static void assertPolicies(String path, String method, String expected, String message) {
        assertEquals(List.of(expected), policiesFor(path, method),
                (message == null ? "" : message + " — ") + method + " " + path
                        + " (matched by " + matchingEntryNames(path, method) + ")");
    }

    /**
     * Which permission entries claimed this request, for the failure message.
     * "resolved to permit" is half the answer; which entry did it is the half a
     * reader needs to know where to look.
     */
    private static String matchingEntryNames(String path, String method) {
        var applicable = applicableEntries(path, method);
        if (!applicable.isEmpty()) {
            return names(applicable);
        }
        // Nothing survived, and the distinction matters: either no entry claimed
        // the path at all, or one claimed it and excluded this method — which
        // Quarkus turns into a denial rather than a fall-through.
        var onPath = matcher.match(HttpSecurityUtils.normalizePath(path)).getValue();
        return onPath == null || onPath.isEmpty() ? "no entry" : names(onPath) + ", but none of them for " + method;
    }

    private static String names(List<Entry> entries) {
        return entries.stream().map(Entry::name).distinct().sorted().collect(Collectors.joining(", "));
    }

    /**
     * Read from the source tree, not the classpath: {@code
     * src/test/resources/application.properties} shadows the main file under test
     * and declares no permission entries at all, so a classpath lookup would find
     * nothing and every assertion above would resolve to {@code <no policy>}.
     */
    private static Properties applicationProperties() throws Exception {
        // Paths.get, not Path.of: jakarta.ws.rs.Path owns the simple name here.
        var path = Paths.get(System.getProperty("basedir", "."))
                .resolve("src/main/resources/application.properties");
        assertTrue(Files.isRegularFile(path), "Expected the application config at " + path);

        var properties = new Properties();
        // Properties.load joins the trailing-backslash continuations that the
        // static-asset path list is written across.
        try (Reader in = Files.newBufferedReader(path)) {
            properties.load(in);
        }
        assertTrue(properties.containsKey("quarkus.http.auth.permission.authenticated.paths"),
                "The catch-all permission entry is gone; this test would pass vacuously without it");
        return properties;
    }

    /** Every {@code <name>} that declares paths, in a stable (sorted) order. */
    private static Set<String> permissionNames(Properties properties) {
        var names = new LinkedHashSet<String>();
        for (var key : new TreeSet<>(properties.stringPropertyNames())) {
            if (key.startsWith("quarkus.http.auth.permission.") && key.endsWith(".paths")) {
                names.add(key.substring("quarkus.http.auth.permission.".length(), key.length() - ".paths".length()));
            }
        }
        assertTrue(names.size() > 5, "Only " + names.size() + " permission entries parsed — the file changed shape");
        return names;
    }

    private static Set<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * {@code ${prop:default}} — the openai-compat entry uses one; take the default.
     */
    private static String resolveExpression(String value) {
        if (value.startsWith("${") && value.endsWith("}")) {
            var inner = value.substring(2, value.length() - 1);
            var colon = inner.indexOf(':');
            return colon >= 0 ? inner.substring(colon + 1) : inner;
        }
        return value;
    }
}
