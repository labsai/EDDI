/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.outbound;

import ai.labs.eddi.engine.tracing.TracePropagation;
import ai.labs.eddi.engine.httpclient.outbound.OutboundEngine.Hop;
import ai.labs.eddi.engine.httpclient.outbound.OutboundEngine.Received;
import ai.labs.eddi.modules.llm.tools.UrlValidationUtils;
import ai.labs.eddi.utils.LogSanitizer;
import ai.labs.eddi.secrets.SecretResolver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.vertx.core.Context;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.Security;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

/**
 * The one way EDDI-owned code sends an HTTP request out of the process.
 * <p>
 * Every outbound policy lives here, and only here; an {@link OutboundEngine}
 * sends single hops and nothing else:
 * <ul>
 * <li><b>SSRF</b> — the initial URL and every redirect hop are checked as the
 * request's {@link SsrfPolicy} says — before the hop is sent, and again once
 * its response headers are in, so a name that rebinds to a refused address
 * between the check and the connection gets no response back
 * ({@link #recheckAfterExchange}).</li>
 * <li><b>Redirects</b> — never followed by the engine. Followed here hop by
 * hop, up to the policy's limit, with the method rewritten as its
 * {@link MethodRule} says.</li>
 * <li><b>Credentials</b> — on a cross-origin hop, {@link #SENSITIVE_HEADERS}
 * and every header the request marked as caller-derived are dropped.</li>
 * <li><b>Trace context</b> — the W3C {@code traceparent}/{@code tracestate} of
 * the calling thread's span (a tool call's {@code execute_tool} span) is added
 * to the first hop unless the request already carries one, kept on same-origin
 * hops and dropped on a cross-origin one; see {@link #withTraceContext}.</li>
 * <li><b>Time</b> — one wall-clock deadline covers every hop, including
 * discarding redirect bodies; see {@link #totalBudget}. A body read has its own
 * budget, a multiple of the per-hop timeout.</li>
 * <li><b>Size</b> — every body is capped while it is read ({@link BodyMode}); a
 * redirect body is dropped after {@value #MAX_REDIRECT_BODY_BYTES} bytes and
 * never reaches the caller.</li>
 * <li><b>Threads</b> — calls block, so they are refused on a Vert.x event-loop
 * thread.</li>
 * </ul>
 */
@ApplicationScoped
public class OutboundHttp {

    private static final Logger LOGGER = Logger.getLogger(OutboundHttp.class);

    /**
     * Security-sensitive headers stripped on cross-origin redirects. Beyond the
     * three RFC-managed ones, this also carries the widely-used custom credential
     * header names: a redirect from a public host to another origin must not replay
     * an {@code X-Api-Key} (or the like) any more than it may replay
     * {@code Authorization}.
     * <p>
     * Public and lower-cased so the Vert.x httpcalls client
     * ({@code HttpClientModule}) strips the same set on its own cross-origin
     * redirect hops.
     */
    public static final Set<String> SENSITIVE_HEADERS = Set.of("authorization", "cookie", "proxy-authorization",
            "x-api-key", "api-key", "apikey", "x-auth-token", "x-access-token", "x-amz-security-token", "authentication");

    /**
     * Per-hop response timeout applied when the request sets none. It bounds only
     * the wait for the response headers, which is why {@link #totalBudget} exists.
     */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(15);

    /**
     * Most bytes of a redirect response's body read before it is dropped. A
     * redirect body is only a courtesy page; nothing reads it.
     */
    static final long MAX_REDIRECT_BODY_BYTES = 64 * 1024;

    /** The multiple of the per-hop timeout a buffered body read may take. */
    private static final int BODY_READ_TIMEOUT_FACTOR = 4;

    private static final Set<Integer> REDIRECT_CODES = Set.of(301, 302, 303, 307, 308);

    /** Headers the engine sets itself — never carried to a redirect hop. */
    private static final Set<String> MANAGED_HEADERS = Set.of("host", "content-length", "connection");

    /** Method tag values; anything else is tagged {@code OTHER}. */
    private static final Set<String> TAGGED_METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

