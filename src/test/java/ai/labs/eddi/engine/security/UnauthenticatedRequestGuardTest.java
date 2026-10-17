/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import ai.labs.eddi.engine.security.UnauthenticatedRequestGuard.Decision;
import ai.labs.eddi.engine.security.UnauthenticatedRequestGuard.HostPort;
import ai.labs.eddi.engine.security.UnauthenticatedRequestGuard.Policy;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static ai.labs.eddi.engine.security.UnauthenticatedRequestGuard.decide;
import static ai.labs.eddi.engine.security.UnauthenticatedRequestGuard.parseHost;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("UnauthenticatedRequestGuard")
class UnauthenticatedRequestGuardTest {

    /** The shipped CORS default. */
    private static final List<String> CORS = List.of("http://localhost:3000", "http://localhost:7070", "https://localhost:7443");

    private static final Policy DEFAULTS = Policy.of(List.of(), CORS);

    private static Decision get(Policy policy, String host) {
        return decide(policy, "GET", "/agentstore/agents/descriptors", host, null, null);
    }

    private static Decision post(Policy policy, String host, String origin, String secFetchSite) {
        return decide(policy, "POST", "/agents/abc/start", host, origin, secFetchSite);
    }

    @Nested
    @DisplayName("parsing a Host")
    class ParseHost {

        @ParameterizedTest(name = "{0} -> {1} port {2} ip={3}")
        @CsvSource({
                "localhost, localhost, -1, false",
                "LOCALHOST:7070, localhost, 7070, false",
                "localhost.:7070, localhost, 7070, false",
                "Eddi.Example.COM., eddi.example.com, -1, false",
                "127.0.0.1, 127.0.0.1, -1, true",
                "127.0.0.1:7070, 127.0.0.1, 7070, true",
                "127.0.0.1., 127.0.0.1, -1, true",
                "'[::1]', ::1, -1, true",
                "'[::1]:7070', ::1, 7070, true",
                "'[FE80::1]:443', fe80::1, 443, true",
                "eddi:65535, eddi, 65535, false",
                "my_host, my_host, -1, false"
        })
        void parsesValidHosts(String raw, String host, int port, boolean ipLiteral) {
            HostPort parsed = parseHost(raw);
            assertNotNull(parsed, raw);
            assertEquals(new HostPort(host, port, ipLiteral), parsed);
        }

        @ParameterizedTest(name = "\"{0}\" is malformed")
        @ValueSource(strings = {"", "   ", "::1", "[::1", "[::1]7070", "[evil.example]", "[]:80", "localhost..",
                "localhost:", ":7070", "localhost:0", "localhost:65536", "localhost:99999999", "localhost:7o70",
                "localhost:-1", "user@localhost", "localhost/x", "evil example", ".localhost", "-host", "a..b",
                "localhost:7070:7070"})
        void refusesMalformedHosts(String raw) {
            assertNull(parseHost(raw), raw);
        }

        @Test
        void nullIsMalformed() {
            assertNull(parseHost(null));
        }

        @Test
        @DisplayName("an IPv4 look-alike in another notation is a name, not an address")
        void numericNamesAreNotIpLiterals() {
            assertFalse(parseHost("2130706433").ipLiteral());
            assertFalse(parseHost("0x7f000001").ipLiteral());
            assertFalse(parseHost("127.1").ipLiteral());
            assertFalse(parseHost("256.0.0.1").ipLiteral());
        }
    }

    @Nested
    @DisplayName("the Host check")
    class HostCheck {

        @ParameterizedTest(name = "{0} is answered")
        @ValueSource(strings = {"localhost", "localhost:7070", "LocalHost:7070", "localhost.:7070", "127.0.0.1:7070",
                "127.0.0.1", "[::1]:7070", "[::1]", "10.1.2.3:7070", "192.168.1.20:7070", "[fd00::5]:7070",
                "app.localhost:7070", "0.0.0.0:7070"})
        void answersLoopbackAndAddresses(String host) {
            assertEquals(Decision.ALLOW, get(DEFAULTS, host));
        }

        @ParameterizedTest(name = "{0} is refused with 421")
        @ValueSource(strings = {"evil.example", "evil.example:7070", "localhost.evil.example:7070",
                "127.0.0.1.evil.example", "127.0.0.1.nip.io:7070", "2130706433:7070", "0x7f000001", "eddi:7070",
                "localhostevil:7070", "[::1", "", "evil.example..", "::1"})
        void refusesOtherHosts(String host) {
            assertEquals(Decision.MISDIRECTED_HOST, get(DEFAULTS, host));
        }

