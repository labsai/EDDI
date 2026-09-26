/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient;

import java.io.IOException;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Body handlers that stop reading a response once it passes a byte limit.
 * <p>
 * {@code BodyHandlers.ofString()} and {@code ofByteArray()} buffer whatever the
 * server sends, and a caller that checks the size afterwards has already paid
 * for it: a peer that answers with a few gigabytes gets an
 * {@link OutOfMemoryError}, which is not an {@link Exception} and escapes every
 * {@code catch (Exception e)} a tool wraps around its fetch. These handlers
 * refuse a declared {@code Content-Length} above the limit before reading a
 * byte, and otherwise cancel the subscription — which closes the connection —
 * the moment the running total passes it. Either way the send fails with
 * {@link ResponseTooLargeException}, an {@link IOException}, so the caller's
 * existing error path handles it.
 * <p>
 * Peak memory per response is about twice the limit: the received chunks are
 * copied (the client recycles its buffers) and then assembled into one array.
 * {@link #ofString} adds the decoded string on top, so about three times. A 25
 * MiB limit therefore means up to ~50 MiB transient per concurrent download.
 * <p>
 * The limit bounds <em>memory</em>, not time. A body trickled one byte a second
 * stays under any limit for a long while; the wall-clock deadline in
 * {@link SafeHttpClient} is what bounds that.
 *
 * @since 6.4.0
 */
public final class BoundedBodyHandlers {

    private BoundedBodyHandlers() {
        // Utility class
    }

    /**
     * Thrown (as the send's failure) when a response body exceeds the handler's
     * limit.
     */
    public static final class ResponseTooLargeException extends IOException {
        private final long limit;

        public ResponseTooLargeException(long limit) {
            super("Response body exceeds the limit of " + limit + " bytes");
            this.limit = limit;
        }

        public long getLimit() {
            return limit;
        }
    }

    /**
     * The body as bytes, at most {@code maxBytes} of them.
     *
     * @throws IllegalArgumentException
     *             if {@code maxBytes} is negative or above {@link #MAX_LIMIT}
     */
    public static HttpResponse.BodyHandler<byte[]> ofByteArray(long maxBytes) {
        requireValidLimit(maxBytes);
        return info -> new LimitingSubscriber(maxBytes, info.headers().firstValueAsLong("Content-Length"));
    }

    /**
     * The body decoded as a string, at most {@code maxBytes} bytes of it before
     * decoding. The charset comes from the {@code Content-Type} header, as with
     * {@code BodyHandlers.ofString()}; absent or unknown, it is UTF-8.
     *
     * @throws IllegalArgumentException
     *             if {@code maxBytes} is negative or above {@link #MAX_LIMIT}
     */
    public static HttpResponse.BodyHandler<String> ofString(long maxBytes) {
        requireValidLimit(maxBytes);
        return info -> {
            Charset charset = charsetFrom(info.headers());
            return HttpResponse.BodySubscribers.mapping(
                    new LimitingSubscriber(maxBytes, info.headers().firstValueAsLong("Content-Length")),
                    bytes -> new String(bytes, charset));
        };
    }

    /**
     * The body lands in one array, so the limit is bounded by what an array holds.
     */
    static final long MAX_LIMIT = Integer.MAX_VALUE - 8;

    private static void requireValidLimit(long maxBytes) {
        requireValidLimit("maxBytes", maxBytes);
    }

    /**
     * Returns {@code value} if it is a usable limit, else throws naming
     * {@code property}. For callers that take a limit from configuration: checking
     * it once, at construction, fails a bad deployment at startup rather than every
     * request at call time.
     *
     * @throws IllegalArgumentException
     *             if {@code value} is negative or above {@link #MAX_LIMIT}
     */
    public static long requireValidLimit(String property, long value) {
        if (value < 0 || value > MAX_LIMIT) {
            throw new IllegalArgumentException(property + " must be between 0 and " + MAX_LIMIT + " bytes, but is " + value);
        }
        return value;
    }

    /**
     * The {@code charset} parameter of the {@code Content-Type} header, or UTF-8.
     * Package-private for the test.
     */
    static Charset charsetFrom(HttpHeaders headers) {
        String contentType = headers.firstValue("Content-Type").orElse("");
        for (String param : contentType.split(";")) {
            String trimmed = param.trim();
            if (trimmed.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                String name = trimmed.substring("charset=".length()).trim();
                if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
                    name = name.substring(1, name.length() - 1);
                }
                try {
                    return Charset.forName(name);
                } catch (IllegalArgumentException e) {
                    // IllegalCharsetNameException and UnsupportedCharsetException both
                    // extend it: fall through to the default, as ofString() would not
                    // (it throws) — a bad header must not fail an otherwise good fetch.
                    return StandardCharsets.UTF_8;
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    /**
     * Collects the body until it completes or passes the limit. Package-private so
     * the limit logic can be driven without a server.
     */
    static final class LimitingSubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final long maxBytes;
        private final OptionalLong declaredLength;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final List<ByteBuffer> received = new ArrayList<>();
        private Flow.Subscription subscription;
        private long total;

        LimitingSubscriber(long maxBytes, OptionalLong declaredLength) {
            this.maxBytes = maxBytes;
            this.declaredLength = declaredLength;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (declaredLength.isPresent() && declaredLength.getAsLong() > maxBytes) {
                // Refused on the header alone: not one byte of the body is read.
                reject();
                return;
            }
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer item : items) {
                total += item.remaining();
                if (total > maxBytes) {
                    received.clear();
                    reject();
                    return;
                }
                // The client may reuse its buffers once onNext returns, so copy.
                ByteBuffer copy = ByteBuffer.allocate(item.remaining());
                copy.put(item).flip();
                received.add(copy);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            received.clear();
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            if (result.isDone()) {
                return;
            }
            byte[] bytes = new byte[(int) total];
            int offset = 0;
            for (ByteBuffer buffer : received) {
                int length = buffer.remaining();
                buffer.get(bytes, offset, length);
                offset += length;
            }
            received.clear();
            result.complete(bytes);
        }

        private void reject() {
            result.completeExceptionally(new ResponseTooLargeException(maxBytes));
            subscription.cancel();
        }
    }
}
