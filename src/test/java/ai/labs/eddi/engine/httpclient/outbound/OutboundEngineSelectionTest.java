/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.outbound;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Which engine each profile runs on, the pool-limit settings, and the
 * connected-peer check the facade hands each hop.
 */
class OutboundEngineSelectionTest {

    private static Config config(Map<String, String> values) {
        Config config = mock(Config.class);
        when(config.getOptionalValue(anyString(), any())).thenAnswer(invocation -> {
            String value = values.get(invocation.<String>getArgument(0));
            Class<?> type = invocation.getArgument(1);
            if (value == null) {
                return Optional.empty();
            }
            if (type == Integer.class) {
                return Optional.of(Integer.valueOf(value));
            }
            if (type == Boolean.class) {
                return Optional.of(Boolean.valueOf(value));
            }
            if (type == Duration.class) {
                return Optional.of(Duration.parse(value));
            }
            return Optional.of(value);
        });
        return config;
    }

    @Nested
    @DisplayName("engine selection")
    class Selection {

        @Test
        @DisplayName("by default apicalls runs on Vert.x and every other profile on the JDK engine")
        void defaults() {
            OutboundSettings settings = OutboundSettings.fromConfig(config(Map.of()));

            assertEquals(OutboundSettings.VERTX_ENGINE, settings.profile(Profile.APICALLS).engine());
            assertEquals(OutboundSettings.DEFAULT_VERTX_MAX_CONNECTIONS_PER_HOST,
                    settings.profile(Profile.APICALLS).maxConnectionsPerHost());
            for (Profile profile : List.of(Profile.DEFAULT, Profile.LLM_PROVIDER, Profile.INTERNAL_PEER)) {
                assertEquals(OutboundSettings.JDK_ENGINE, settings.profile(profile).engine(), profile.key());
                assertNull(settings.profile(profile).maxConnectionsPerHost(), profile.key());
            }
            assertTrue(settings.usesVertx());
        }

        @Test
        @DisplayName("apicalls inherits httpcalls' tuned pool size")
        void apicallsInheritsLegacyPool() {
            OutboundSettings settings = OutboundSettings.fromConfig(config(Map.of("httpClient.maxConnectionPerRoute", "200")));

            assertEquals(200, settings.profile(Profile.APICALLS).maxConnectionsPerHost());
        }

        @Test
        @DisplayName("an explicit global engine applies to apicalls too")
        void globalEngineWins() {
            OutboundSettings settings = OutboundSettings.fromConfig(config(Map.of("eddi.http.outbound.engine", "jdk")));

            assertEquals(OutboundSettings.JDK_ENGINE, settings.profile(Profile.APICALLS).engine());
        }

        @Test
        @DisplayName("a per-profile engine overrides the global one")
        void perProfileOverride() {
            OutboundSettings settings = OutboundSettings.fromConfig(config(Map.of("eddi.http.outbound.engine", "vertx",
                    "eddi.http.outbound.llm-provider.engine", "JDK", "eddi.http.outbound.default.max-connections-per-host", "8")));

            assertEquals(OutboundSettings.VERTX_ENGINE, settings.profile(Profile.DEFAULT).engine());
            assertEquals(8, settings.profile(Profile.DEFAULT).maxConnectionsPerHost());
            assertEquals(OutboundSettings.JDK_ENGINE, settings.profile(Profile.LLM_PROVIDER).engine());
        }

        @Test
        @DisplayName("an unknown engine fails startup")
        void unknownEngine() {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> OutboundSettings.fromConfig(config(Map.of("eddi.http.outbound.apicalls.engine", "netty"))));
            assertTrue(e.getMessage().contains("eddi.http.outbound.apicalls.engine"), e.getMessage());
        }