        @Test
        @DisplayName("applies to state-changing requests too, before the Origin is considered")
        void refusesRebindingWriteEvenWithMatchingOrigin() {
            // A rebound page is same-origin with itself: Origin and Host agree.
            assertEquals(Decision.MISDIRECTED_HOST, post(DEFAULTS, "evil.example:7070", "http://evil.example:7070", "same-origin"));
        }

        @Test
        @DisplayName("a bare allowed host matches every port; host:port only that port")
        void allowedHostsHonourPorts() {
            Policy policy = Policy.of(List.of("eddi", " EDDI.Example.com.:443 ", "[fd00::1]:7070"), CORS);

            assertEquals(Decision.ALLOW, get(policy, "eddi"));
            assertEquals(Decision.ALLOW, get(policy, "eddi:7070"));
            assertEquals(Decision.ALLOW, get(policy, "EDDI:9999"));
            assertEquals(Decision.ALLOW, get(policy, "eddi.example.com:443"));
            assertEquals(Decision.ALLOW, get(policy, "eddi.example.com.:443"));
            assertEquals(Decision.MISDIRECTED_HOST, get(policy, "eddi.example.com"),
                    "no port means the scheme default, which is not the listed 443 for a plain-http Host");
            assertEquals(Decision.MISDIRECTED_HOST, get(policy, "eddi.example.com:7070"));
            assertEquals(Decision.MISDIRECTED_HOST, get(policy, "eddi.example.com.evil:443"));
            assertEquals(Decision.MISDIRECTED_HOST, get(policy, "sub.eddi:7070"), "no implicit subdomains");
        }

        @Test
        @DisplayName("malformed and blank allow-list entries are ignored, not widened")
        void malformedEntriesAreIgnored() {
            Policy policy = Policy.of(List.of("", "  ", "http://eddi.example", "user@eddi", "eddi:abc"), CORS);
            assertTrue(policy.allowedHosts().isEmpty());
            assertFalse(policy.anyHost());
            assertEquals(Decision.MISDIRECTED_HOST, get(policy, "eddi.example"));
        }

        @Test
        @DisplayName("* turns the Host check off")
        void wildcardDisablesTheHostCheck() {
            Policy policy = Policy.of(List.of("*"), CORS);
            assertTrue(policy.anyHost());
            assertEquals(Decision.ALLOW, get(policy, "evil.example:7070"));
            // ...but a malformed Host is still refused
            assertEquals(Decision.MISDIRECTED_HOST, get(policy, "evil example"));
            // ...and the Origin check still applies
            assertEquals(Decision.CROSS_SITE, post(policy, "evil.example:7070", "https://other.example", null));
        }

        @Test
        @DisplayName("a request without a Host is not the rebinding vector")
        void missingHostIsAllowedForSafeMethods() {
            assertEquals(Decision.ALLOW, get(DEFAULTS, null));
            assertEquals(Decision.ALLOW, post(DEFAULTS, null, null, null));
            assertEquals(Decision.CROSS_SITE, post(DEFAULTS, null, "https://evil.example", null));
        }
    }

    @Nested
    @DisplayName("the cross-site check")
    class CrossSiteCheck {

        @ParameterizedTest(name = "{0} with a foreign Origin passes")
        @ValueSource(strings = {"GET", "HEAD", "OPTIONS", "get", "options"})
        void safeMethodsAreNotOriginChecked(String method) {
            assertEquals(Decision.ALLOW, decide(DEFAULTS, method, "/x", "localhost:7070", "https://evil.example", "cross-site"));
        }

        @ParameterizedTest(name = "{0} with a foreign Origin is refused")
        @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE", "post"})
        void unsafeMethodsAreOriginChecked(String method) {
            assertEquals(Decision.CROSS_SITE, decide(DEFAULTS, method, "/x", "localhost:7070", "https://evil.example", null));
        }

        @Test
        @DisplayName("the request's own origin passes")
        void sameOriginPasses() {
            assertEquals(Decision.ALLOW, post(DEFAULTS, "localhost:7070", "http://localhost:7070", "same-origin"));
            assertEquals(Decision.ALLOW, post(DEFAULTS, "127.0.0.1:7070", "http://127.0.0.1:7070", null));
            assertEquals(Decision.ALLOW, post(DEFAULTS, "[::1]:7070", "http://[::1]:7070", null));
            assertEquals(Decision.ALLOW, post(DEFAULTS, "LOCALHOST:7070", "http://localhost:7070", null));
            assertEquals(Decision.ALLOW, post(DEFAULTS, "192.168.1.20:7070", "http://192.168.1.20:7070", null));
        }

