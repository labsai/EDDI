/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import io.nats.client.AuthenticationException;
import io.nats.client.Consumer;
import io.nats.client.ErrorListener;
import io.nats.client.JetStream;
import io.nats.client.JetStreamManagement;
import io.nats.client.JetStreamOptions;
import io.nats.client.KeyValue;
import io.nats.client.KeyValueManagement;
import io.nats.client.KeyValueOptions;
import io.nats.client.Nats;
import io.nats.client.Options;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The one owner of the {@code io.nats.client.Connection}.
 * <p>
 * <b>Boot never waits for NATS.</b> The connection is opened with
 * {@code Nats.connectAsynchronously(options, true)}: the client keeps retrying
 * in the background (reconnect-on-connect) and the node starts serving
 * immediately, in degraded mode until the first CONNECTED event. The old
 * coordinator connected synchronously from a {@code @PostConstruct} with a 10 s
 * timeout, so a node booted while NATS was down failed every turn with a 500.
 * <p>
 * <b>Every NATS call fails fast.</b> {@link #requireConnected()} throws
 * {@link ClusterUnavailableException} at once while disconnected, and every
 * JetStream/KV call uses {@code eddi.nats.request-timeout} (2 s). A caller then
 * applies its documented degraded-mode decision instead of blocking a turn
 * thread for ten seconds.
 * <p>
 * <b>Degraded</b> means disconnected for longer than
 * {@code eddi.cluster.degraded.grace} (5 s): a short blip is ridden out, a real
 * outage switches every area to its degraded policy (see
 * {@code docs/clustering.md}).
 * <p>
 * <b>A misconfiguration is not an outage.</b> An authentication or TLS failure,
 * or client options that cannot be built, cannot heal by retrying; such a cause
 * is logged at ERROR, counted in {@code eddi.cluster.nats.errors{cause}} and
 * reported by {@link #readinessProblem()}, so the node leaves the load balancer
 * instead of serving every turn unfenced while all checks stay green.
 * <p>
 * Only resolved when {@code eddi.messaging.type=nats}: the bean is
 * {@code @Typed} to itself and reached through {@code Instance}, so an
 * in-memory deployment never instantiates it, registers none of its meters and
 * writes no line about NATS.
 */
@ApplicationScoped
@Typed(NatsConnectionManager.class)
public class NatsConnectionManager {

    private static final Logger LOGGER = Logger.getLogger(NatsConnectionManager.class);

    /** Connection states as this node sees them. */
    public enum Status {
        STOPPED, CONNECTING, CONNECTED, DISCONNECTED, CLOSED
    }

    /**
     * Why a NATS call or connection attempt failed — the {@code cause} tag of
     * {@code eddi.cluster.nats.errors}.
     */
    public enum ErrorCause {
        /** Bad credentials, an expired user or an authorization violation. */
        AUTH(true),
        /** A TLS handshake failure: untrusted, expired or mismatched certificate. */
        TLS(true),
        /** Client options could not be built (credentials or TLS material). */
        CONFIG(true),
        /** A subject permission violation on an established connection. */
        PERMISSION(false),
        /** A timeout talking to the server. */
        TIMEOUT(false),
        /** Connection refused, reset, unknown host and other network I/O. */
        IO(false),
        /** A subscription that cannot keep up with its messages. */
        SLOW_CONSUMER(false),
        /** An error the server reported that matches nothing above. */
        SERVER(false), OTHER(false);

        private final boolean misconfiguration;

        ErrorCause(boolean misconfiguration) {
            this.misconfiguration = misconfiguration;
        }

        /** True for a cause that retrying cannot heal. */
        public boolean isMisconfiguration() {
            return misconfiguration;
        }

        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Repeats of one cause are logged at most this often. */
    static final long ERROR_LOG_INTERVAL_MILLIS = 60_000;

    private final ClusterConfig config;
    private final NodeIdentity node;
    private final MeterRegistry meterRegistry;

    private final List<Runnable> connectedHooks = new CopyOnWriteArrayList<>();
    private final List<Runnable> shutdownHooks = new CopyOnWriteArrayList<>();
    private final AtomicLong reconnects = new AtomicLong();

    private volatile Connection connection;
    /**
     * The client's connection object as soon as ANY callback has handed it to us —
     * including the error listener while the first connect is still failing. Kept
     * so that {@link #shutdown()} can close a connection that never reached
     * CONNECTED; otherwise its reconnect loop would outlive the application.
     */
    private volatile Connection anyConnection;
    private volatile Status status = Status.STOPPED;
    /**
     * When the connection was last lost, or when connecting started; 0 while
     * connected.
     */
    private volatile long unavailableSinceMillis;
    /**
     * Whether this node has been connected at least once since it booted; until
     * then it cannot take a single lease (see {@link #readinessProblem()}).
     */
    private volatile boolean everConnected;
    /**
     * The last failure retrying cannot heal, while it is unresolved: cleared by the
     * next successful connection.
     */
    private volatile ErrorCause misconfiguration;
    private volatile String misconfigurationDetail;
    private final Map<ErrorCause, Counter> errorCounters = new EnumMap<>(ErrorCause.class);
    private final Map<ErrorCause, AtomicLong> lastLoggedMillis = new EnumMap<>(ErrorCause.class);
    private volatile ScheduledExecutorService scheduler;
    /**
     * Lease heartbeats and lease-waiter timers only. Kept apart from
     * {@link #scheduler} so that slow work there — reconnect hooks, sweeps that
     * query the database — can never delay a renewal past the lease TTL.
     */
    private volatile ScheduledExecutorService leaseScheduler;
    /**
     * Runs the on-connect hooks. They do blocking NATS and database I/O (outbox
     * flush, bucket provisioning, stale-lease sweep) right when NATS has just come
     * back, so they get threads of their own instead of the scheduler's.
     */
    private final ExecutorService hookExecutor;
    private volatile JetStream jetStream;
    private volatile JetStreamManagement jetStreamManagement;
    private volatile KeyValueManagement keyValueManagement;
    private Counter reconnectCounter;

    @Inject
    public NatsConnectionManager(ClusterConfig config, NodeIdentity node, MeterRegistry meterRegistry) {
        this.config = config;
        this.node = node;
        this.meterRegistry = meterRegistry;
        // Created here rather than in start(): components built before the startup
        // event (a lease manager injected into an early bean) can already schedule.
        // The two timer threads only keep time; every job runs on a virtual thread, so
        // jobs that block on NATS or the database cannot delay presence past its TTL.
        this.scheduler = new OffloadingScheduler(Executors.newScheduledThreadPool(2, daemon("eddi-cluster-" + node.nodeId())),
                "eddi-cluster-" + node.nodeId());
        for (ErrorCause cause : ErrorCause.values()) {
            lastLoggedMillis.put(cause, new AtomicLong());
        }
        this.leaseScheduler = Executors.newSingleThreadScheduledExecutor(daemon("eddi-cluster-lease-" + node.nodeId()));
        this.hookExecutor = Executors.newFixedThreadPool(2, daemon("eddi-cluster-connect-" + node.nodeId()));
    }

    private static ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    /**
     * Starts connecting in the background and returns at once. Idempotent.
     */
    public synchronized void start() {
        if (status != Status.STOPPED) {
            return;
        }
        status = Status.CONNECTING;
        unavailableSinceMillis = System.currentTimeMillis();
        registerMeters();
        Options options;
        try {
            options = buildOptions();
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            // Misconfigured credentials or TLS material cannot heal by retrying: say so
            // loudly, and keep the node out of the load balancer (readinessProblem) rather
            // than serving every turn unfenced.
            recordError(ErrorCause.CONFIG, "NATS client options could not be built (credentials/TLS): " + e.getMessage(), e);
            return;
        }
        try {
            LOGGER.infof("Cluster mode: node %s connecting to NATS %s (asynchronously; the node serves in degraded mode until connected)",
                    node, config.natsServersForLog());
            Nats.connectAsynchronously(options, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    Options buildOptions() throws IOException, GeneralSecurityException {
        Options.Builder builder = new Options.Builder()
                .servers(config.natsServers().toArray(String[]::new))
                .connectionName(node.nodeId())
                .connectionTimeout(config.natsConnectionTimeout())
                .reconnectWait(config.natsReconnectWait())
                .reconnectJitter(Duration.ofMillis(500))
                .maxReconnects(-1)
                .reconnectBufferSize(8L * 1024 * 1024)
                // A server lost without a TCP reset is noticed only through missed pings;
                // the jnats default (2 min x 2) kept a dead connection "up" for minutes.
                .pingInterval(config.natsPingInterval())
                .maxPingsOut(config.natsMaxPingsOut())
                .inboxPrefix(config.natsInboxPrefix() + ".")
                .connectionListener(new Listener())
                .errorListener(new ClassifyingErrorListener());
        if (config.natsUsername().isPresent()) {
            builder.userInfo(config.natsUsername().get(), config.natsPassword().orElse(""));
        }
        if (config.natsToken().isPresent()) {
            builder.token(config.natsToken().get().toCharArray());
        }
        if (config.natsCredsFile().isPresent()) {
            builder.authHandler(Nats.credentials(config.natsCredsFile().get()));
        } else if (config.natsNkeySeedFile().isPresent()) {
            String seed = Files.readString(Path.of(config.natsNkeySeedFile().get()), StandardCharsets.UTF_8).trim();
            builder.authHandler(Nats.staticCredentials(null, seed.toCharArray()));
        }
        // Not only when eddi.nats.tls.enabled: a tls:// or wss:// server without an
        // explicit context made jnats fall back to the JVM default context, which
        // checks no host name — any CA-signed certificate for any host was accepted.
        if (config.natsTlsRequired()) {
            builder.sslContext(buildSslContext());
        }
        return builder.build();
    }

    private SSLContext buildSslContext() throws IOException, GeneralSecurityException {
        TrustManagerFactory tmf = null;
        if (config.natsTlsTruststorePath().isPresent()) {
            KeyStore trust = loadTrustStore(config.natsTlsTruststorePath().get(), config.natsTlsTruststorePassword().orElse(""));
            tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trust);
        }
        KeyManagerFactory kmf = null;
        if (config.natsTlsKeystorePath().isPresent()) {
            char[] password = config.natsTlsKeystorePassword().orElse("").toCharArray();
            KeyStore keys = loadKeyStore(config.natsTlsKeystorePath().get(), new String(password));
            kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keys, password);
        }
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf == null ? null : kmf.getKeyManagers(), tmf == null ? null : tmf.getTrustManagers(), null);
        // jnats sets no endpoint identification algorithm, so without this the JDK
        // accepts a trusted certificate issued for ANY host.
        return config.natsTlsVerifyHostname() ? new HostnameVerifyingSslContext(context) : context;
    }

    /**
     * A trust store from a PKCS12/JKS file or from PEM certificates — the
     * {@code ca.crt} of a {@code kubernetes.io/tls} Secret (cert-manager), which is
     * what the Helm chart mounts. A PEM file may hold a whole chain.
     */
    static KeyStore loadTrustStore(String path, String password) throws IOException, GeneralSecurityException {
        if (!isPem(Path.of(path))) {
            return loadKeyStore(path, password);
        }
        Collection<? extends Certificate> certificates;
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            certificates = CertificateFactory.getInstance("X.509").generateCertificates(in);
        }
        if (certificates.isEmpty()) {
            throw new CertificateException("No certificate in the PEM trust store " + path);
        }
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        int i = 0;
        for (Certificate certificate : certificates) {
            store.setCertificateEntry("nats-ca-" + i++, certificate);
        }
        return store;
    }

    private static boolean isPem(Path path) throws IOException {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".pem") || name.endsWith(".crt") || name.endsWith(".cer")) {
            return true;
        }
        try (InputStream in = Files.newInputStream(path)) {
            byte[] head = in.readNBytes(64);
            return new String(head, StandardCharsets.US_ASCII).contains("-----BEGIN");
        }
    }

    private static KeyStore loadKeyStore(String path, String password) throws IOException, GeneralSecurityException {
        String type = path.endsWith(".p12") || path.endsWith(".pfx") ? "PKCS12" : KeyStore.getDefaultType();
        KeyStore store = KeyStore.getInstance(type);
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            store.load(in, password.toCharArray());
        }
        return store;
    }

    void registerMeters() {
        Gauge.builder("eddi.cluster.nats.connected", this, m -> m.isConnected() ? 1 : 0)
                .description("1 while this node is connected to NATS").register(meterRegistry);
        Gauge.builder("eddi.cluster.degraded", this, m -> m.isDegraded() ? 1 : 0)
                .description("1 while this node runs in degraded mode (NATS unreachable for longer than the grace)")
                .register(meterRegistry);
        reconnectCounter = Counter.builder("eddi.cluster.nats.reconnects").description("NATS reconnects of this node")
                .register(meterRegistry);
        // Registered eagerly, every cause at 0, so that increase() sees the first error
        // on a pod rather than starting the series at 1.
        for (ErrorCause cause : ErrorCause.values()) {
            errorCounters.put(cause, Counter.builder("eddi.cluster.nats.errors").tag("cause", cause.tag())
                    .description("NATS errors of this node, by cause").register(meterRegistry));
        }
        Gauge.builder("eddi.cluster.nats.misconfigured", this, m -> m.misconfiguration == null ? 0 : 1)
                .description("1 while the NATS connection fails for a reason retrying cannot heal (auth, TLS, client options)")
                .register(meterRegistry);
    }

    /**
     * Counts an error under its cause and logs it: a misconfiguration at ERROR, any
     * other cause at WARN — each cause at most once a minute, so a reconnect loop
     * does not flood the log, and the rest at DEBUG.
     */
    void recordError(ErrorCause cause, String message, Throwable error) {
        Counter counter = errorCounters.get(cause);
        if (counter != null) {
            counter.increment();
        }
        if (cause.isMisconfiguration()) {
            misconfiguration = cause;
            misconfigurationDetail = message;
        }
        long now = System.currentTimeMillis();
        AtomicLong last = lastLoggedMillis.get(cause);
        long previous = last.get();
        boolean log = now - previous >= ERROR_LOG_INTERVAL_MILLIS && last.compareAndSet(previous, now);
        if (!log) {
            LOGGER.debugf(error, "NATS %s error (node %s): %s", cause.tag(), node, message);
        } else if (cause.isMisconfiguration()) {
            LOGGER.errorf(error, "NATS %s error (node %s): %s — this cannot heal by retrying; the node reports not-ready "
                    + "until it connects (eddi.cluster.readiness.fail-on-misconfiguration)", cause.tag(), node, message);
        } else {
            LOGGER.warnf("NATS %s error (node %s): %s", cause.tag(), node, message);
        }
    }

    /** The cause of a NATS exception, from its type and its chain of causes. */
    static ErrorCause classify(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof AuthenticationException) {
                return ErrorCause.AUTH;
            }
            if (t instanceof SSLException || t instanceof CertificateException) {
                return ErrorCause.TLS;
            }
            if (t instanceof TimeoutException || t instanceof SocketTimeoutException) {
                return ErrorCause.TIMEOUT;
            }
            String message = t.getMessage();
            if (message != null) {
                ErrorCause byMessage = classify(message);
                if (byMessage == ErrorCause.AUTH || byMessage == ErrorCause.PERMISSION) {
                    return byMessage;
                }
            }
        }
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof IOException) {
                return ErrorCause.IO;
            }
        }
        return ErrorCause.OTHER;
    }

    /** The cause of an error the server reported ({@code -ERR '...'}). */
    static ErrorCause classify(String error) {
        String e = error == null ? "" : error.toLowerCase(Locale.ROOT);
        if (e.contains("authorization violation") || e.contains("authentication") || e.contains("user authentication expired")
                || e.contains("account authentication expired")) {
            return ErrorCause.AUTH;
        }
        if (e.contains("permissions violation")) {
            return ErrorCause.PERMISSION;
        }
        if (e.contains("tls") || e.contains("certificate")) {
            return ErrorCause.TLS;
        }
        if (e.contains("slow consumer")) {
            return ErrorCause.SLOW_CONSUMER;
        }
        return ErrorCause.SERVER;
    }

    /**
     * Why this node should not take traffic, if it should not: it has never
     * connected since boot ({@code eddi.cluster.readiness.require-first-connect}),
     * or its connection fails for a reason retrying cannot heal
     * ({@code eddi.cluster.readiness.fail-on-misconfiguration}). Empty while
     * connected — and during an ordinary outage after a first connection, which
     * every replica sees at once and which must never empty the load balancer.
     */
    public Optional<String> readinessProblem() {
        if (isConnected()) {
            return Optional.empty();
        }
        ErrorCause cause = misconfiguration;
        if (cause != null && config.readinessFailOnMisconfiguration()) {
            return Optional.of("NATS " + cause.tag() + " error: " + misconfigurationDetail);
        }
        if (!everConnected && config.readinessRequireFirstConnect()) {
            return Optional.of("not connected to NATS since boot");
        }
        return Optional.empty();
    }

    /** True once this node has been connected at least once since it booted. */
    public boolean hasEverConnected() {
        return everConnected;
    }

    /** Runs {@code hook} after every CONNECTED/RECONNECTED, off the NATS thread. */
    public void onConnected(Runnable hook) {
        connectedHooks.add(hook);
        if (isConnected()) {
            // Same executor as the connection listener: a hook registered after the
            // connection is up must not take a scheduler thread either.
            try {
                hookExecutor.execute(() -> runHook(hook));
            } catch (RejectedExecutionException e) {
                LOGGER.debugf("On-connect hook not run (shutting down): %s", e.getMessage());
            }
        }
    }

    /** Runs {@code hook} while the connection is still open, before it drains. */
    public void onShutdown(Runnable hook) {
        shutdownHooks.add(hook);
    }

    private void runHook(Runnable hook) {
        try {
            hook.run();
        } catch (RuntimeException e) {
            LOGGER.warnf(e, "Cluster on-connect hook failed: %s", e.getMessage());
        }
    }

    private final class Listener implements ConnectionListener {
        @Override
        public void connectionEvent(Connection conn, Events type) {
            anyConnection = conn;
            switch (type) {
                case CONNECTED, RECONNECTED -> {
                    boolean reconnect = type == Events.RECONNECTED || connection != null;
                    connection = conn;
                    initContexts(conn);
                    status = Status.CONNECTED;
                    unavailableSinceMillis = 0;
                    everConnected = true;
                    misconfiguration = null;
                    misconfigurationDetail = null;
                    warnAboutReplication(conn);
                    if (reconnect) {
                        reconnects.incrementAndGet();
                        if (reconnectCounter != null) {
                            reconnectCounter.increment();
                        }
                    }
                    LOGGER.infof("NATS %s (node %s, server %s)", type == Events.CONNECTED ? "connected" : "reconnected", node,
                            ClusterConfig.redactUserInfo(conn.getConnectedUrl()));
                    for (Runnable hook : connectedHooks) {
                        try {
                            hookExecutor.execute(() -> runHook(hook));
                        } catch (RejectedExecutionException e) {
                            LOGGER.debugf("On-connect hook not run (shutting down): %s", e.getMessage());
                        }
                    }
                }
                case DISCONNECTED -> {
                    if (status == Status.CONNECTED) {
                        unavailableSinceMillis = System.currentTimeMillis();
                        LOGGER.warnf("NATS disconnected (node %s) — reconnecting; degraded mode after %s", node, config.degradedGrace());
                    }
                    status = Status.DISCONNECTED;
                }
                case CLOSED -> {
                    status = Status.CLOSED;
                    if (unavailableSinceMillis == 0) {
                        unavailableSinceMillis = System.currentTimeMillis();
                    }
                }
                default -> {
                    // RESUBSCRIBED, DISCOVERED_SERVERS, LAME_DUCK: nothing to do
                }
            }
        }
    }

    private void initContexts(Connection conn) {
        try {
            JetStreamOptions jso = JetStreamOptions.builder().requestTimeout(config.natsRequestTimeout()).build();
            jetStream = conn.jetStream(jso);
            jetStreamManagement = conn.jetStreamManagement(jso);
            keyValueManagement = conn.keyValueManagement(KeyValueOptions.builder().jetStreamOptions(jso).build());
        } catch (IOException e) {
            LOGGER.warnf(e, "Could not create JetStream contexts: %s", e.getMessage());
        }
    }

    /**
     * R1 buckets and streams on a NATS cluster of three or more servers lose their
     * data, and this cluster its lease service, with any single server; the default
     * {@code eddi.nats.replicas=1} used to be applied silently to an external
     * three-node NATS. A replica count above the servers the client knows of cannot
     * be placed at all.
     */
    void warnAboutReplication(Connection conn) {
        int servers;
        try {
            servers = conn.getServers() == null ? 0 : conn.getServers().size();
        } catch (RuntimeException e) {
            return;
        }
        int replicas = config.natsReplicas();
        if (replicas == 1 && servers >= 3) {
            LOGGER.warnf("eddi.nats.replicas=1 on a NATS cluster of %d servers: every bucket and stream (leases, nonces, dead letters, "
                    + "audit positions) lives on ONE server and is lost or unavailable with it. Set eddi.nats.replicas=3", servers);
        } else if (servers > 0 && replicas > servers) {
            LOGGER.warnf("eddi.nats.replicas=%d but the client knows only %d NATS server(s): buckets and streams cannot be "
                    + "created with more replicas than there are servers", replicas, servers);
        }
    }

    /**
     * Classifies every error the client reports, counts it and logs it (see
     * {@link #recordError}). A refused connection while reconnecting is reported on
     * every attempt; the rate limit keeps it to one WARN a minute.
     */
    private final class ClassifyingErrorListener implements ErrorListener {
        @Override
        public void errorOccurred(Connection conn, String error) {
            anyConnection = conn;
            recordError(classify(error), error, null);
        }

        @Override
        public void exceptionOccurred(Connection conn, Exception exp) {
            anyConnection = conn;
            ErrorCause cause = classify(exp);
            recordError(cause, exp.getClass().getSimpleName() + ": " + exp.getMessage(), cause.isMisconfiguration() ? exp : null);
        }

        @Override
        public void slowConsumerDetected(Connection conn, Consumer consumer) {
            recordError(ErrorCause.SLOW_CONSUMER, "slow consumer detected: messages are being dropped", null);
        }
    }

    // ---- status ----

    public Status status() {
        return status;
    }

    public boolean isConnected() {
        Connection c = connection;
        return status == Status.CONNECTED && c != null && c.getStatus() == Connection.Status.CONNECTED;
    }

    /** Disconnected for longer than the degraded grace (or never connected yet). */
    public boolean isDegraded() {
        if (isConnected()) {
            return false;
        }
        long since = unavailableSinceMillis;
        return since != 0 && System.currentTimeMillis() - since >= config.degradedGrace().toMillis();
    }

    /** When the node became unavailable (epoch millis), or 0 while connected. */
    public long unavailableSinceMillis() {
        return isConnected() ? 0 : unavailableSinceMillis;
    }

    public long reconnectCount() {
        return reconnects.get();
    }

    /** Round-trip time to the server in millis, or -1 when unknown. */
    public long rttMillis() {
        Connection c = connection;
        if (!isConnected() || c == null) {
            return -1;
        }
        try {
            return c.RTT().toMillis();
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    public ClusterConfig config() {
        return config;
    }

    public NodeIdentity node() {
        return node;
    }

    // ---- access for the cluster components (fail fast) ----

    public Connection requireConnected() {
        Connection c = connection;
        if (!isConnected() || c == null) {
            throw new ClusterUnavailableException("NATS is not connected (status " + status + ")");
        }
        return c;
    }

    public JetStream jetStream() {
        requireConnected();
        return jetStream;
    }

    public JetStreamManagement jetStreamManagement() {
        requireConnected();
        return jetStreamManagement;
    }

    public KeyValueManagement keyValueManagement() {
        requireConnected();
        return keyValueManagement;
    }

    public KeyValue keyValue(String bucket) {
        Connection c = requireConnected();
        try {
            return c.keyValue(bucket, KeyValueOptions.builder()
                    .jetStreamOptions(JetStreamOptions.builder().requestTimeout(config.natsRequestTimeout()).build()).build());
        } catch (IOException e) {
            throw new ClusterUnavailableException("Cannot open KV bucket " + bucket, e);
        }
    }

    /**
     * The node's scheduler for presence, polls and sweeps; never a turn thread. Its
     * tasks run on virtual threads (see {@link OffloadingScheduler}), so they may
     * block on NATS or the database without delaying each other.
     */
    public ScheduledExecutorService scheduler() {
        ScheduledExecutorService s = scheduler;
        if (s == null) {
            throw new IllegalStateException("NatsConnectionManager not started");
        }
        return s;
    }

    /**
     * A JetStream management context whose calls wait at most
     * {@code requestTimeout}, for a caller with a budget smaller than the default
     * request timeout.
     *
     * @throws ClusterUnavailableException
     *             while disconnected
     */
    public JetStreamManagement jetStreamManagement(Duration requestTimeout) {
        try {
            return requireConnected().jetStreamManagement(JetStreamOptions.builder().requestTimeout(requestTimeout).build());
        } catch (IOException e) {
            throw new ClusterUnavailableException("Cannot create a JetStream management context: " + e.getMessage(), e);
        }
    }

    /**
     * The scheduler for lease heartbeats and lease-waiter timers, and nothing else.
     * Tasks on it must be short and must not block: anything slow belongs on
     * {@link #scheduler()}.
     */
    public ScheduledExecutorService leaseScheduler() {
        ScheduledExecutorService s = leaseScheduler;
        if (s == null) {
            throw new IllegalStateException("NatsConnectionManager not started");
        }
        return s;
    }

    /** The scheduler, or {@code null} before {@link #start()} (tests). */
    ScheduledExecutorService schedulerOrNull() {
        return scheduler;
    }

    /**
     * Releases what this node holds (leases, through the registered hooks), then
     * drains and closes the connection. Never leaves a half-open connection behind,
     * even when start failed part-way.
     */
    @PreDestroy
    public synchronized void shutdown() {
        if (status == Status.STOPPED && connection == null) {
            return;
        }
        for (Runnable hook : shutdownHooks) {
            runHook(hook);
        }
        Connection c = connection != null ? connection : anyConnection;
        connection = null;
        anyConnection = null;
        status = Status.CLOSED;
        if (c != null && c.getStatus() == Connection.Status.CONNECTED) {
            try {
                c.drain(Duration.ofSeconds(5)).get(6, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOGGER.debugf("NATS drain did not complete cleanly: %s", e.getMessage());
            }
        }
        if (c != null) {
            try {
                c.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        ScheduledExecutorService s = scheduler;
        if (s != null) {
            s.shutdownNow();
        }
        ScheduledExecutorService ls = leaseScheduler;
        if (ls != null) {
            ls.shutdownNow();
        }
        hookExecutor.shutdownNow();
    }
}
