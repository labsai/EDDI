/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The address EDDI answers as "where I can reach myself".
 *
 * @author ginccc
 */
@DisplayName("SelfUrlResolver")
class SelfUrlResolverTest {

    private static SelfUrlResolver loopbackOn(int port) {
        return new SelfUrlResolver(Optional.empty(), port);
    }

    private static SelfUrlResolver configured(String baseUrl) {
        return new SelfUrlResolver(Optional.of(baseUrl), 7070);
    }

    @Nested
    @DisplayName("derived from the HTTP port")
    class Loopback {

        @Test
        @DisplayName("defaults to loopback on the configured port")
        void loopbackOnConfiguredPort() {
            var resolver = loopbackOn(7070);
            assertEquals("http://127.0.0.1:7070", resolver.baseUrl());
            assertEquals(SelfUrlResolver.SOURCE_LOOPBACK, resolver.source());
            assertFalse(resolver.configured());
        }

        /**
         * The point of reading the port rather than assuming it: the failure this class
         * exists for was a port mismatch, so a deployment that moved off 7070 must not
         * need the override to be resolved correctly.
         */
        @Test
        @DisplayName("follows a non-default HTTP port")
        void followsNonDefaultPort() {
            assertEquals("http://127.0.0.1:9443", loopbackOn(9443).baseUrl());
        }

        /**
         * quarkus.http.port=0 asks for a random port, which configuration cannot name.
         * Answering 7070 anyway would be confidently wrong — and would make isSelf true
         * for an address that is not this process.
         */
        @Test
        @DisplayName("a random or unset port is reported as unresolved, not guessed")
        void randomPortIsUnresolved() {
            for (int port : new int[]{0, -1}) {
                var resolver = loopbackOn(port);
                assertNull(resolver.baseUrl());
                assertEquals(SelfUrlResolver.SOURCE_UNRESOLVED, resolver.source());
                assertFalse(resolver.isSelf(URI.create("http://127.0.0.1:7070/agentstore/agents")));
            }
        }

        @Test
        @DisplayName("an override still works when the port cannot be derived")
        void overrideRescuesARandomPort() {
            var resolver = new SelfUrlResolver(Optional.of("http://eddi:7070"), 0);
            assertEquals("http://eddi:7070", resolver.baseUrl());
            assertEquals(SelfUrlResolver.SOURCE_CONFIGURED, resolver.source());
        }
    }

    @Nested
    @DisplayName("the eddi.self.base-url override")
    class Override {

        @Test
        @DisplayName("wins over loopback and is reported as configured")
        void overrideWins() {
            var resolver = configured("https://eddi.internal:8443");
            assertEquals("https://eddi.internal:8443", resolver.baseUrl());
            assertEquals(SelfUrlResolver.SOURCE_CONFIGURED, resolver.source());
            assertTrue(resolver.configured());
        }

        /**
         * {@code ApiCallExecutor} concatenates {@code targetServerUrl} with a path that
         * already starts with a slash, so a trailing slash here becomes {@code //path}.
         */
        @Test
        @DisplayName("trailing slashes are stripped, however many")
        void stripsTrailingSlashes() {
            assertEquals("http://eddi:7070", configured("http://eddi:7070/").baseUrl());
            assertEquals("http://eddi:7070", configured("http://eddi:7070///").baseUrl());
        }

        @Test
        @DisplayName("whitespace only is treated as unset")
        void blankIsUnset() {
            assertEquals("http://127.0.0.1:7070", configured("   ").baseUrl());
            assertEquals(SelfUrlResolver.SOURCE_LOOPBACK, configured("   ").source());
        }

        @Test
        @DisplayName("a private or loopback host is accepted — that is the whole point of it")
        void acceptsPrivateHosts() {
            assertEquals("http://eddi.svc.cluster.local:7070", configured("http://eddi.svc.cluster.local:7070").baseUrl());
            assertEquals("http://10.0.0.4:7070", configured("http://10.0.0.4:7070").baseUrl());
        }

        /**
         * A malformed override must not take the deployment down, and it must not be
         * used either: falling back to loopback is the answer that was correct before
         * anyone set the property.
         */
        @Test
        @DisplayName("an unusable value — including anything but a bare origin — falls back to loopback instead of failing startup")
        void unusableValueFallsBack() {
            for (String bad : new String[]{"not a url", "ftp://eddi:7070", "file:///etc/passwd", "/agentstore", "http://",
                    "https://eddi.internal/base", "http://eddi:7070?tenant=x", "http://eddi:7070#frag",
                    "http://user:pass@eddi:7070"}) {
                var resolver = configured(bad);
                assertEquals("http://127.0.0.1:7070", resolver.baseUrl(), bad + " should have fallen back");
                assertEquals(SelfUrlResolver.SOURCE_LOOPBACK, resolver.source(), bad + " should not report as configured");
            }
        }
    }

    @Nested
    @DisplayName("isSelf")
    class IsSelf {

        @Test
        @DisplayName("matches the resolved origin regardless of path and query")
        void matchesOriginOnly() {
            var resolver = loopbackOn(7070);
            assertTrue(resolver.isSelf(URI.create("http://127.0.0.1:7070/agentstore/agents?version=1")));
            assertTrue(resolver.isSelf(URI.create("http://127.0.0.1:7070")));
        }

        @Test
        @DisplayName("a default port is the same origin as the explicit one")
        void defaultPortEqualsExplicit() {
            assertTrue(configured("https://eddi.example").isSelf(URI.create("https://eddi.example:443/agentstore/agents")));
            assertTrue(configured("https://eddi.example:443").isSelf(URI.create("https://eddi.example/agentstore/agents")));
        }

        /**
         * The whole failure mode this class addresses: the browser's address and the
         * server's are different origins. {@code isSelf} must say so, or the
         * caller-token exception built on it would be a same-origin bypass.
         */
        @Test
        @DisplayName("another host, another port or another scheme is not self")
        void rejectsEverythingElse() {
            var resolver = loopbackOn(7070);
            assertFalse(resolver.isSelf(URI.create("http://localhost:7080/agentstore/agents")));
            assertFalse(resolver.isSelf(URI.create("http://127.0.0.1:7080/agentstore/agents")));
            assertFalse(resolver.isSelf(URI.create("https://127.0.0.1:7070/agentstore/agents")));
            assertFalse(resolver.isSelf(URI.create("http://evil.example/agentstore/agents")));
        }

        @Test
        @DisplayName("null and a hostless URI are not self")
        void rejectsUnknownTargets() {
            var resolver = loopbackOn(7070);
            assertFalse(resolver.isSelf(null));
            assertFalse(resolver.isSelf(URI.create("/agentstore/agents")));
        }
    }
}