        @Test
        @DisplayName("behind a TLS-terminating proxy the page is https and the Host has no port")
        void sameOriginBehindAProxy() {
            Policy policy = Policy.of(List.of("eddi.example.com"), CORS);
            assertEquals(Decision.ALLOW, post(policy, "eddi.example.com", "https://eddi.example.com", "same-origin"));
            assertEquals(Decision.ALLOW, post(policy, "eddi.example.com:443", "https://eddi.example.com", null));
            assertEquals(Decision.CROSS_SITE, post(policy, "eddi.example.com", "https://eddi.example.com:8443", null));
        }

        @Test
        @DisplayName("another port on the same host is another origin")
        void otherPortIsCrossOrigin() {
            assertEquals(Decision.CROSS_SITE, post(DEFAULTS, "localhost:7070", "http://localhost:3001", "same-site"));
            assertEquals(Decision.CROSS_SITE, post(DEFAULTS, "localhost:7070", "http://localhost:8080", null));
        }

        @Test
        @DisplayName("an origin in quarkus.http.cors.origins passes, matched like CORS")
        void corsOriginsPass() {
            assertEquals(Decision.ALLOW, post(DEFAULTS, "localhost:7070", "http://localhost:3000", "same-site"));
            assertEquals(Decision.ALLOW, post(DEFAULTS, "localhost:7070", "HTTP://LOCALHOST:3000", "same-site"));
            assertEquals(Decision.ALLOW, post(DEFAULTS, "localhost:7443", "https://localhost:7443", null));
            assertEquals(Decision.CROSS_SITE, post(DEFAULTS, "localhost:7070", "https://localhost:3000", null),
                    "the scheme is part of a configured origin");
        }

        @ParameterizedTest(name = "Origin \"{0}\" is refused")
        @ValueSource(strings = {"null", "NULL", "", " ", "http://", "not a url", "http://user@localhost:7070",
                "file:///etc/passwd", "https://evil.example", "http://localhost.evil.example:7070"})
        void hostileOrMalformedOriginsAreRefused(String origin) {
            assertEquals(Decision.CROSS_SITE, post(DEFAULTS, "localhost:7070", origin, null));
        }

        @ParameterizedTest(name = "Sec-Fetch-Site {0} without an Origin -> {1}")
        @CsvSource({
                "cross-site, CROSS_SITE",
                "Cross-Site, CROSS_SITE",
                "same-site, CROSS_SITE",
                "same-origin, ALLOW",
                "none, ALLOW",
                "something-new, ALLOW"
        })
        void secFetchSiteDecidesWithoutAnOrigin(String site, Decision expected) {
            assertEquals(expected, post(DEFAULTS, "localhost:7070", null, site));
        }

        @Test
        @DisplayName("a request with neither header comes from a non-browser client and passes")
        void nonBrowserClientsPass() {
            assertEquals(Decision.ALLOW, post(DEFAULTS, "localhost:7070", null, null));
        }

        @Test
        @DisplayName("an allowed Origin wins over a cross-site Sec-Fetch-Site")
        void allowedOriginWinsOverSecFetchSite() {
            Policy policy = Policy.of(List.of(), List.of("https://www.example.com"));
            assertEquals(Decision.ALLOW, post(policy, "localhost:7070", "https://www.example.com", "cross-site"));
        }

        @Test
        @DisplayName("* and /regex/ CORS entries are honoured, null still is not")
        void wildcardAndRegexOrigins() {
            Policy any = Policy.of(List.of(), List.of("*"));
            assertEquals(Decision.ALLOW, post(any, "localhost:7070", "https://evil.example", null));
            assertEquals(Decision.CROSS_SITE, post(any, "localhost:7070", "null", null));

            Policy regex = Policy.of(List.of(), List.of("/https://[a-z]+\\.example\\.com/", "/[unclosed/"));
            assertEquals(Decision.ALLOW, post(regex, "localhost:7070", "https://chat.example.com", null));
            assertEquals(Decision.CROSS_SITE, post(regex, "localhost:7070", "https://chat.example.com.evil", null));
        }
    }

    @Nested
    @DisplayName("health endpoints")
    class Health {

        @ParameterizedTest(name = "{0} is exempt")
        @ValueSource(strings = {"/q/health", "/q/health/live", "/q/health/ready", "/q/health/started"})
        void healthIsExempt(String path) {
            assertEquals(Decision.ALLOW, decide(DEFAULTS, "GET", path, "10.0.0.7.evil:8080", null, null));
            assertEquals(Decision.ALLOW, decide(DEFAULTS, "GET", path, "pod-name:7070", "https://evil.example", "cross-site"));
        }

