/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.outbound;

import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A refused peer's connection against a real socket: it is closed, not pooled,
 * and a second request to the same origin opens (and is refused on) a
 * connection of its own. Needs loopback sockets, so it runs as an integration
 * test; {@link VertxOutboundEnginePeerRefusalTest} covers the same with mocks.
 */
class VertxOutboundEnginePoolIT {

    private static Vertx vertx;

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stopVertx() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static OutboundSettings settings() {
        OutboundSettings.ProfileSettings profile = new OutboundSettings.ProfileSettings(Duration.ofSeconds(5), null,
                OutboundSettings.Protocol.HTTP1, null, OutboundSettings.VERTX_ENGINE, 1);
        Map<Profile, OutboundSettings.ProfileSettings> profiles = new EnumMap<>(Profile.class);
        for (Profile p : Profile.values()) {
            profiles.put(p, profile);
        }
        return new OutboundSettings(OutboundSettings.VERTX_ENGINE, profiles, OutboundSettings.ProxySettings.NONE);
    }

    /**
     * What one accepted connection saw: bytes received, and whether the client
     * closed it.
     */
    private record Seen(int bytes, boolean closedByClient) {
    }

    @Test
    @DisplayName("a refused connection is closed, and a second request to the same origin is refused on a new one")
    void refusedConnectionIsNotReused() throws Exception {
        try (ServerSocket raw = new ServerSocket(0, 10, InetAddress.getLoopbackAddress())) {
            AtomicInteger accepted = new AtomicInteger();
            List<CompletableFuture<Seen>> connections = new CopyOnWriteArrayList<>();
            Thread acceptor = new Thread(() -> {
                while (!raw.isClosed()) {
                    try {
                        Socket socket = raw.accept();
                        accepted.incrementAndGet();
                        connections.add(CompletableFuture.supplyAsync(() -> watch(socket)));
                    } catch (IOException e) {
                        return;
                    }
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            // One connection per host, so a pooled connection would have to be reused.
            VertxOutboundEngine engine = new VertxOutboundEngine(vertx, settings(), VertxOutboundEngine.TlsOptions.NONE,
                    UnaryOperator.identity());
            try {
                URI target = URI.create("http://127.0.0.1:" + raw.getLocalPort() + "/secret");
                for (int i = 0; i < 2; i++) {
                    assertThrows(OutboundBlockedException.class,
                            () -> engine.send(new OutboundEngine.Hop(Profile.DEFAULT, "GET", target, Map.of(), new byte[0],
                                    Duration.ofSeconds(5), System.nanoTime() + Duration.ofSeconds(5).toNanos(),
                                    ConnectedPeers.STRICT)));
                }

                // The server side may return from accept() after the client already
                // saw the connection; a reused connection would leave this at 1.
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (accepted.get() < 2 && System.nanoTime() < until) {
                    Thread.onSpinWait();
                }
                assertEquals(2, accepted.get(), "each refused request had a connection of its own");
                for (CompletableFuture<Seen> connection : connections) {
                    Seen seen = connection.get(10, TimeUnit.SECONDS);
                    assertEquals(0, seen.bytes(), "the refused peer must not see the request");
                    assertEquals(true, seen.closedByClient(), "the refused connection must be closed, not left pooled");
                }
            } finally {
                engine.close();
            }
        }
    }

    /**
     * Reads until the client closes the socket (well under the keep-alive timeout a
     * pooled connection would stay open for) or 3 s pass.
     */
    private static Seen watch(Socket socket) {
        try (socket) {
            socket.setSoTimeout(3000);
            InputStream in = socket.getInputStream();
            int count = 0;
            try {
                while (in.read() != -1) {
                    count++;
                }
                return new Seen(count, true);
            } catch (SocketTimeoutException e) {
                return new Seen(count, false);
            }
        } catch (IOException e) {
            // A reset is a close too.
            return new Seen(0, true);
        }
    }
}
