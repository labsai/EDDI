/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * A UTF-8 string body handler that refuses a response larger than a cap.
 * <p>
 * Live sync reads the source instance's JSON documents — descriptor listings
 * with {@code limit=0}, every workflow and extension config — and
 * {@link HttpResponse.BodyHandlers#ofString()} buffers whatever the source
 * sends, however large, before anything looks at it. A compromised source, or
 * one that is simply wrong about what it serves, decided how much of this
 * instance's heap one sync request took. This handler refuses a declared
 * {@code Content-Length} over the cap before reading anything, and otherwise
 * counts the bytes as they arrive and cancels the transfer the moment the cap
 * is crossed — so nothing over the cap is ever held.
 */
final class CappedStringBodyHandler implements HttpResponse.BodyHandler<String> {

    private final long maxBytes;

    CappedStringBodyHandler(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    public HttpResponse.BodySubscriber<String> apply(HttpResponse.ResponseInfo responseInfo) {
        long declared = responseInfo.headers().firstValueAsLong("Content-Length").orElse(-1);
        return new CappedSubscriber(maxBytes, declared);
    }

    /** Thrown (as the send's cause) when a response is over the cap. */
    static final class ResponseTooLargeException extends IOException {
        ResponseTooLargeException(long maxBytes) {
            super("the response is larger than this instance accepts from a sync source (" + maxBytes + " bytes, "
                    + RemoteApiResourceSource.MAX_RESPONSE_BYTES_PROPERTY + ")");
        }
    }

    private static final class CappedSubscriber implements HttpResponse.BodySubscriber<String> {
        private final long maxBytes;
        private final long declared;
        private final CompletableFuture<String> body = new CompletableFuture<>();
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        private long received;

        CappedSubscriber(long maxBytes, long declared) {
            this.maxBytes = maxBytes;
            this.declared = declared;
        }

        @Override
        public CompletionStage<String> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (declared > maxBytes) {
                subscription.cancel();
                body.completeExceptionally(new ResponseTooLargeException(maxBytes));
                return;
            }
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (body.isDone()) {
                return;
            }
            for (ByteBuffer item : items) {
                received += item.remaining();
                if (received > maxBytes) {
                    subscription.cancel();
                    body.completeExceptionally(new ResponseTooLargeException(maxBytes));
                    return;
                }
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                buffer.write(chunk, 0, chunk.length);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            body.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            body.complete(buffer.toString(StandardCharsets.UTF_8));
        }
    }
}
