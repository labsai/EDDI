/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import org.eclipse.microprofile.config.ConfigProvider;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.PushPromiseHandler;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;

/**
 * The langchain4j {@link JdkHttpClient} with a cap on how many bytes one LLM
 * provider response may deliver (security review 2026-10-17, L13).
 * <p>
 * The JDK client reads a whole response into a byte array (or, for streaming,
 * hands an unbounded stream to the SSE parser), and langchain4j offers no size
 * setting — so a misbehaving or malicious provider endpoint, which an editor
 * can point an agent at, could stream without bound into the heap.
 * {@link JdkHttpClientBuilder} does accept the {@link HttpClient.Builder} it
 * builds its client from, and every response passes through the
 * {@link BodyHandler} the client is given, so the cap sits there: each body
 * subscriber counts the bytes it is handed and, past the cap, cancels the
 * subscription and fails the body with an {@link IOException}. Nothing in
 * langchain4j is replaced.
 * <p>
 * The cap is {@value #MAX_RESPONSE_BYTES_PROPERTY} (default
 * {@value #DEFAULT_MAX_RESPONSE_BYTES} bytes), counted per response — for a
 * streaming chat, over the whole stream.
 */
public final class BoundedJdkHttpClient {

    static final String MAX_RESPONSE_BYTES_PROPERTY = "eddi.llm.max-response-bytes";
    static final long DEFAULT_MAX_RESPONSE_BYTES = 32L * 1024 * 1024;

    private BoundedJdkHttpClient() {
    }

    /** {@code JdkHttpClient.builder()} with the response cap installed. */
    public static JdkHttpClientBuilder builder() {
        return builder(maxResponseBytes());
    }

    static JdkHttpClientBuilder builder(long maxResponseBytes) {
        return JdkHttpClient.builder().httpClientBuilder(new CappedBuilder(HttpClient.newBuilder(), maxResponseBytes));
    }

    static long maxResponseBytes() {
        try {
            long configured = ConfigProvider.getConfig().getOptionalValue(MAX_RESPONSE_BYTES_PROPERTY, Long.class)
                    .orElse(DEFAULT_MAX_RESPONSE_BYTES);
            return configured > 0 ? configured : DEFAULT_MAX_RESPONSE_BYTES;
        } catch (RuntimeException e) {
            return DEFAULT_MAX_RESPONSE_BYTES;
        }
    }

    /** Wraps every body handler a request is sent with. */
    static <T> BodyHandler<T> capped(BodyHandler<T> handler, long maxBytes) {
        return info -> new CappedSubscriber<>(handler.apply(info), maxBytes);
    }

    /** Counts the bytes it passes on and fails the body once past the cap. */
    static final class CappedSubscriber<T> implements BodySubscriber<T> {
        private final BodySubscriber<T> delegate;
        private final long maxBytes;
        private long received;
        private boolean failed;
        private Flow.Subscription subscription;

        CappedSubscriber(BodySubscriber<T> delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public CompletionStage<T> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (failed) {
                return;
            }
            for (ByteBuffer item : items) {
                received += item.remaining();
            }
            if (received > maxBytes) {
                failed = true;
                if (subscription != null) {
                    subscription.cancel();
                }
                delegate.onError(new IOException("The LLM provider's response is larger than " + maxBytes + " bytes ("
                        + MAX_RESPONSE_BYTES_PROPERTY + ")"));
                return;
            }
            delegate.onNext(items);
        }

        @Override
        public void onError(Throwable throwable) {
            if (!failed) {
                delegate.onError(throwable);
            }
        }

        @Override
        public void onComplete() {
            if (!failed) {
                delegate.onComplete();
            }
        }
    }

    /** An {@link HttpClient} that caps every response it sends. */
    static final class CappedClient extends HttpClient {
        private final HttpClient delegate;
        private final long maxBytes;

        CappedClient(HttpClient delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, BodyHandler<T> handler) throws IOException, InterruptedException {
            return delegate.send(request, capped(handler, maxBytes));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, BodyHandler<T> handler) {
            return delegate.sendAsync(request, capped(handler, maxBytes));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, BodyHandler<T> handler,
                                                                PushPromiseHandler<T> pushPromiseHandler) {
            return delegate.sendAsync(request, capped(handler, maxBytes), pushPromiseHandler);
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return delegate.cookieHandler();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return delegate.connectTimeout();
        }

        @Override
        public Redirect followRedirects() {
            return delegate.followRedirects();
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return delegate.proxy();
        }

        @Override
        public SSLContext sslContext() {
            return delegate.sslContext();
        }

        @Override
        public SSLParameters sslParameters() {
            return delegate.sslParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return delegate.authenticator();
        }

        @Override
        public Version version() {
            return delegate.version();
        }

        @Override
        public Optional<Executor> executor() {
            return delegate.executor();
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public boolean awaitTermination(Duration duration) throws InterruptedException {
            return delegate.awaitTermination(duration);
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public void shutdownNow() {
            delegate.shutdownNow();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    /** The JDK builder, building a {@link CappedClient}. */
    static final class CappedBuilder implements HttpClient.Builder {
        private final HttpClient.Builder delegate;
        private final long maxBytes;

        CappedBuilder(HttpClient.Builder delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public HttpClient.Builder cookieHandler(CookieHandler cookieHandler) {
            delegate.cookieHandler(cookieHandler);
            return this;
        }

        @Override
        public HttpClient.Builder connectTimeout(Duration duration) {
            delegate.connectTimeout(duration);
            return this;
        }

        @Override
        public HttpClient.Builder sslContext(SSLContext sslContext) {
            delegate.sslContext(sslContext);
            return this;
        }

        @Override
        public HttpClient.Builder sslParameters(SSLParameters sslParameters) {
            delegate.sslParameters(sslParameters);
            return this;
        }

        @Override
        public HttpClient.Builder executor(Executor executor) {
            delegate.executor(executor);
            return this;
        }

        @Override
        public HttpClient.Builder followRedirects(HttpClient.Redirect policy) {
            delegate.followRedirects(policy);
            return this;
        }

        @Override
        public HttpClient.Builder version(HttpClient.Version version) {
            delegate.version(version);
            return this;
        }

        @Override
        public HttpClient.Builder priority(int priority) {
            delegate.priority(priority);
            return this;
        }

        @Override
        public HttpClient.Builder proxy(ProxySelector proxySelector) {
            delegate.proxy(proxySelector);
            return this;
        }

        @Override
        public HttpClient.Builder authenticator(Authenticator authenticator) {
            delegate.authenticator(authenticator);
            return this;
        }

        @Override
        public HttpClient.Builder localAddress(InetAddress localAddr) {
            delegate.localAddress(localAddr);
            return this;
        }

        @Override
        public HttpClient build() {
            return new CappedClient(delegate.build(), maxBytes);
        }
    }
}
