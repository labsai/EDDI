/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.HttpRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Security review 2026-10-17, L13: an LLM provider response was read without
 * any size bound.
 */
@DisplayName("BoundedJdkHttpClient caps what one provider response may deliver")
class BoundedJdkHttpClientTest {

    private HttpServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/big", exchange -> {
            byte[] chunk = new byte[64 * 1024];
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                for (int i = 0; i < 64; i++) { // 4 MB
                    out.write(chunk);
                }
            } catch (Exception ignored) {
                // the client hangs up once the cap is passed
            }
        });
        server.createContext("/small", exchange -> {
            byte[] body = "{\"ok\":true}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    @Test
    @DisplayName("a response over the cap fails instead of being read into memory")
    void overTheCapFails() {
        var client = BoundedJdkHttpClient.builder(1024 * 1024).build();
        var request = HttpRequest.builder().method(HttpMethod.GET).url(url("/big")).build();

        var e = assertThrows(RuntimeException.class, () -> client.execute(request));
        boolean named = false;
        for (Throwable t = e; t != null; t = t.getCause()) {
            named |= t.getMessage() != null && t.getMessage().contains("larger than 1048576 bytes");
        }
        assertTrue(named, String.valueOf(e));
    }

    @Test
    @DisplayName("a response under the cap is delivered unchanged")
    void underTheCapPasses() {
        var client = BoundedJdkHttpClient.builder(1024 * 1024).build();
        var request = HttpRequest.builder().method(HttpMethod.GET).url(url("/small")).build();

        assertEquals("{\"ok\":true}", client.execute(request).body());
    }

    @Test
    @DisplayName("the subscriber cancels the upstream and fails the body once past the cap")
    void subscriberCancelsPastTheCap() throws Exception {
        Flow.Subscription subscription = mock(Flow.Subscription.class);
        var capped = new BoundedJdkHttpClient.CappedSubscriber<>(BodySubscribers.ofByteArray(), 10);
        capped.onSubscribe(subscription);
        capped.onNext(List.of(ByteBuffer.wrap(new byte[6])));
        capped.onNext(List.of(ByteBuffer.wrap(new byte[6])));
        capped.onComplete();

        verify(subscription).cancel();
        var e = assertThrows(ExecutionException.class, () -> capped.getBody().toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertInstanceOf(IOException.class, e.getCause());

        var fine = new BoundedJdkHttpClient.CappedSubscriber<>(BodySubscribers.ofByteArray(), 10);
        fine.onSubscribe(mock(Flow.Subscription.class));
        fine.onNext(List.of(ByteBuffer.wrap(new byte[]{1, 2, 3})));
        fine.onComplete();
        assertArrayEquals(new byte[]{1, 2, 3}, fine.getBody().toCompletableFuture().get(5, TimeUnit.SECONDS));
    }
}