    private static final String DEFAULT_USER_AGENT = "EDDI-Agent/1.0";

    private static final int BUFFER_SIZE = 8192;

    private final OutboundSettings settings;
    private final OutboundEngine engine;
    private final boolean ssrfProtectionEnabled;
    private final MeterRegistry meterRegistry;

    /** The full SSRF check ({@link SsrfPolicy#STRICT}). */
    private final TargetValidator strictCheck;

    /** The cloud-metadata check ({@link SsrfPolicy#METADATA_ONLY}). */
    private final TargetValidator metadataCheck;

    /**
     * Closes a body whose read has stalled past its budget — a server that sent its
     * headers and then nothing never returns from a read on its own.
     */
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "outbound-http-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Outbound W3C trace propagation. Field-injected and null-tolerant: a
     * {@link #standalone} facade (tests, code outside CDI) has none, which means no
     * trace headers are added.
     */
    @Inject
    TracePropagation tracePropagation;

    @Inject
    public OutboundHttp(Config config, Instance<TlsConfigurationRegistry> tlsRegistry, Instance<SecretResolver> secretResolver,
            MeterRegistry meterRegistry) {
        this(OutboundSettings.fromConfig(config), tlsRegistry, secretResolver,
                config.getOptionalValue("eddi.security.ssrf-protection.enabled", Boolean.class).orElse(false), meterRegistry);
    }

    private OutboundHttp(OutboundSettings settings, Instance<TlsConfigurationRegistry> tlsRegistry,
            Instance<SecretResolver> secretResolver, boolean ssrfProtectionEnabled, MeterRegistry meterRegistry) {
        this(settings, new JdkOutboundEngine(settings, tlsContexts(tlsRegistry), secrets(secretResolver)),
                ssrfProtectionEnabled, meterRegistry);
        if (positiveDnsCacheDisabled(Security.getProperty(DNS_CACHE_TTL_PROPERTY), System.getProperty(LEGACY_DNS_CACHE_TTL_PROPERTY))) {
            LOGGER.warnf("The JVM's positive DNS cache is disabled (%s=0). The SSRF checks of outbound requests resolve a host "
                    + "and the HTTP client resolves it again to connect; without the cache the two lookups can return different "
                    + "answers, which is what a DNS-rebinding attack relies on. Leave %s unset (30s) or set it above 0.",
                    DNS_CACHE_TTL_PROPERTY, DNS_CACHE_TTL_PROPERTY);
        }
    }

    OutboundHttp(OutboundSettings settings, OutboundEngine engine, boolean ssrfProtectionEnabled, MeterRegistry meterRegistry) {
        this(settings, engine, ssrfProtectionEnabled, meterRegistry, InetAddress::getAllByName);
    }

    /**
     * With an injectable resolver for the SSRF checks, so a test can play a name
     * whose answer changes between the check and the connection.
     */
    OutboundHttp(OutboundSettings settings, OutboundEngine engine, boolean ssrfProtectionEnabled, MeterRegistry meterRegistry,
            UrlValidationUtils.HostResolver hostResolver) {
        this.settings = Objects.requireNonNull(settings);
        this.engine = Objects.requireNonNull(engine);
        this.ssrfProtectionEnabled = ssrfProtectionEnabled;
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
        Objects.requireNonNull(hostResolver);
        this.strictCheck = url -> UrlValidationUtils.validateUrl(url, hostResolver);
        this.metadataCheck = url -> UrlValidationUtils.rejectCloudMetadataTarget(url, hostResolver);
    }

    /** The security property that sets the JVM's positive DNS cache lifetime. */
    static final String DNS_CACHE_TTL_PROPERTY = "networkaddress.cache.ttl";

    /**
     * Its system-property fallback, read only when the security property is unset.
     */
    static final String LEGACY_DNS_CACHE_TTL_PROPERTY = "sun.net.inetaddr.ttl";

    /**
     * Whether the JVM caches no successful DNS lookup at all — the security
     * property, else the system property, set to {@code 0}. Unset means the JDK
     * default of 30 seconds; a negative value means forever.
     */
    static boolean positiveDnsCacheDisabled(String securityValue, String systemValue) {
        String value = securityValue != null && !securityValue.isBlank() ? securityValue : systemValue;
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            return Integer.parseInt(value.trim()) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * A facade outside CDI — every profile on the given connect timeout, no proxy,
     * no named TLS configuration, SSRF protection flag off, meters kept locally.
     * For tests and code that is not a bean.
     */
    public static OutboundHttp standalone(Duration connectTimeout) {
        OutboundSettings settings = OutboundSettings.defaults(connectTimeout);
        return new OutboundHttp(settings, new JdkOutboundEngine(settings, JdkOutboundEngine.TlsContexts.NONE, UnaryOperator.identity()),
                false, new SimpleMeterRegistry());
    }

    @PreDestroy
    void shutdown() {
        watchdog.shutdownNow();
    }

    /**
     * Sends {@code request}, follows redirects as its policy allows, and reads the
     * final body as {@code mode} says. Blocks the calling thread.
     *
     * @throws OutboundBlockedException
     *             the initial URL or a redirect hop is refused by the SSRF policy
     *             (an {@link IllegalArgumentException})
     * @throws BodyTooLargeException
     *             the body is over the cap and the mode says
     *             {@link BodyMode.OnOverflow#FAIL}
     * @throws OutboundTimeoutException
     *             the exchange or the body read ran out of time
     * @throws IllegalStateException
     *             called on a Vert.x event-loop thread
     */
    public <T> OutboundResponse<T> send(OutboundRequest request, BodyMode<T> mode) throws IOException, InterruptedException {
        requireNotOnEventLoop();
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "error";
        int status = 0;
        try {
            OutboundResponse<T> response = exchange(request, mode);
            status = response.status();
            outcome = "success";
            return response;
        } catch (OutboundBlockedException e) {
            outcome = "blocked";
            countBlocked(request.profile(), "ssrf");
            throw e;
        } catch (BodyTooLargeException e) {
            outcome = "blocked";
            countBlocked(request.profile(), "cap");
            throw e;
        } catch (RedirectLimitException e) {
            outcome = "blocked";
            countBlocked(request.profile(), "redirect");
            throw e;
        } catch (HttpTimeoutException e) {
            outcome = "timeout";
            throw e;
        } finally {
            String profile = request.profile().key();
            Counter.builder("eddi.http.outbound.requests")
                    .description("Outbound HTTP exchanges, redirects followed counted once")
                    .tag("profile", profile)
                    .tag("method", TAGGED_METHODS.contains(request.method()) ? request.method() : "OTHER")
                    .tag("status", status == 0 ? "none" : (status / 100) + "xx")
                    .tag("outcome", outcome)
                    .register(meterRegistry)
                    .increment();
            sample.stop(Timer.builder("eddi.http.outbound.duration")
                    .description("Outbound HTTP exchange time, to the end of a buffered body or the headers of a streamed one")
                    .tag("profile", profile)
                    .tag("outcome", outcome)
                    .register(meterRegistry));
        }
    }

    /**
     * The wall-clock budget for reaching the final response: every hop's headers
     * and every discarded redirect body. The profile's floor (three connect
     * timeouts unless configured), or the per-hop timeout when the caller asked for
     * longer. The final body is not inside it: it has its own read budget of
     * {@value #BODY_READ_TIMEOUT_FACTOR} × the per-hop timeout, so a large response
     * on a slow link is legitimate while a body that never ends is not. Neither
     * bound alone is enough — the JDK stops timing once the response headers
     * arrive, so a server that sent headers and then trickled the body a byte at a
     * time used to hold the call open for as long as it liked.
     */
    public Duration totalBudget(Profile profile, Duration hopTimeout) {
        Duration floor = settings.profile(profile).budgetFloor();
        return hopTimeout.compareTo(floor) > 0 ? hopTimeout : floor;
    }

    /**
     * {@code uri} without its query or fragment, for an error message. A query
     * string may carry a credential ({@code key=}, {@code appid=}), and a tool
     * hands the message of a failed call to the model and the log.
     */
    public static String withoutQuery(URI uri) {
        int port = uri.getPort();
        return uri.getScheme() + "://" + uri.getHost() + (port == -1 ? "" : ":" + port) + (uri.getRawPath() == null ? "" : uri.getRawPath());
    }

    private <T> OutboundResponse<T> exchange(OutboundRequest request, BodyMode<T> mode) throws IOException, InterruptedException {
        Duration hopTimeout = request.timeoutOverride() != null ? request.timeoutOverride() : DEFAULT_REQUEST_TIMEOUT;
        // One deadline for the whole chain (see totalBudget): an attacker can
        // neither chain slow redirects nor trickle a redirect body to hold the
        // connection — and the calling thread — open indefinitely.
        long deadlineNanos = System.nanoTime() + totalBudget(request.profile(), hopTimeout).toNanos();
        SsrfPolicy ssrf = (request.ssrfOverride() != null ? request.ssrfOverride() : request.profile().defaultSsrf())
                .resolve(ssrfProtectionEnabled);
        RedirectPolicy redirects = request.redirectOverride() != null
                ? request.redirectOverride()
                : request.profile().defaultRedirects(ssrf);

        TargetValidator check = initialCheck(ssrf);
        validate(check, request.uri());

        String method = request.method();
        URI uri = request.uri();
        byte[] body = request.body().toBytes();
        Map<String, List<String>> headers = withTraceContext(withImpliedContentType(request.headers(), request.body()),
                tracePropagation);
        List<URI> chain = new ArrayList<>();
        int hops = 0;

        while (true) {
            Received received = engine.send(new Hop(request.profile(), method, uri, headers, body, hopTimeout, deadlineNanos));
            recheckAfterExchange(check, uri, received);
            int status = received.status();
            if (!(redirects instanceof RedirectPolicy.Follow follow) || !REDIRECT_CODES.contains(status)) {
                return read(request.profile(), received, mode, uri, chain, hopTimeout);
            }
            MethodRule.Action action = follow.rule().onRedirect(method, status);
            if (action == MethodRule.Action.RETURN) {
                return read(request.profile(), received, mode, uri, chain, hopTimeout);
            }
            discardRedirectBody(received, uri, deadlineNanos);

            hops++;
            if (hops > follow.maxHops()) {
                throw new RedirectLimitException("Too many redirects (" + hops + ") for URL: " + uri);
            }
            String location = first(received.headers(), "Location");
            if (location == null || location.isBlank()) {
                throw new IOException("Redirect " + status + " without Location header from: " + uri);
            }
            URI target;
            try {
                target = uri.resolve(location);
            } catch (IllegalArgumentException e) {
                // A server-chosen header that is not a URI is a broken redirect, not a
                // refused target: OutboundBlockedException is an IllegalArgumentException
                // too, and callers read that as "SSRF said no". No cause: the parser's
                // message quotes the raw header, query and all, and a tool hands this
                // exception to the model and the log.
                throw new IOException("Redirect " + status + " with an unparsable Location header from: " + withoutQuery(uri));
            }
            // Prevents SSRF via 302 → internal.
            check = hopCheck(ssrf, request.hopValidator());
            validate(check, target);
            // Both URIs come from the server (Location): sanitized so a crafted header
            // cannot forge log lines.
            LOGGER.debugf("Following redirect %d/%d: %s → %s", hops, follow.maxHops(), LogSanitizer.sanitize(withoutQuery(uri)),
                    LogSanitizer.sanitize(withoutQuery(target)));

            boolean preserved = action == MethodRule.Action.FOLLOW_PRESERVING;
            headers = redirectHeaders(headers, isSameOrigin(uri, target), preserved, request.callerDerivedHeaders());
            if (!preserved) {
                method = action == MethodRule.Action.FOLLOW_AS_HEAD ? "HEAD" : "GET";
                body = new byte[0];
            }
            chain.add(uri);
            uri = target;
        }
    }

    /**
     * The check the initial URL gets, or {@code null} for none: HOPS_ONLY trusts
     * the initial URL, and FOLLOW_GLOBAL_FLAG never reaches here unresolved.
     */
    private TargetValidator initialCheck(SsrfPolicy policy) {
        return switch (policy) {
            case STRICT -> strictCheck;
            case METADATA_ONLY -> metadataCheck;
            case HOPS_ONLY, NONE, FOLLOW_GLOBAL_FLAG -> null;
        };
    }

    /** The check a redirect hop gets, or {@code null} for none. */
    private TargetValidator hopCheck(SsrfPolicy policy, TargetValidator override) {
        if (override != null) {
            return override;
        }
        return switch (policy) {
            case STRICT, HOPS_ONLY -> strictCheck;
            case METADATA_ONLY -> metadataCheck;
            case NONE, FOLLOW_GLOBAL_FLAG -> null;
        };
    }

    /**
     * Runs the hop's check a second time, once its response headers are in, and
     * discards the response when the target no longer passes (DNS rebinding).
     * <p>
     * The check resolves the host through {@link InetAddress}; the JDK client
     * resolves it again to connect, and neither can be handed the other's answer.
     * Both normally read the JVM's positive DNS cache
     * ({@value #DNS_CACHE_TTL_PROPERTY}, 30 seconds by default), so they agree —
     * unless the cache entry expired in the instant between them, which is the
     * window a rebinding name aims for. The lookup the connection made in that case
     * is the one now cached, so asking again after the exchange sees the address
     * that was actually used, for as long as the exchange took less than the cache
     * lifetime. A target that has turned private (or into the metadata service) is
     * refused here: the request went out, but its response — the instance's
     * credentials, an internal page — never reaches the caller or a redirect.
     */
    private static void recheckAfterExchange(TargetValidator check, URI uri, Received received) {
        if (check == null) {
            return;
        }
        try {
            validate(check, uri);
        } catch (OutboundBlockedException e) {
            closeQuietly(received.body());
            throw new OutboundBlockedException("Response discarded: the target no longer passes the SSRF check after "
                    + "connecting (its address changed while the request was in flight): " + e.getMessage(), e);
        }
    }

    private static void validate(TargetValidator validator, URI target) {
        if (validator == null) {
            return;
        }
        try {
            validator.validate(target.toString());
        } catch (OutboundBlockedException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new OutboundBlockedException(e.getMessage(), e);
        }
    }

    private static Map<String, List<String>> withImpliedContentType(Map<String, List<String>> headers, RequestBody body) {
        String implied = body.contentType();
        if (implied == null || headers.containsKey("Content-Type")) {
            return headers;
        }
        TreeMap<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        copy.putAll(headers);
        copy.put("Content-Type", List.of(implied));
        return copy;
    }

    /**
     * {@code headers} plus the W3C trace context of the calling thread's current
     * span, or {@code headers} itself when there is nothing to add — propagation
     * off or absent, no valid span (the OTel SDK disabled), or the caller already
     * set a trace header, which is never overwritten.
     * <p>
     * Applied to the first hop only, on the calling thread, where the tool's
     * {@code execute_tool} span is current. A same-origin redirect hop inherits the
     * headers through {@link #redirectHeaders}; a cross-origin hop has them
     * stripped there, as credentials are, and gets none back.
     */
    static Map<String, List<String>> withTraceContext(Map<String, List<String>> headers, TracePropagation propagation) {
        if (propagation == null) {
            return headers;
        }
        TreeMap<String, List<String>> traced = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        traced.putAll(headers);
        Map<String, String> traceHeaders = propagation.headersMissingFrom(traced::containsKey);
        if (traceHeaders.isEmpty()) {
            return headers;
        }
        traceHeaders.forEach((name, value) -> traced.put(name, List.of(value)));
        return traced;
    }

    /**
     * The headers of a redirect hop. Same origin: everything but the engine-managed
     * headers. Cross-origin: also no {@link #SENSITIVE_HEADERS}, no W3C trace
     * context and no caller-derived header. A method downgraded to GET/HEAD loses
     * {@code Content-Type} with its body.
     */
    static Map<String, List<String>> redirectHeaders(Map<String, List<String>> previous, boolean sameOrigin,
                                                     boolean methodPreserved, Set<String> callerDerived) {
        TreeMap<String, List<String>> next = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        previous.forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (MANAGED_HEADERS.contains(lower)) {
                return;
            }
            if (!methodPreserved && "content-type".equals(lower)) {
                return;
            }
            if (!sameOrigin && (SENSITIVE_HEADERS.contains(lower) || callerDerived.contains(lower) || TracePropagation.isTraceHeader(lower))) {
                return;
            }
            next.put(name, values);
        });
        next.putIfAbsent("User-Agent", List.of(DEFAULT_USER_AGENT));
        return next;
    }

