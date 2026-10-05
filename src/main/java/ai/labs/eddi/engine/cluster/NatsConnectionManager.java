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
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
    private volatile ScheduledExecutorService scheduler;
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
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "eddi-cluster-" + node.nodeId());
            t.setDaemon(true);
            return t;
        });
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
        } catch (IOException | GeneralSecurityException e) {
            // Misconfigured credentials or TLS material cannot heal by retrying: say so
            // loudly and stay degraded rather than failing the boot of an otherwise
            // working node.
            LOGGER.errorf(e, "NATS client options could not be built (credentials/TLS): %s — this node runs in degraded mode",
                    e.getMessage());
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
                .connectionListener(new Listener())
                .errorListener(new QuietErrorListener());
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
        if (config.natsTlsEnabled()) {
            builder.sslContext(buildSslContext());
        }
        return builder.build();
    }

    private SSLContext buildSslContext() throws IOException, GeneralSecurityException {
        TrustManagerFactory tmf = null;
        if (config.natsTlsTruststorePath().isPresent()) {
            KeyStore trust = loadKeyStore(config.natsTlsTruststorePath().get(), config.natsTlsTruststorePassword().orElse(""));
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
        return context;
    }

    private static KeyStore loadKeyStore(String path, String password) throws IOException, GeneralSecurityException {
        String type = path.endsWith(".p12") || path.endsWith(".pfx") ? "PKCS12" : KeyStore.getDefaultType();
        KeyStore store = KeyStore.getInstance(type);
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            store.load(in, password.toCharArray());
        }
        return store;
    }

    private void registerMeters() {
        Gauge.builder("eddi.cluster.nats.connected", this, m -> m.isConnected() ? 1 : 0)
                .description("1 while this node is connected to NATS").register(meterRegistry);
        Gauge.builder("eddi.cluster.degraded", this, m -> m.isDegraded() ? 1 : 0)
                .description("1 while this node runs in degraded mode (NATS unreachable for longer than the grace)")
                .register(meterRegistry);
        reconnectCounter = Counter.builder("eddi.cluster.nats.reconnects").description("NATS reconnects of this node")
                .register(meterRegistry);
    }

    /** Runs {@code hook} after every CONNECTED/RECONNECTED, off the NATS thread. */
    public void onConnected(Runnable hook) {
        connectedHooks.add(hook);
        if (isConnected() && scheduler != null) {
            scheduler.execute(() -> runHook(hook));
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
                    if (reconnect) {
                        reconnects.incrementAndGet();
                        if (reconnectCounter != null) {
                            reconnectCounter.increment();
                        }
                    }
                    LOGGER.infof("NATS %s (node %s, server %s)", type == Events.CONNECTED ? "connected" : "reconnected", node,
                            ClusterConfig.redactUserInfo(conn.getConnectedUrl()));
                    ScheduledExecutorService s = scheduler;
                    if (s != null) {
                        for (Runnable hook : connectedHooks) {
                            s.execute(() -> runHook(hook));
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

    private final class QuietErrorListener implements ErrorListener {
        @Override
        public void errorOccurred(Connection conn, String error) {
            anyConnection = conn;
            LOGGER.warnf("NATS error: %s", error);
        }

        @Override
        public void exceptionOccurred(Connection conn, Exception exp) {
            anyConnection = conn;
            // Connection refused while reconnecting is reported here on every attempt;
            // the state machine already says "disconnected", so keep it at DEBUG.
            LOGGER.debugf(exp, "NATS exception: %s", exp.getMessage());
        }

        @Override
        public void slowConsumerDetected(Connection conn, Consumer consumer) {
            LOGGER.warn("NATS slow consumer detected");
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
     * The node's scheduler for heartbeats, presence and polls; never a turn thread.
     */
    public ScheduledExecutorService scheduler() {
        ScheduledExecutorService s = scheduler;
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
    }
}