        @ParameterizedTest(name = "{0} is not exempt")
        @ValueSource(strings = {"/q/healthz", "/q/health-check", "/q/metrics", "/health", "/q/healt"})
        void lookAlikesAreNotExempt(String path) {
            assertEquals(Decision.MISDIRECTED_HOST, decide(DEFAULTS, "GET", path, "evil.example", null, null));
        }

        @Test
        void nullPathIsNotExempt() {
            assertEquals(Decision.MISDIRECTED_HOST, decide(DEFAULTS, "GET", null, "evil.example", null, null));
        }
    }

    @Nested
    @DisplayName("wiring")
    class Wiring {

        private RoutingContext context(String method, String path, String host, String origin) {
            RoutingContext context = mock(RoutingContext.class);
            HttpServerRequest request = mock(HttpServerRequest.class);
            HttpServerResponse response = mock(HttpServerResponse.class);
            when(context.request()).thenReturn(request);
            when(context.response()).thenReturn(response);
            when(context.normalizedPath()).thenReturn(path);
            when(request.method()).thenReturn(HttpMethod.valueOf(method));
            when(request.getHeader(HttpHeaders.HOST)).thenReturn(host);
            when(request.getHeader(HttpHeaders.ORIGIN)).thenReturn(origin);
            when(response.setStatusCode(anyInt())).thenReturn(response);
            when(response.putHeader(any(CharSequence.class), any(CharSequence.class))).thenReturn(response);
            when(response.putHeader(anyString(), anyString())).thenReturn(response);
            return context;
        }

        private UnauthenticatedRequestGuard guard(boolean authorizationEnabled, List<String> allowedHosts) {
            UnauthenticatedRequestGuard guard = new UnauthenticatedRequestGuard();
            guard.authorizationEnabled = authorizationEnabled;
            guard.allowedHosts = Optional.of(allowedHosts);
            guard.corsOrigins = Optional.of(CORS);
            return guard;
        }

        @Test
        @DisplayName("registers nothing while authentication is on")
        void inactiveWithAuthentication() {
            Filters filters = mock(Filters.class);
            guard(true, List.of()).register(filters);
            verify(filters, never()).register(any(), anyInt());
        }

        @Test
        @DisplayName("registers ahead of the body-size guard while authentication is off, and refuses with 421 / 403")
        @SuppressWarnings("unchecked")
        void activeWithoutAuthentication() {
            Filters filters = mock(Filters.class);
            ArgumentCaptor<Handler<RoutingContext>> handler = ArgumentCaptor.forClass(Handler.class);
            guard(false, List.of("eddi")).register(filters);
            verify(filters).register(handler.capture(), eq(UnauthenticatedRequestGuard.PRIORITY));
            assertTrue(UnauthenticatedRequestGuard.PRIORITY < HttpMethodGuard.PRIORITY);
            assertTrue(UnauthenticatedRequestGuard.PRIORITY > RequestBodyLimitGuard.PRIORITY);

            RoutingContext rebound = context("GET", "/secretstore/secrets", "evil.example:7070", null);
            handler.getValue().handle(rebound);
            verify(rebound.response()).setStatusCode(421);
            verify(rebound, never()).next();

            RoutingContext crossSite = context("POST", "/agents/x/start", "localhost:7070", "https://evil.example");
            handler.getValue().handle(crossSite);
            verify(crossSite.response()).setStatusCode(403);
            verify(crossSite, never()).next();

            RoutingContext ok = context("POST", "/agents/x/start", "eddi:7070", null);
            handler.getValue().handle(ok);
            verify(ok).next();
            verify(ok.response(), never()).setStatusCode(anyInt());
        }

        @Test
        @DisplayName("the boot line names the policy and the property")
        void describesThePolicy() {
            String line = UnauthenticatedRequestGuard.describe(Policy.of(List.of("eddi", "[fd00::1]:7070"), CORS));
            assertTrue(line.startsWith("[SECURITY] "), line);
            assertTrue(line.contains(UnauthenticatedRequestGuard.ALLOWED_HOSTS_PROPERTY), line);
            assertTrue(line.contains("eddi, [fd00::1]:7070"), line);
            assertTrue(line.contains("quarkus.http.cors.origins"), line);

            String off = UnauthenticatedRequestGuard.describe(Policy.of(List.of("*"), List.of("*")));
            assertTrue(off.contains("Host check is OFF"), off);
            assertTrue(off.contains("from any page are accepted"), off);
        }
    }
}