        @Test
        @DisplayName("a pool limit on a JDK profile fails startup instead of being ignored")
        void poolLimitOnJdkProfile() {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> OutboundSettings
                    .fromConfig(config(Map.of("eddi.http.outbound.default.max-connections-per-host", "10"))));
            assertTrue(e.getMessage().contains("jdk engine"), e.getMessage());
        }

        @Test
        @DisplayName("a pool limit below 1 fails startup")
        void poolLimitBelowOne() {
            assertThrows(IllegalStateException.class, () -> OutboundSettings
                    .fromConfig(config(Map.of("eddi.http.outbound.apicalls.max-connections-per-host", "0"))));
        }
    }

    @Nested
    @DisplayName("the peer check each hop carries")
    class PeerChecks {

        private final ScriptedEngine engine = new ScriptedEngine();

        private OutboundHttp facade() {
            return new OutboundHttp(OutboundSettings.defaults(Duration.ofSeconds(10)), engine, false, new SimpleMeterRegistry(),
                    host -> new InetAddress[]{InetAddress.getByAddress(host, new byte[]{93, (byte) 184, (byte) 216, 34})});
        }

        private void send(OutboundRequest request) throws Exception {
            facade().send(request, BodyMode.discard());
        }

        @Test
        @DisplayName("STRICT, METADATA_ONLY and NONE map to the strict, metadata and no peer check")
        void firstHop() throws Exception {
            URI uri = URI.create("https://api.example.com/");
            engine.respond(200, "").respond(200, "").respond(200, "");

            send(OutboundRequest.get(Profile.DEFAULT, uri).ssrf(SsrfPolicy.STRICT).build());
            send(OutboundRequest.get(Profile.DEFAULT, uri).ssrf(SsrfPolicy.METADATA_ONLY).build());
            send(OutboundRequest.get(Profile.DEFAULT, uri).ssrf(SsrfPolicy.NONE).build());

            assertSame(ConnectedPeers.STRICT, engine.hops.get(0).peerCheck());
            assertSame(ConnectedPeers.METADATA_ONLY, engine.hops.get(1).peerCheck());
            assertNull(engine.hops.get(2).peerCheck());
        }

        @Test
        @DisplayName("HOPS_ONLY trusts the first hop and holds redirect hops to the strict check")
        void hopsOnly() throws Exception {
            engine.respond(302, Map.of("Location", List.of("https://other.example.com/next")), new byte[0]).respond(200, "");

            send(OutboundRequest.get(Profile.DEFAULT, URI.create("https://api.example.com/")).ssrf(SsrfPolicy.HOPS_ONLY).build());

            assertNull(engine.hops.get(0).peerCheck());
            assertSame(ConnectedPeers.STRICT, engine.hops.get(1).peerCheck());
        }

        @Test
        @DisplayName("a caller's own hop validator replaces the redirect hops' peer check")
        void hopValidatorOverride() throws Exception {
            engine.respond(302, Map.of("Location", List.of("https://other.example.com/next")), new byte[0]).respond(200, "");

            send(OutboundRequest.get(Profile.DEFAULT, URI.create("https://api.example.com/")).ssrf(SsrfPolicy.STRICT)
                    .hopValidator(url -> {
                    }).build());

            assertSame(ConnectedPeers.STRICT, engine.hops.get(0).peerCheck());
            assertNull(engine.hops.get(1).peerCheck());
        }
    }

    @Nested
    @DisplayName("ConnectedPeers")
    class Rules {

        @Test
        @DisplayName("STRICT refuses loopback, private and metadata peers, and an unreadable one")
        void strict() throws Exception {
            for (String refused : List.of("127.0.0.1", "10.0.0.5", "192.168.1.1", "169.254.169.254", "::1")) {
                InetAddress peer = ConnectedPeers.parse(refused);
                assertThrows(OutboundBlockedException.class, () -> ConnectedPeers.STRICT.check(peer), refused);
            }
            assertThrows(OutboundBlockedException.class, () -> ConnectedPeers.STRICT.check(null));
            ConnectedPeers.STRICT.check(ConnectedPeers.parse("93.184.216.34"));
        }

        @Test
        @DisplayName("METADATA_ONLY refuses only the metadata and link-local peers")
        void metadataOnly() {
            assertThrows(OutboundBlockedException.class,
                    () -> ConnectedPeers.METADATA_ONLY.check(ConnectedPeers.parse("169.254.169.254")));
            ConnectedPeers.METADATA_ONLY.check(ConnectedPeers.parse("10.0.0.5"));
            ConnectedPeers.METADATA_ONLY.check(null);
        }

        @Test
        @DisplayName("parse reads bracketed and zoned literals and never resolves a name")
        void parse() {
            assertEquals(ConnectedPeers.parse("fe80::1"), ConnectedPeers.parse("[fe80::1%eth0]"));
            assertNull(ConnectedPeers.parse("example.com"));
            assertNull(ConnectedPeers.parse(" "));
        }
    }

    @Nested
    @DisplayName("how the Vert.x engine reports an async failure")
    class VertxFailures {

        private final URI uri = URI.create("https://api.example.com/v1?key=s3cret");

        @Test
        @DisplayName("a pool-wait timeout under the exchange deadline is an OutboundTimeoutException, without the query")
        void poolWaitAtDeadline() {
            TimeoutException poolWait = new TimeoutException("The timeout of 5000 ms has been exceeded when getting a connection");

            IOException reported = VertxOutboundEngine.unwrap(new ExecutionException(poolWait), uri, true);

            assertInstanceOf(OutboundTimeoutException.class, reported);
            assertSame(poolWait, reported.getCause());
            assertFalse(reported.getMessage().contains("s3cret"), reported.getMessage());
        }

        @Test
        @DisplayName("a pool-wait timeout under the hop timeout is the hop's HttpTimeoutException")
        void poolWaitAtHopTimeout() {
            IOException reported = VertxOutboundEngine.unwrap(new ExecutionException(new TimeoutException("pool")), uri, false);

            assertInstanceOf(HttpTimeoutException.class, reported);
            assertFalse(reported instanceof OutboundTimeoutException);
        }

        @Test
        @DisplayName("an I/O failure keeps its type, a refused peer is rethrown, anything else becomes an IOException")
        void otherFailures() {
            ConnectException refused = new ConnectException("Connection refused");
            assertSame(refused, VertxOutboundEngine.unwrap(new ExecutionException(refused), uri, true));

            OutboundBlockedException blocked = new OutboundBlockedException("peer refused");
            assertSame(blocked, assertThrows(OutboundBlockedException.class,
                    () -> VertxOutboundEngine.unwrap(new ExecutionException(blocked), uri, true)));

            IllegalStateException closed = new IllegalStateException("Connection was closed");
            IOException wrapped = VertxOutboundEngine.unwrap(new ExecutionException(closed), uri, true);
            assertSame(closed, wrapped.getCause());
        }
    }
}