    /** Whether two URIs share scheme, host and port. */
    static boolean isSameOrigin(URI a, URI b) {
        if (!Objects.equals(a.getScheme(), b.getScheme())) {
            return false;
        }
        if (a.getHost() == null || !a.getHost().equalsIgnoreCase(b.getHost())) {
            return false;
        }
        return effectivePort(a) == effectivePort(b);
    }

    private static int effectivePort(URI uri) {
        int port = uri.getPort();
        if (port != -1) {
            return port;
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    /**
     * Drops a redirect response's body, reading at most
     * {@link #MAX_REDIRECT_BODY_BYTES} of it — none when its declared
     * {@code Content-Length} is above that — then closing the stream, which
     * releases or closes the connection. An oversized redirect body must neither be
     * downloaded (bandwidth, and the shared deadline) nor fail a legitimate
     * redirect over its courtesy page.
     */
    private void discardRedirectBody(Received received, URI uri, long deadlineNanos) throws IOException {
        InputStream body = received.body();
        OptionalLong declared = contentLength(received.headers());
        long remaining = deadlineNanos - System.nanoTime();
        if ((declared.isPresent() && declared.getAsLong() > MAX_REDIRECT_BODY_BYTES) || remaining <= 0) {
            closeQuietly(body);
            return;
        }
        AtomicBoolean expired = new AtomicBoolean();
        ScheduledFuture<?> guard = watchdog.schedule(() -> {
            expired.set(true);
            closeQuietly(body);
        }, remaining, TimeUnit.NANOSECONDS);
        try (InputStream in = body) {
            byte[] buffer = new byte[BUFFER_SIZE];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_REDIRECT_BODY_BYTES) {
                    break;
                }
            }
        } catch (IOException e) {
            if (expired.get()) {
                throw new OutboundTimeoutException("Total request timeout exceeded for: " + withoutQuery(uri));
            }
            throw e;
        } finally {
            guard.cancel(false);
        }
    }

