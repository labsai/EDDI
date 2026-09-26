/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient;

import ai.labs.eddi.engine.httpclient.BoundedBodyHandlers.ResponseTooLargeException;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;

/**
 * Drives a {@link HttpResponse.BodyHandler} the way the JDK client would,
 * without a server, and reports whether it refused the body as too large.
 * <p>
 * For tests of callers: a tool that passes a bounded handler to a mocked
 * {@link SafeHttpClient} can capture the handler and prove that it — not some
 * check after the fact — stops at the configured limit.
 */
public final class BodyHandlerProbe {

    private static final int CHUNK = 64 * 1024;

    private BodyHandlerProbe() {
    }

    /**
     * Result of one probe: whether the body was refused and the subscription
     * cancelled.
     */
    public record Outcome(boolean refused, boolean cancelled, boolean requested, Object body) {
    }

    /**
     * A 200 response declaring {@code Content-Length: declaredLength} and then
     * sending nothing: a handler that honours the declared length refuses without
     * requesting a byte.
     */
    public static Outcome declared(HttpResponse.BodyHandler<?> handler, long declaredLength) {
        return run(handler, Map.of("Content-Length", List.of(Long.toString(declaredLength))), 0);
    }

    /**
     * A 200 response with no {@code Content-Length} whose body is
     * {@code streamedBytes} long, sent in 64 KiB chunks until the handler cancels.
     */
    public static Outcome streamed(HttpResponse.BodyHandler<?> handler, long streamedBytes) {
        return run(handler, Map.of(), streamedBytes);
    }

    private static Outcome run(HttpResponse.BodyHandler<?> handler, Map<String, List<String>> headerMap, long streamedBytes) {
        HttpHeaders headers = HttpHeaders.of(headerMap, (name, value) -> true);
        HttpResponse.ResponseInfo info = new HttpResponse.ResponseInfo() {
            @Override
            public int statusCode() {
                return 200;
            }

            @Override
            public HttpHeaders headers() {
                return headers;
            }

            @Override
            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
        HttpResponse.BodySubscriber<?> subscriber = handler.apply(info);
        boolean[] cancelled = {false};
        boolean[] requested = {false};
        subscriber.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long n) {
                requested[0] = true;
            }

            @Override
            public void cancel() {
                cancelled[0] = true;
            }
        });
        long sent = 0;
        while (!cancelled[0] && sent < streamedBytes) {
            int size = (int) Math.min(CHUNK, streamedBytes - sent);
            subscriber.onNext(List.of(ByteBuffer.allocate(size)));
            sent += size;
        }
        if (!cancelled[0]) {
            subscriber.onComplete();
        }
        CompletableFuture<?> body = subscriber.getBody().toCompletableFuture();
        if (!body.isDone()) {
            return new Outcome(false, cancelled[0], requested[0], null);
        }
        try {
            return new Outcome(false, cancelled[0], requested[0], body.get());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            while (cause != null && !(cause instanceof ResponseTooLargeException)) {
                cause = cause.getCause();
            }
            return new Outcome(cause != null, cancelled[0], requested[0], null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
