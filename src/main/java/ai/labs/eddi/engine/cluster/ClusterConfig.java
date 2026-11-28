/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Every setting of the cluster layer, read once and validated.
 * <p>
 * {@code eddi.messaging.type} is read <em>at runtime</em> and selects between
 * the zero-dependency single-node default ({@code in-memory}) and the clustered
 * mode ({@code nats}), in which any number of EDDI replicas share one database
 * and one NATS JetStream cluster behind a plain round-robin load balancer. The
 * value used to be read by nothing at all — the NATS coordinator was gated on a
 * build profile, so the published image silently ignored it.
 * <p>
 * Field-injected with defaults equal to the documented ones, so a test can
 * construct it with {@link #defaults()} and override single fields.
 */
@ApplicationScoped
public class ClusterConfig {

    public static final String IN_MEMORY = "in-memory";
    public static final String NATS = "nats";

    /** Degraded-mode decision for an area: run node-locally. */
    public static final String LOCAL = "local";
    /** Degraded-mode decision for an area: refuse with 503. */
    public static final String REJECT = "reject";
    /** Degraded-mode decision for rate limits: a local share of the limit. */
    public static final String LOCAL_SHARE = "local-share";

    @ConfigProperty(name = "eddi.messaging.type", defaultValue = IN_MEMORY)
    String messagingType = IN_MEMORY;

    @ConfigProperty(name = "eddi.cluster.node-id")
    Optional<String> nodeId = Optional.empty();

    @ConfigProperty(name = "eddi.nats.url", defaultValue = "nats://localhost:4222")
    String natsUrl = "nats://localhost:4222";

    static final String DEFAULT_PREFIX = "EDDI";
    static final String DEFAULT_DEAD_LETTER_STREAM = "EDDI_DEAD_LETTERS";
    static final String DEFAULT_INBOX_PREFIX = "_INBOX";

    @ConfigProperty(name = "eddi.nats.prefix", defaultValue = "EDDI")
    String natsPrefix = DEFAULT_PREFIX;

    @ConfigProperty(name = "eddi.nats.replicas", defaultValue = "1")
    int natsReplicas = 1;

    @ConfigProperty(name = "eddi.nats.username")
    Optional<String> natsUsername = Optional.empty();

    @ConfigProperty(name = "eddi.nats.password")
    Optional<String> natsPassword = Optional.empty();

    @ConfigProperty(name = "eddi.nats.token")
    Optional<String> natsToken = Optional.empty();

    @ConfigProperty(name = "eddi.nats.creds-file")
    Optional<String> natsCredsFile = Optional.empty();

    @ConfigProperty(name = "eddi.nats.nkey-seed-file")
    Optional<String> natsNkeySeedFile = Optional.empty();

    @ConfigProperty(name = "eddi.nats.tls.enabled", defaultValue = "false")
    boolean natsTlsEnabled;

    /**
     * Verify that the broker's certificate was issued for the host it was dialled
     * as (HTTPS-style endpoint identification). On by default; turning it off goes
     * back to trusting any certificate the trust store accepts, for any host.
     */
    @ConfigProperty(name = "eddi.nats.tls.verify-hostname", defaultValue = "true")
    boolean natsTlsVerifyHostname = true;

    @ConfigProperty(name = "eddi.nats.tls.truststore-path")
    Optional<String> natsTlsTruststorePath = Optional.empty();

    @ConfigProperty(name = "eddi.nats.tls.truststore-password")
    Optional<String> natsTlsTruststorePassword = Optional.empty();

    @ConfigProperty(name = "eddi.nats.tls.keystore-path")
    Optional<String> natsTlsKeystorePath = Optional.empty();

    @ConfigProperty(name = "eddi.nats.tls.keystore-password")
    Optional<String> natsTlsKeystorePassword = Optional.empty();

    @ConfigProperty(name = "eddi.nats.connection-timeout", defaultValue = "2s")
    Duration natsConnectionTimeout = Duration.ofSeconds(2);

    @ConfigProperty(name = "eddi.nats.request-timeout", defaultValue = "2s")
    Duration natsRequestTimeout = Duration.ofSeconds(2);

    @ConfigProperty(name = "eddi.nats.reconnect-wait", defaultValue = "1s")
    Duration natsReconnectWait = Duration.ofSeconds(1);

    /**
     * How often the client pings the server. A NATS server lost without a TCP reset
     * (a node gone, a half-open connection) is noticed only through missed pings:
     * with the jnats default of two minutes, a node stayed "connected" for minutes
     * while every KV call timed out.
     */
    @ConfigProperty(name = "eddi.nats.ping-interval", defaultValue = "5s")
    Duration natsPingInterval = Duration.ofSeconds(5);

    /** Unanswered pings after which the connection is declared dead. */
    @ConfigProperty(name = "eddi.nats.max-pings-out", defaultValue = "2")
    int natsMaxPingsOut = 2;

    /**
     * Prefix of this node's reply subjects (jnats' {@code _INBOX}). A deployment
     * that shares a NATS account with another sets its own, so that subject
     * permissions can keep each one's replies away from the other.
     */
    @ConfigProperty(name = "eddi.nats.inbox-prefix", defaultValue = DEFAULT_INBOX_PREFIX)
    String natsInboxPrefix = DEFAULT_INBOX_PREFIX;

    @ConfigProperty(name = "eddi.nats.dead-letter-stream-name", defaultValue = "EDDI_DEAD_LETTERS")
    String deadLetterStreamName = "EDDI_DEAD_LETTERS";

    @ConfigProperty(name = "eddi.cluster.lease.ttl", defaultValue = "20s")
    Duration leaseTtl = Duration.ofSeconds(20);

    @ConfigProperty(name = "eddi.cluster.lease.heartbeat-interval", defaultValue = "5s")
    Duration leaseHeartbeatInterval = Duration.ofSeconds(5);

    @ConfigProperty(name = "eddi.cluster.lease.acquire-timeout", defaultValue = "45s")
    Duration leaseAcquireTimeout = Duration.ofSeconds(45);

    @ConfigProperty(name = "eddi.cluster.lease.handoff-grace", defaultValue = "25ms")
    Duration leaseHandoffGrace = Duration.ofMillis(25);

    @ConfigProperty(name = "eddi.cluster.degraded.grace", defaultValue = "5s")
    Duration degradedGrace = Duration.ofSeconds(5);

    @ConfigProperty(name = "eddi.cluster.degraded.turns", defaultValue = LOCAL)
    String degradedTurns = LOCAL;

    @ConfigProperty(name = "eddi.cluster.degraded.nonces", defaultValue = REJECT)
    String degradedNonces = REJECT;

    @ConfigProperty(name = "eddi.cluster.degraded.rate-limits", defaultValue = LOCAL_SHARE)
    String degradedRateLimits = LOCAL_SHARE;

    @ConfigProperty(name = "eddi.cluster.readiness.require-nats", defaultValue = "false")
    boolean readinessRequireNats;

    /**
     * Not ready until the first connection to NATS has succeeded. A node that never
     * connected cannot take a single lease, so every turn it served ran unfenced —
     * and a wrong URL kept it that way forever while every check stayed green.
     * Unlike {@link #readinessRequireNats}, a node that HAS connected stays ready
     * through a later outage.
     */
    @ConfigProperty(name = "eddi.cluster.readiness.require-first-connect", defaultValue = "true")
    boolean readinessRequireFirstConnect = true;

    /**
     * Not ready while the connection fails for a reason retrying cannot heal: an
     * authentication or authorization error, a TLS failure (an untrusted or
     * mismatched certificate) or client options that could not be built. These used
     * to leave the node degraded — ready, and running turns unfenced.
     */
    @ConfigProperty(name = "eddi.cluster.readiness.fail-on-misconfiguration", defaultValue = "true")
    boolean readinessFailOnMisconfiguration = true;

    @ConfigProperty(name = "eddi.cluster.events.max-age", defaultValue = "1h")
    Duration eventsMaxAge = Duration.ofHours(1);

    @ConfigProperty(name = "eddi.cluster.events.outbox-size", defaultValue = "10000")
    int eventsOutboxSize = 10_000;

    @ConfigProperty(name = "eddi.cluster.local-cache-ttl", defaultValue = "60s")
    Duration localCacheTtl = Duration.ofSeconds(60);

    @ConfigProperty(name = "eddi.cluster.model-cache.max-age", defaultValue = "15m")
    Duration modelCacheMaxAge = Duration.ofMinutes(15);

    @ConfigProperty(name = "eddi.cluster.hitl-recovery.interval", defaultValue = "60s")
    Duration hitlRecoveryInterval = Duration.ofSeconds(60);

    @ConfigProperty(name = "eddi.cluster.hitl-recovery.min-age", defaultValue = "3m")
    Duration hitlRecoveryMinAge = Duration.ofMinutes(3);

    @ConfigProperty(name = "eddi.cluster.cost.ttl", defaultValue = "30d")
    Duration costTtl = Duration.ofDays(30);

    @ConfigProperty(name = "eddi.a2a.task-ttl", defaultValue = "24h")
    Duration a2aTaskTtl = Duration.ofHours(24);

    @ConfigProperty(name = "eddi.cluster.presence.interval", defaultValue = "10s")
    Duration presenceInterval = Duration.ofSeconds(10);

    @ConfigProperty(name = "eddi.coordinator.dead-letter.max-age", defaultValue = "7d")
    Duration deadLetterMaxAge = Duration.ofDays(7);

    @ConfigProperty(name = "eddi.coordinator.dead-letter.capture-input", defaultValue = "true")
    boolean deadLetterCaptureInput = true;

    /**
     * Size cap of the dead-letter stream in bytes; {@code <= 0} for none. When it
     * is full, new dead letters are refused (the oldest are kept for the operator)
     * and fall back to the node-local ring, and audit entries to the JSONL file.
     */
    @ConfigProperty(name = "eddi.coordinator.dead-letter.max-bytes", defaultValue = "1073741824")
    long deadLetterMaxBytes = 1L << 30;

    /**
     * Size cap of the {@code <prefix>_ARCHIVES} object store (agent exports shared
     * between nodes) in bytes; {@code <= 0} for none. It shares JetStream storage
     * with the lease and nonce buckets, so an unbounded one let a few large exports
     * fill the store and block lease creates. An export that does not fit stays on
     * the node that made it.
     */
    @ConfigProperty(name = "eddi.cluster.archives.max-bytes", defaultValue = "1073741824")
    long archivesMaxBytes = 1L << 30;

    /** Every value as documented, for tests and for code paths without CDI. */
    public static ClusterConfig defaults() {
        return new ClusterConfig();
    }

    /** The normalized messaging type; validated by {@link #validate()}. */
    public String messagingType() {
        return messagingType == null ? IN_MEMORY : messagingType.trim().toLowerCase(Locale.ROOT);
    }

    /** True when the replicas coordinate through NATS JetStream. */
    public boolean isNats() {
        return NATS.equals(messagingType());
    }

    /**
     * Fails boot on a messaging type this build does not know — a typo used to
     * leave a deployment silently single-node — and, in cluster mode, on settings
     * that cannot work: inconsistent lease timings, which would make every long
     * turn lose its lease, durations that are zero or negative, two authentication
     * methods at once (only one of them would be used) and {@code opentls://}
     * servers, which trust any certificate.
     */
    public void validate() {
        String type = messagingType();
        if (!IN_MEMORY.equals(type) && !NATS.equals(type)) {
            throw new IllegalStateException("eddi.messaging.type must be 'in-memory' or 'nats', got '" + messagingType + "'");
        }
        if (!isNats()) {
            return;
        }
        requirePositive("eddi.cluster.lease.ttl", leaseTtl);
        requirePositive("eddi.cluster.lease.heartbeat-interval", leaseHeartbeatInterval);
        requirePositive("eddi.cluster.lease.acquire-timeout", leaseAcquireTimeout);
        requirePositive("eddi.nats.connection-timeout", natsConnectionTimeout);
        requirePositive("eddi.nats.request-timeout", natsRequestTimeout);
        requirePositive("eddi.nats.reconnect-wait", natsReconnectWait);
        requirePositive("eddi.nats.ping-interval", natsPingInterval);
        requirePositive("eddi.cluster.presence.interval", presenceInterval);
        requireNotNegative("eddi.cluster.lease.handoff-grace", leaseHandoffGrace);
        requireNotNegative("eddi.cluster.degraded.grace", degradedGrace);
        if (leaseHeartbeatInterval.compareTo(leaseTtl.dividedBy(2)) > 0) {
            throw new IllegalStateException("eddi.cluster.lease.heartbeat-interval (" + leaseHeartbeatInterval
                    + ") must be at most half of eddi.cluster.lease.ttl (" + leaseTtl + ")");
        }
        if (natsReplicas < 1 || natsReplicas > 5) {
            throw new IllegalStateException("eddi.nats.replicas must be between 1 and 5, got " + natsReplicas);
        }
        if (natsMaxPingsOut < 1) {
            throw new IllegalStateException("eddi.nats.max-pings-out must be at least 1, got " + natsMaxPingsOut);
        }
        requireOneOf("eddi.cluster.degraded.turns", degradedTurns, LOCAL, REJECT);
        requireOneOf("eddi.cluster.degraded.nonces", degradedNonces, LOCAL, REJECT);
        requireOneOf("eddi.cluster.degraded.rate-limits", degradedRateLimits, LOCAL_SHARE, REJECT);
        if (!natsPrefix.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalStateException("eddi.nats.prefix may only contain letters, digits, '_' and '-', got '" + natsPrefix + "'");
        }
        if (natsInboxPrefix == null || !natsInboxPrefix.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalStateException("eddi.nats.inbox-prefix may only contain letters, digits, '_' and '-' (one subject token), got '"
                    + natsInboxPrefix + "'");
        }
        for (String server : natsServers()) {
            if (server.toLowerCase(Locale.ROOT).startsWith("opentls://")) {
                throw new IllegalStateException("eddi.nats.url lists " + redactUserInfo(server)
                        + ": opentls:// accepts ANY server certificate, so anyone on the path can impersonate NATS and receive "
                        + "this node's credentials. Use tls:// (with eddi.nats.tls.truststore-path for a private CA).");
            }
        }
        List<String> authMethods = new ArrayList<>();
        natsUsername.filter(v -> !v.isBlank()).ifPresent(v -> authMethods.add("eddi.nats.username"));
        natsToken.filter(v -> !v.isBlank()).ifPresent(v -> authMethods.add("eddi.nats.token"));
        natsCredsFile.filter(v -> !v.isBlank()).ifPresent(v -> authMethods.add("eddi.nats.creds-file"));
        natsNkeySeedFile.filter(v -> !v.isBlank()).ifPresent(v -> authMethods.add("eddi.nats.nkey-seed-file"));
        if (authMethods.size() > 1) {
            throw new IllegalStateException("Only one NATS authentication method may be configured, but " + authMethods
                    + " are all set: the client would use one of them and silently ignore the rest");
        }
    }

    /**
     * Settings that work but are probably not what the operator meant, logged as
     * warnings at boot. Empty in in-memory mode.
     */
    public List<String> validationWarnings() {
        List<String> warnings = new ArrayList<>();
        if (!isNats()) {
            return warnings;
        }
        if (leaseAcquireTimeout.compareTo(leaseTtl) < 0) {
            warnings.add("eddi.cluster.lease.acquire-timeout (" + leaseAcquireTimeout + ") is shorter than eddi.cluster.lease.ttl ("
                    + leaseTtl + "): a turn that waits for the lease of a crashed node gives up (409) before that lease expires");
        }
        if (natsPassword.isPresent() && natsUsername.isEmpty()) {
            warnings.add("eddi.nats.password is set without eddi.nats.username: it is not used");
        }
        if (natsTlsRequired() && !natsTlsVerifyHostname) {
            warnings.add("eddi.nats.tls.verify-hostname=false: any certificate the trust store accepts authenticates ANY host as "
                    + "the NATS server, so a man in the middle holding such a certificate receives this node's credentials");
        }
        if ((natsUsername.isPresent() || natsToken.isPresent()) && !natsTlsRequired()) {
            warnings.add("NATS credentials are configured but no server is tls:// and eddi.nats.tls.enabled=false: they cross the "
                    + "network in clear text unless the transport is encrypted underneath (a service mesh)");
        }
        return warnings;
    }

    /**
     * Whether the client must speak TLS: {@code eddi.nats.tls.enabled}, or any
     * {@code tls://} or {@code wss://} server. Either way EDDI builds its own
     * hostname-verifying context — jnats' fallback for a {@code tls://} URL was the
     * JVM default context, which accepts a certificate issued for any host.
     */
    public boolean natsTlsRequired() {
        if (natsTlsEnabled) {
            return true;
        }
        for (String server : natsServers()) {
            String s = server.toLowerCase(Locale.ROOT);
            if (s.startsWith("tls://") || s.startsWith("wss://")) {
                return true;
            }
        }
        return false;
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(name + " must be a positive duration, got " + value);
        }
    }

    private static void requireNotNegative(String name, Duration value) {
        if (value == null || value.isNegative()) {
            throw new IllegalStateException(name + " must not be negative, got " + value);
        }
    }

    private static void requireOneOf(String name, String value, String... allowed) {
        if (!Arrays.asList(allowed).contains(value)) {
            throw new IllegalStateException(name + " must be one of " + Arrays.toString(allowed) + ", got '" + value + "'");
        }
    }

    /** The configured NATS servers; {@code eddi.nats.url} may be a list. */
    public List<String> natsServers() {
        return Arrays.stream(natsUrl.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public Optional<String> nodeId() {
        return nodeId;
    }

    public String natsPrefix() {
        return natsPrefix;
    }

    public int natsReplicas() {
        return natsReplicas;
    }

    public Optional<String> natsUsername() {
        return natsUsername;
    }

    public Optional<String> natsPassword() {
        return natsPassword;
    }

    public Optional<String> natsToken() {
        return natsToken;
    }

    public Optional<String> natsCredsFile() {
        return natsCredsFile;
    }

    public Optional<String> natsNkeySeedFile() {
        return natsNkeySeedFile;
    }

    public boolean natsTlsEnabled() {
        return natsTlsEnabled;
    }

    public boolean natsTlsVerifyHostname() {
        return natsTlsVerifyHostname;
    }

    public Optional<String> natsTlsTruststorePath() {
        return natsTlsTruststorePath;
    }

    public Optional<String> natsTlsTruststorePassword() {
        return natsTlsTruststorePassword;
    }

    public Optional<String> natsTlsKeystorePath() {
        return natsTlsKeystorePath;
    }

    public Optional<String> natsTlsKeystorePassword() {
        return natsTlsKeystorePassword;
    }

    public Duration natsConnectionTimeout() {
        return natsConnectionTimeout;
    }

    public Duration natsRequestTimeout() {
        return natsRequestTimeout;
    }

    public Duration natsReconnectWait() {
        return natsReconnectWait;
    }

    public Duration natsPingInterval() {
        return natsPingInterval;
    }

    public int natsMaxPingsOut() {
        return natsMaxPingsOut;
    }

    public String natsInboxPrefix() {
        return natsInboxPrefix;
    }

    /**
     * The dead-letter stream. The default name follows {@code eddi.nats.prefix},
     * like every other stream and bucket: two deployments with different prefixes
     * on one NATS cluster otherwise shared one stream, and each one's provisioning
     * rewrote the other's subjects. An explicitly configured name is used as given.
     */
    public String deadLetterStreamName() {
        if (DEFAULT_DEAD_LETTER_STREAM.equals(deadLetterStreamName) && !DEFAULT_PREFIX.equals(natsPrefix)) {
            return natsPrefix + "_DEAD_LETTERS";
        }
        return deadLetterStreamName;
    }

    /**
     * The configured NATS servers with any {@code user:password@} part removed:
     * safe to log.
     */
    public List<String> natsServersForLog() {
        return natsServers().stream().map(ClusterConfig::redactUserInfo).toList();
    }

    /**
     * {@code nats://user:secret@host:4222} becomes {@code nats://***@host:4222}.
     */
    public static String redactUserInfo(String url) {
        if (url == null) {
            return null;
        }
        // A literal space, deliberately not the regex \s: "\s" in a Java string is the
        // space escape, and widening it to all whitespace would let a tab or newline
        // inside the userinfo end the match and leave the credentials in the log.
        return url.replaceAll("(?<=//)[^/@ ]*@", "***@");
    }

    public Duration leaseTtl() {
        return leaseTtl;
    }

    public Duration leaseHeartbeatInterval() {
        return leaseHeartbeatInterval;
    }

    public Duration leaseAcquireTimeout() {
        return leaseAcquireTimeout;
    }

    public Duration leaseHandoffGrace() {
        return leaseHandoffGrace;
    }

    public Duration degradedGrace() {
        return degradedGrace;
    }

    public String degradedTurns() {
        return degradedTurns;
    }

    public String degradedNonces() {
        return degradedNonces;
    }

    public String degradedRateLimits() {
        return degradedRateLimits;
    }

    public boolean readinessRequireNats() {
        return readinessRequireNats;
    }

    public boolean readinessRequireFirstConnect() {
        return readinessRequireFirstConnect;
    }

    public boolean readinessFailOnMisconfiguration() {
        return readinessFailOnMisconfiguration;
    }

    public Duration eventsMaxAge() {
        return eventsMaxAge;
    }

    public int eventsOutboxSize() {
        return eventsOutboxSize;
    }

    public Duration localCacheTtl() {
        return localCacheTtl;
    }

    public Duration modelCacheMaxAge() {
        return modelCacheMaxAge;
    }

    public Duration hitlRecoveryInterval() {
        return hitlRecoveryInterval;
    }

    public Duration hitlRecoveryMinAge() {
        return hitlRecoveryMinAge;
    }

    public Duration costTtl() {
        return costTtl;
    }

    public Duration a2aTaskTtl() {
        return a2aTaskTtl;
    }

    public Duration presenceInterval() {
        return presenceInterval;
    }

    public Duration deadLetterMaxAge() {
        return deadLetterMaxAge;
    }

    public boolean deadLetterCaptureInput() {
        return deadLetterCaptureInput;
    }

    public long deadLetterMaxBytes() {
        return deadLetterMaxBytes;
    }

    public long archivesMaxBytes() {
        return archivesMaxBytes;
    }

    // ---- mutators for tests and the in-JVM cluster ITs ----

    public ClusterConfig withMessagingType(String type) {
        this.messagingType = type;
        return this;
    }

    public ClusterConfig withLeaseTimings(Duration ttl, Duration heartbeat, Duration acquireTimeout) {
        this.leaseTtl = ttl;
        this.leaseHeartbeatInterval = heartbeat;
        this.leaseAcquireTimeout = acquireTimeout;
        return this;
    }

    public ClusterConfig withNatsUrl(String url) {
        this.natsUrl = url;
        return this;
    }

    public ClusterConfig withNatsPrefix(String prefix) {
        this.natsPrefix = prefix;
        return this;
    }

    public ClusterConfig withNodeId(String id) {
        this.nodeId = Optional.ofNullable(id);
        return this;
    }

    public ClusterConfig withDegradedGrace(Duration grace) {
        this.degradedGrace = grace;
        return this;
    }

    public ClusterConfig withDegradedTurns(String decision) {
        this.degradedTurns = decision;
        return this;
    }

    public ClusterConfig withEventsOutboxSize(int size) {
        this.eventsOutboxSize = size;
        return this;
    }

    public ClusterConfig withRequestTimeout(Duration timeout) {
        this.natsRequestTimeout = timeout;
        return this;
    }

    public ClusterConfig withPresenceInterval(Duration interval) {
        this.presenceInterval = interval;
        return this;
    }

    public ClusterConfig withReplicas(int replicas) {
        this.natsReplicas = replicas;
        return this;
    }

    public ClusterConfig withCredentials(String username, String password) {
        this.natsUsername = Optional.ofNullable(username);
        this.natsPassword = Optional.ofNullable(password);
        return this;
    }

    public ClusterConfig withToken(String token) {
        this.natsToken = Optional.ofNullable(token);
        return this;
    }

    public ClusterConfig withTls(boolean enabled, boolean verifyHostname, String truststorePath, String truststorePassword) {
        this.natsTlsEnabled = enabled;
        this.natsTlsVerifyHostname = verifyHostname;
        this.natsTlsTruststorePath = Optional.ofNullable(truststorePath);
        this.natsTlsTruststorePassword = Optional.ofNullable(truststorePassword);
        return this;
    }

    public ClusterConfig withPing(Duration interval, int maxPingsOut) {
        this.natsPingInterval = interval;
        this.natsMaxPingsOut = maxPingsOut;
        return this;
    }

    public ClusterConfig withInboxPrefix(String prefix) {
        this.natsInboxPrefix = prefix;
        return this;
    }

    public ClusterConfig withReadiness(boolean requireFirstConnect, boolean failOnMisconfiguration) {
        this.readinessRequireFirstConnect = requireFirstConnect;
        this.readinessFailOnMisconfiguration = failOnMisconfiguration;
        return this;
    }
}