    private <T> OutboundResponse<T> read(Profile profile, Received received, BodyMode<T> mode, URI uri, List<URI> chain,
                                         Duration hopTimeout)
            throws IOException {
        Map<String, List<String>> headers = OutboundRequest.caseInsensitiveCopy(received.headers());
        InputStream body = received.body();
        Duration budget = hopTimeout.multipliedBy(BODY_READ_TIMEOUT_FACTOR);
        Object value;
        boolean truncated = false;
        switch (mode.kind()) {
            case DISCARD -> {
                closeQuietly(body);
                value = null;
            }
            // Ownership passes to the caller with the response: closing it here would
            // hand back a dead stream. BodyMode.stream documents that the caller closes
            // it; until then the cap and the read budget still bound it.
            case STREAM -> value = new GuardedInputStream(body, mode.maxBytes(), mode.readBudget(), watchdog, uri);
            case FILE -> value = writeFile(profile, body, headers, mode.target(), mode.maxBytes(), budget, uri);
            case STRING, BYTES -> {
                BoundedBodyReader.Bounded bounded = readBuffered(body, headers, mode, hopTimeout, uri);
                countBytes(profile, bounded.bytes().length);
                truncated = bounded.truncated();
                value = mode.kind() == BodyMode.Kind.STRING
                        ? new String(bounded.bytes(), charsetOf(headers))
                        : bounded.bytes();
            }
            default -> throw new IllegalStateException("unknown body mode " + mode.kind());
        }
        @SuppressWarnings("unchecked")
        T typed = (T) value;
        return new OutboundResponse<>(received.status(), received.reasonPhrase(), headers, uri, chain, typed, truncated);
    }

