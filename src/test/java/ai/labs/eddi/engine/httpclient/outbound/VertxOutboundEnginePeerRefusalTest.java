/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.outbound;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.SocketAddress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the Vert.x engine does with a connection whose peer the check refused,
 * without sockets: the client, request and connection are mocks. The same
 * behaviour against real sockets is {@code VertxOutboundEnginePoolIT}.
 */
class VertxOutboundEnginePeerRefusalTest {

    private static final URI TARGET = URI.create("http://rebind.example:8080/secret");

    private HttpClient client;
    private HttpConnection connection;
    private VertxOutboundEngine engine;

    @BeforeEach
    void setUp() {
        Vertx vertx = mock(Vertx.class);
        client = mock(HttpClient.class);
        connection = mock(HttpConnection.class);
        SocketAddress loopback = mock(SocketAddress.class);
        when(loopback.hostAddress()).thenReturn("127.0.0.1");
        when(connection.remoteAddress()).thenReturn(loopback);
        when(vertx.createHttpClient(any(HttpClientOptions.class), any(PoolOptions.class))).thenReturn(client);
        engine = new VertxOutboundEngine(vertx, settings(), VertxOutboundEngine.TlsOptions.NONE, UnaryOperator.identity());
    }

    private static OutboundSettings settings() {
        OutboundSettings.ProfileSettings profile = new OutboundSettings.ProfileSettings(Duration.ofSeconds(5), null,
                OutboundSettings.Protocol.AUTO, null, OutboundSettings.VERTX_ENGINE, 5);
        Map<Profile, OutboundSettings.ProfileSettings> profiles = new EnumMap<>(Profile.class);
        for (Profile p : Profile.values()) {
            profiles.put(p, profile);
        }
        return new OutboundSettings(OutboundSettings.VERTX_ENGINE, profiles, OutboundSettings.ProxySettings.NONE);
    }

    private static OutboundEngine.Hop hop(OutboundEngine.PeerCheck check) {
        return new OutboundEngine.Hop(Profile.DEFAULT, "GET", TARGET, Map.of(), new byte[0], Duration.ofSeconds(5),
                System.nanoTime() + Duration.ofSeconds(5).toNanos(), check);
    }

    /** A request the pool assigned to {@link #connection}. */
    private HttpClientRequest requestOnConnection() {
        HttpClientRequest request = mock(HttpClientRequest.class);
        when(request.connection()).thenReturn(connection);
        return request;
    }

    @Test
    @DisplayName("a refused peer's connection is closed, so the pool cannot hand it out again, and nothing is sent")
    void refusedConnectionIsClosed() {
        HttpClientRequest request = requestOnConnection();
        when(client.request(any(RequestOptions.class))).thenReturn(Future.succeededFuture(request));

        assertThrows(OutboundBlockedException.class, () -> engine.send(hop(ConnectedPeers.STRICT)));

        InOrder order = inOrder(connection, request);
        order.verify(connection).close();
        order.verify(request).reset();
        verify(request, never()).send();
        verify(request, never()).send(any(Buffer.class));
    }

    @Test
    @DisplayName("a second request to the same origin is checked again, even on the same pooled connection")
    void reusedConnectionIsCheckedAgain() {
        HttpClientRequest first = requestOnConnection();
        HttpClientRequest second = requestOnConnection();
        when(client.request(any(RequestOptions.class))).thenReturn(Future.succeededFuture(first),
                Future.succeededFuture(second));
        AtomicInteger checks = new AtomicInteger();
        OutboundEngine.PeerCheck counting = peer -> {
            checks.incrementAndGet();
            ConnectedPeers.STRICT.check(peer);
        };

        assertThrows(OutboundBlockedException.class, () -> engine.send(hop(counting)));
        assertThrows(OutboundBlockedException.class, () -> engine.send(hop(counting)));

        assertEquals(2, checks.get(), "the check runs per request, not per connect");
        verify(connection, times(2)).close();
        for (HttpClientRequest request : List.of(first, second)) {
            verify(request).reset();
            verify(request, never()).send();
        }
    }

    @Test
    @DisplayName("an allowed peer's connection is neither closed nor reset")
    void allowedPeerKeepsConnection() {
        HttpClientRequest request = requestOnConnection();
        when(client.request(any(RequestOptions.class))).thenReturn(Future.succeededFuture(request));
        // Never answered: the hop then times out, which is not what this test is about.
        when(request.send()).thenReturn(Future.failedFuture(new IllegalStateException("no response in this test")));
        AtomicInteger checks = new AtomicInteger();

        List<InetAddress> seen = new CopyOnWriteArrayList<>();

        assertThrows(IOException.class, () -> engine.send(hop(peer -> {
            seen.add(peer);
            checks.incrementAndGet();
        })));

        assertEquals("127.0.0.1", seen.getFirst().getHostAddress());

        assertEquals(1, checks.get());
        verify(request).send();
        verify(connection, never()).close();
    }
}
