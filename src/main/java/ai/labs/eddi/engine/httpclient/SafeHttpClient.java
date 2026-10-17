/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient;

import ai.labs.eddi.engine.httpclient.outbound.BodyMode;
import ai.labs.eddi.engine.httpclient.outbound.CallerFrames;
import ai.labs.eddi.engine.httpclient.outbound.MethodRule;
import ai.labs.eddi.engine.httpclient.outbound.OutboundHttp;
import ai.labs.eddi.engine.httpclient.outbound.OutboundRequest;
import ai.labs.eddi.engine.httpclient.outbound.OutboundResponse;
import ai.labs.eddi.engine.httpclient.outbound.Profile;
import ai.labs.eddi.engine.httpclient.outbound.RedirectPolicy;
import ai.labs.eddi.engine.httpclient.outbound.RequestBody;
import ai.labs.eddi.engine.httpclient.outbound.SsrfPolicy;
import ai.labs.eddi.modules.llm.tools.UrlValidationUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.net.ssl.SSLSession;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The SSRF-safe JDK-typed HTTP API that LLM tools and integrations have used
 * since 6.0.2, now a thin adapter over {@link OutboundHttp} on the
 * {@link Profile#DEFAULT} profile. Every guarantee — per-hop SSRF validation,
 * manual redirects with RFC 9110 method rules, cross-origin credential
 * stripping, one deadline for the whole exchange, capped and discarded redirect
 * bodies — is implemented there.
 * <p>
 * Kept so callers can move to {@link OutboundHttp} one at a time.
 *
 * @since 6.0.2
 * @deprecated use {@link OutboundHttp} directly
 */
@Deprecated
@ApplicationScoped
public class SafeHttpClient {

    /** @see OutboundHttp#SENSITIVE_HEADERS */
    public static final Set<String> SENSITIVE_HEADERS = OutboundHttp.SENSITIVE_HEADERS;

    private final OutboundHttp outbound;

    @Inject
    public SafeHttpClient(OutboundHttp outbound) {
        this.outbound = outbound;
    }

    /** A client outside CDI, on its own {@link OutboundHttp#standalone} facade. */
    public SafeHttpClient(int connectTimeoutMs) {
        this(OutboundHttp.standalone(Duration.ofMillis(connectTimeoutMs)));
    }

    /**
     * Sends an HTTP request with SSRF-safe redirect handling. The initial URL is
     * NOT validated — callers that fetch user-controlled URLs must call
     * {@link UrlValidationUtils#validateUrl(String)} first (or use
     * {@link #sendValidated}). Every redirect hop is validated.
     */
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler)
            throws IOException, InterruptedException {
        return sendHandled(request, bodyHandler, defaultRedirects());
    }

    /**
     * Sends an HTTP request with SSRF validation on the initial URL and every
     * redirect hop. Use this for user-controlled URLs.
     *
     * @throws IllegalArgumentException
     *             if the URL is unsafe
     */
    public <T> HttpResponse<T> sendValidated(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler)
            throws IOException, InterruptedException {
        UrlValidationUtils.validateUrl(request.uri().toString());
        return send(request, bodyHandler);
    }

    /**
     * A response whose body was read with a hard cap: the status code, the bytes
     * (never more than the requested cap), and whether the body was cut short at
     * the cap or the read deadline.
     */
    public record BoundedResponse(int statusCode, byte[] body, boolean truncated) {
    }

    /**
     * Like {@link #sendValidated} but reads the body with a hard {@code maxBytes}
     * cap, checked <em>while</em> reading.
     */
    public BoundedResponse sendValidatedBounded(HttpRequest request, long maxBytes)
            throws IOException, InterruptedException {
        UrlValidationUtils.validateUrl(request.uri().toString());
        return sendBounded(request, maxBytes);
    }

    /**
     * Like {@link #send} but reads the body with a hard {@code maxBytes} cap. For
     * config-constructed URLs that still return caller/LLM-relayed bodies
     * (redirects are still followed and per-hop validated).
     */
    public BoundedResponse sendBounded(HttpRequest request, long maxBytes)
            throws IOException, InterruptedException {
        OutboundResponse<byte[]> response = outbound.send(toOutbound(request, defaultRedirects()),
                BodyMode.bytes(maxBytes > 0 ? maxBytes : Long.MAX_VALUE, BodyMode.OnOverflow.TRUNCATE));
        return new BoundedResponse(response.status(), response.body(), response.truncated());
    }

    /**
     * Sends an HTTP request with SSRF validation on the initial URL and <em>without
     * following any redirect</em>: a 3xx is returned to the caller as the response,
     * {@code Location} and all. For requests carrying a credential vouched for one
     * specific origin, such as an OAuth token request.
     *
     * @throws IllegalArgumentException
     *             if the URL is unsafe
     */
    public <T> HttpResponse<T> sendValidatedNoRedirect(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler)
            throws IOException, InterruptedException {
        validateInitialTarget(request.uri().toString());
        return sendNoRedirect(request, bodyHandler);
    }

    /**
     * Sends an HTTP request exactly once, following no redirect, and without
     * validating the URL. For callers that already decided the target is acceptable
     * by a stricter rule (an operator-maintained allowlist).
     */
    public <T> HttpResponse<T> sendNoRedirect(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler)
            throws IOException, InterruptedException {
        return sendHandled(request, bodyHandler, RedirectPolicy.NEVER);
    }

    /**
     * Validates the initial request URL against SSRF rules. Package-private so a
     * test against a loopback server can stub it.
     */
    void validateInitialTarget(String url) {
        UrlValidationUtils.validateUrl(url);
    }

    /**
     * Validates a redirect target URL against SSRF rules. Package-private so a test
     * against a loopback server can stub it.
     */
    void validateRedirectTarget(String url) {
        UrlValidationUtils.validateUrl(url);
    }

    private static RedirectPolicy defaultRedirects() {
        return RedirectPolicy.follow(5, MethodRule.JDK_SAFE);
    }

    /**
     * The JDK request as an outbound request: the initial URL is trusted (the
     * public methods validate it where they promise to) and every redirect hop goes
     * through {@link #validateRedirectTarget}. The lambda is built per call so a
     * Mockito spy's stub is the one consulted.
     */
    OutboundRequest toOutbound(HttpRequest request, RedirectPolicy redirects) {
        OutboundRequest.Builder builder = OutboundRequest.builder(Profile.DEFAULT, request.uri())
                .method(request.method(), request.bodyPublisher()
                        .map(SafeHttpClient::collect)
                        .<RequestBody>map(RequestBody::ofBytes)
                        .orElse(RequestBody.EMPTY))
                .ssrf(SsrfPolicy.HOPS_ONLY)
                .hopValidator(this::validateRedirectTarget)
                .redirects(redirects);
        request.timeout().ifPresent(builder::timeout);
        request.headers().map().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
        return builder.build();
    }

    /**
     * The bytes a body publisher produces. Callers here only publish in-memory
     * bodies.
     */
    private static byte[] collect(HttpRequest.BodyPublisher publisher) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CompletableFuture<byte[]> done = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                out.writeBytes(bytes);
            }

            @Override
            public void onError(Throwable throwable) {
                done.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                done.complete(out.toByteArray());
            }
        });
        try {
            return done.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while reading the request body", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalArgumentException("request body could not be read", e);
        }
    }

    /**
     * Sends through the facade with the body streamed, then hands the stream to the
     * caller's JDK body handler. A buffering handler ({@code ofString}) is bounded
     * by the exchange's total budget; a streaming one ({@code ofInputStream})
     * returns at once and its later reads are the caller's to bound.
     */
    private <T> HttpResponse<T> sendHandled(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler,
                                            RedirectPolicy redirects)
            throws IOException, InterruptedException {
        OutboundRequest outboundRequest = toOutbound(request, redirects);
        Duration hopTimeout = request.timeout().orElse(OutboundHttp.DEFAULT_REQUEST_TIMEOUT);
        long deadlineNanos = System.nanoTime() + outbound.totalBudget(Profile.DEFAULT, hopTimeout).toNanos();
        OutboundResponse<InputStream> response = outbound.send(outboundRequest, BodyMode.stream(0, null));

        InputStream stream = response.body();
        HttpHeaders headers = HttpHeaders.of(response.headers(), (name, value) -> true);
        HttpResponse.ResponseInfo info = new HttpResponse.ResponseInfo() {
            @Override
            public int statusCode() {
                return response.status();
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
        HttpResponse.BodySubscriber<T> subscriber;
        try {
            subscriber = bodyHandler.apply(info);
        } catch (RuntimeException e) {
            closeQuietly(stream);
            throw e;
        }
        Thread.ofVirtual().name("safe-http-body").start(() -> HttpRequest.BodyPublishers.ofInputStream(() -> stream)
                .subscribe(new ListBridge(subscriber, stream)));

        CompletableFuture<T> body = subscriber.getBody().toCompletableFuture();
        long remaining = deadlineNanos - System.nanoTime();
        T value;
        try {
            value = body.get(Math.max(remaining, 0), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            closeQuietly(stream);
            throw new HttpTimeoutException("Total request timeout exceeded for: " + OutboundHttp.withoutQuery(response.finalUri()));
        } catch (InterruptedException e) {
            closeQuietly(stream);
            throw e;
        } catch (ExecutionException e) {
            closeQuietly(stream);
            throw unwrap(e);
        }
        return new AdaptedResponse<>(response, headers, request, value);
    }

    private static IOException unwrap(ExecutionException e) {
        Throwable cause = e.getCause();
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof UncheckedIOException unchecked && unchecked.getCause() != null) {
            cause = unchecked.getCause();
        }
        if (cause instanceof IOException || cause instanceof RuntimeException) {
            CallerFrames.attachTo(cause);
        }
        if (cause instanceof IOException io) {
            return io;
        }
        if (cause instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IOException(cause != null ? cause.getMessage() : e.getMessage(), cause);
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException e) {
            // Abandoned anyway.
        }
    }

    /**
     * Adapts the {@code ByteBuffer} publisher to a JDK body subscriber, which takes
     * lists. A cancel closes the stream, releasing the connection; a read failure
     * surfaces as its original {@link IOException}.
     */
    private static final class ListBridge implements Flow.Subscriber<ByteBuffer> {
        private final HttpResponse.BodySubscriber<?> downstream;
        private final InputStream stream;

        ListBridge(HttpResponse.BodySubscriber<?> downstream, InputStream stream) {
            this.downstream = downstream;
            this.stream = stream;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            downstream.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    subscription.request(n);
                }

                @Override
                public void cancel() {
                    subscription.cancel();
                    closeQuietly(stream);
                }
            });
        }

        @Override
        public void onNext(ByteBuffer item) {
            downstream.onNext(List.of(item));
        }

        @Override
        public void onError(Throwable throwable) {
            closeQuietly(stream);
            downstream.onError(throwable instanceof UncheckedIOException unchecked && unchecked.getCause() != null
                    ? unchecked.getCause()
                    : throwable);
        }

        @Override
        public void onComplete() {
            downstream.onComplete();
        }
    }

    /** The facade's response in the JDK's shape. */
    private record AdaptedResponse<T>(OutboundResponse<InputStream> response, HttpHeaders headers, HttpRequest request, T body)
            implements
                HttpResponse<T> {

        @Override
        public int statusCode() {
            return response.status();
        }

        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return response.finalUri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