    private BoundedBodyReader.Bounded readBuffered(InputStream body, Map<String, List<String>> headers, BodyMode<?> mode,
                                                   Duration hopTimeout, URI uri)
            throws IOException {
        long cap = mode.maxBytes();
        if (mode.onOverflow() == BodyMode.OnOverflow.TRUNCATE) {
            return BoundedBodyReader.read(body, cap, hopTimeout, watchdog);
        }
        OptionalLong declared = contentLength(headers);
        if (declared.isPresent() && declared.getAsLong() > cap) {
            closeQuietly(body);
            throw new BodyTooLargeException(cap);
        }
        // One byte past the cap tells "exactly at the cap" from "over it".
        BoundedBodyReader.Bounded bounded = BoundedBodyReader.read(body, cap == Long.MAX_VALUE ? cap : cap + 1, hopTimeout,
                watchdog);
        if (bounded.bytes().length > cap) {
            throw new BodyTooLargeException(cap);
        }
        if (bounded.truncated()) {
            throw new OutboundTimeoutException("Body read timeout exceeded for: " + withoutQuery(uri));
        }
        return bounded;
    }

    private Path writeFile(Profile profile, InputStream body, Map<String, List<String>> headers, Path target, long cap,
                           Duration budget, URI uri)
            throws IOException {
        OptionalLong declared = contentLength(headers);
        if (declared.isPresent() && declared.getAsLong() > cap) {
            closeQuietly(body);
            throw new BodyTooLargeException(cap);
        }
        AtomicBoolean expired = new AtomicBoolean();
        ScheduledFuture<?> guard = watchdog.schedule(() -> {
            expired.set(true);
            closeQuietly(body);
        }, budget.toNanos(), TimeUnit.NANOSECONDS);
        boolean complete = false;
        long total = 0;
        try (InputStream in = body;
                OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > cap) {
                    throw new BodyTooLargeException(cap);
                }
                out.write(buffer, 0, read);
            }
            if (expired.get()) {
                throw new OutboundTimeoutException("Body read timeout exceeded for: " + withoutQuery(uri));
            }
            complete = true;
        } catch (BodyTooLargeException | OutboundTimeoutException e) {
            throw e;
        } catch (IOException e) {
            if (expired.get()) {
                throw new OutboundTimeoutException("Body read timeout exceeded for: " + withoutQuery(uri));
            }
            throw e;
        } finally {
            guard.cancel(false);
            if (!complete) {
                Files.deleteIfExists(target);
            }
        }
        countBytes(profile, total);
        return target;
    }

    private static Charset charsetOf(Map<String, List<String>> headers) {
        String contentType = first(headers, "Content-Type");
        if (contentType != null) {
            for (String parameter : contentType.split(";")) {
                String trimmed = parameter.trim();
                if (trimmed.regionMatches(true, 0, "charset=", 0, "charset=".length())) {
                    String name = trimmed.substring("charset=".length()).replace("\"", "").trim();
                    try {
                        return Charset.forName(name);
                    } catch (IllegalArgumentException e) {
                        return StandardCharsets.UTF_8;
                    }
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static OptionalLong contentLength(Map<String, List<String>> headers) {
        String value = first(headers, "Content-Length");
        if (value == null) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    private static String first(Map<String, List<String>> headers, String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    private static void requireNotOnEventLoop() {
        if (Context.isOnEventLoopThread()) {
            throw new IllegalStateException("Outbound HTTP blocks the calling thread and must not be called on a Vert.x "
                    + "event-loop thread; move the call to a worker or virtual thread");
        }
    }

    private void countBlocked(Profile profile, String reason) {
        Counter.builder("eddi.http.outbound.blocked")
                .description("Outbound HTTP exchanges refused by policy")
                .tag("profile", profile.key())
                .tag("reason", reason)
                .register(meterRegistry)
                .increment();
    }

    private void countBytes(Profile profile, long bytes) {
        Counter.builder("eddi.http.outbound.bytes")
                .description("Response body bytes read by buffered outbound HTTP exchanges")
                .tag("profile", profile.key())
                .register(meterRegistry)
                .increment(bytes);
    }

    static void closeQuietly(InputStream stream) {
        try {
            if (stream != null) {
                stream.close();
            }
        } catch (IOException e) {
            // Nothing useful to do: the response is already decided.
        }
    }

    private static JdkOutboundEngine.TlsContexts tlsContexts(Instance<TlsConfigurationRegistry> registry) {
        if (!registry.isResolvable()) {
            return JdkOutboundEngine.TlsContexts.NONE;
        }
        return name -> {
            TlsConfiguration configuration = registry.get().get(name)
                    .orElseThrow(() -> new IllegalStateException("No TLS configuration named '" + name
                            + "' (quarkus.tls." + name + ".*) for eddi.http.outbound.*.tls.configuration-name"));
            try {
                return configuration.createSSLContext();
            } catch (Exception e) {
                throw new IllegalStateException("TLS configuration '" + name + "' cannot create an SSL context", e);
            }
        };
    }

    private static UnaryOperator<String> secrets(Instance<SecretResolver> resolver) {
        return value -> resolver.isResolvable() ? resolver.get().resolveValue(value) : value;
    }

    /** More redirects than the policy allows. An {@link IOException}, as ever. */
    static final class RedirectLimitException extends IOException {
        RedirectLimitException(String message) {
            super(message);
        }
    }

    /**
     * A streamed body with the cap and read budget of
     * {@link BodyMode#stream(long, Duration)} enforced on every read.
     */
    static final class GuardedInputStream extends FilterInputStream {
        private final long maxBytes;
        private final URI uri;
        private final AtomicBoolean expired = new AtomicBoolean();
        private final ScheduledFuture<?> guard;
        private long total;

        GuardedInputStream(InputStream in, long maxBytes, Duration readBudget, ScheduledExecutorService watchdog, URI uri) {
            super(in);
            this.maxBytes = maxBytes;
            this.uri = uri;
            this.guard = readBudget == null
                    ? null
                    : watchdog.schedule(() -> {
                        expired.set(true);
                        closeQuietly(in);
                    }, readBudget.toNanos(), TimeUnit.NANOSECONDS);
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int read = read(one, 0, 1);
            return read == -1 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            checkExpired();
            int read;
            try {
                read = super.read(buffer, offset, length);
            } catch (IOException e) {
                checkExpired();
                throw e;
            }
            if (read > 0) {
                count(read);
            }
            return read;
        }

        @Override
        public long skip(long n) throws IOException {
            // Through read(), so skipped bytes count against the cap too.
            byte[] buffer = new byte[(int) Math.min(BUFFER_SIZE, Math.max(n, 0))];
            long skipped = 0;
            while (skipped < n) {
                int read = read(buffer, 0, (int) Math.min(buffer.length, n - skipped));
                if (read == -1) {
                    break;
                }
                skipped += read;
            }
            return skipped;
        }

        @Override
        public void close() throws IOException {
            if (guard != null) {
                guard.cancel(false);
            }
            super.close();
        }

        private void count(int read) throws BodyTooLargeException {
            total += read;
            if (maxBytes > 0 && total > maxBytes) {
                closeQuietly(in);
                throw new BodyTooLargeException(maxBytes);
            }
        }

        private void checkExpired() throws OutboundTimeoutException {
            if (expired.get()) {
                throw new OutboundTimeoutException("Body read timeout exceeded for: " + withoutQuery(uri));
            }
        }
    }
}
