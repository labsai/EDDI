/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.outbound;

import org.eclipse.microprofile.config.Config;

import java.time.Duration;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The deployment's outbound HTTP configuration, read once at startup.
 * <p>
 * Every key is spelled out literally below, so
 * {@code ConfigurationReferenceCoverageTest} can hold
 * {@code docs/configuration-reference.md} to it. Only the {@code default}
 * profile has timeout, protocol and TLS keys yet; each other profile gets its
 * own when its callers move onto {@link OutboundHttp}, and until then shares
 * the {@code default} settings (while keeping a pool of its own). The engine
 * and the pool limit are per profile already.
 *
 * @param engine
 *            the deployment-wide default engine name, {@value #JDK_ENGINE} or
 *            {@value #VERTX_ENGINE}; a profile may override it
 * @param profiles
 *            settings per profile
 * @param proxy
 *            the forward proxy; {@link ProxySettings#NONE} for none
 */
public record OutboundSettings(String engine, Map<Profile, ProfileSettings> profiles, ProxySettings proxy) {

    /** The {@code java.net.http} engine, the default. */
    public static final String JDK_ENGINE = "jdk";

    /**
     * The Vert.x core-client engine: a pre-send connected-peer check and a per-host
     * pool limit. The default for the {@code apicalls} profile only.
     */
    public static final String VERTX_ENGINE = "vertx";

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** Pooled connections per host on a Vert.x profile that sets no limit. */
    static final int DEFAULT_VERTX_MAX_CONNECTIONS_PER_HOST = 50;

    public OutboundSettings {
        profiles = Map.copyOf(profiles);
        for (Profile profile : Profile.values()) {
            if (!profiles.containsKey(profile)) {
                throw new IllegalArgumentException("no settings for profile " + profile);
            }
        }
    }

    public ProfileSettings profile(Profile profile) {
        return profiles.get(profile);
    }

    /** HTTP version negotiation. */
    public enum Protocol {
        /** HTTP/2 via ALPN where the server offers it, else HTTP/1.1. */
        AUTO, HTTP1, HTTP2;

        static Protocol parse(String value) {
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "auto" -> AUTO;
                case "http1" -> HTTP1;
                case "http2" -> HTTP2;
                default -> throw new IllegalStateException(
                        "eddi.http.outbound.*.protocol must be auto, http1 or http2, was '" + value + "'");
            };
        }
    }

    /**
     * @param connectTimeout
     *            TCP + TLS connect timeout, per hop
     * @param totalTimeout
     *            the least wall-clock budget for reaching the final response, every
     *            redirect hop included; {@code null} means three connect timeouts.
     *            The final body has its own read budget
     * @param tlsConfigurationName
     *            a named {@code quarkus.tls.<name>} configuration, or {@code null}
     *            for the JVM's default trust
     * @param engine
     *            {@value #JDK_ENGINE} or {@value #VERTX_ENGINE}
     * @param maxConnectionsPerHost
     *            the Vert.x pool limit per host and port; {@code null} on a JDK
     *            profile, which has no such limit
     */
    public record ProfileSettings(Duration connectTimeout, Duration totalTimeout, Protocol protocol,
            String tlsConfigurationName, String engine, Integer maxConnectionsPerHost) {

        /** On the JDK engine. */
        public ProfileSettings(Duration connectTimeout, Duration totalTimeout, Protocol protocol, String tlsConfigurationName) {
            this(connectTimeout, totalTimeout, protocol, tlsConfigurationName, JDK_ENGINE, null);
        }

        /** These settings on {@code engine}, with {@code maxConnectionsPerHost}. */
        public ProfileSettings onEngine(String engine, Integer maxConnectionsPerHost) {
            return new ProfileSettings(connectTimeout, totalTimeout, protocol, tlsConfigurationName, engine, maxConnectionsPerHost);
        }

        /** Whether this profile runs on the Vert.x engine. */
        public boolean vertx() {
            return VERTX_ENGINE.equals(engine);
        }

        /** The budget floor: {@link #totalTimeout}, or three connect timeouts. */
        public Duration budgetFloor() {
            return totalTimeout != null ? totalTimeout : connectTimeout.multipliedBy(3);
        }
    }

    /**
     * A forward proxy for every outbound request, except to hosts matching
     * {@code nonProxyHosts}.
     *
     * @param nonProxyHosts
     *            host patterns, {@code *} as a wildcard at either end, as in the
     *            JDK's {@code http.nonProxyHosts}
     * @param credentials
     *            {@code user:password}, possibly a {@code ${vault:...}} reference,
     *            or {@code null}
     */
    public record ProxySettings(String host, int port, List<String> nonProxyHosts, String credentials) {

        public static final ProxySettings NONE = new ProxySettings(null, 0, List.of(), null);

        public ProxySettings {
            nonProxyHosts = List.copyOf(nonProxyHosts);
        }

        public boolean enabled() {
            return host != null;
        }

        @Override
        public String toString() {
            // Never print the credentials.
            return enabled() ? "ProxySettings[" + host + ":" + port + "]" : "ProxySettings[none]";
        }
    }

    /** Every profile with the same settings, no proxy. For tests and tools. */
    public static OutboundSettings defaults(Duration connectTimeout) {
        return uniform(new ProfileSettings(connectTimeout, null, Protocol.AUTO, null), ProxySettings.NONE);
    }

    private static OutboundSettings uniform(ProfileSettings settings, ProxySettings proxy) {
        Map<Profile, ProfileSettings> all = new EnumMap<>(Profile.class);
        for (Profile profile : Profile.values()) {
            all.put(profile, settings);
        }
        return new OutboundSettings(JDK_ENGINE, all, proxy);
    }

    /**
     * Reads the settings from {@code config}.
     * <p>
     * The {@code default} profile's connect timeout falls back to
     * {@code httpClient.connectTimeoutInMillis}, the property the retired
     * {@code SafeHttpClient} read, so a deployment that tuned it keeps its value.
     *
     * @throws IllegalStateException
     *             on a value that cannot be honoured — fail at startup, not on the
     *             first request
     */
    public static OutboundSettings fromConfig(Config config) {
        Optional<String> globalEngine = engineName("eddi.http.outbound.engine",
                config.getOptionalValue("eddi.http.outbound.engine", String.class));
        String engine = globalEngine.orElse(JDK_ENGINE);

        Duration legacyConnect = config.getOptionalValue("httpClient.connectTimeoutInMillis", Integer.class)
                .map(Duration::ofMillis)
                .orElse(DEFAULT_CONNECT_TIMEOUT);
        Duration connect = positive("eddi.http.outbound.default.connect-timeout",
                config.getOptionalValue("eddi.http.outbound.default.connect-timeout", Duration.class).orElse(legacyConnect));
        Duration total = config.getOptionalValue("eddi.http.outbound.default.total-timeout", Duration.class)
                .map(value -> positive("eddi.http.outbound.default.total-timeout", value))
                .orElse(null);
        Protocol protocol = Protocol.parse(
                config.getOptionalValue("eddi.http.outbound.default.protocol", String.class).orElse("auto"));
        String tlsName = blankToNull(
                config.getOptionalValue("eddi.http.outbound.default.tls.configuration-name", String.class));

        ProfileSettings defaults = new ProfileSettings(connect, total, protocol, tlsName);

        ProxySettings proxy = ProxySettings.NONE;
        String proxyHost = blankToNull(config.getOptionalValue("eddi.http.outbound.proxy.host", String.class));
        if (proxyHost != null) {
            int port = config.getOptionalValue("eddi.http.outbound.proxy.port", Integer.class)
                    .orElseThrow(() -> new IllegalStateException(
                            "eddi.http.outbound.proxy.host is set but eddi.http.outbound.proxy.port is not"));
            if (port < 1 || port > 65535) {
                throw new IllegalStateException("eddi.http.outbound.proxy.port must be 1-65535, was " + port);
            }
            proxy = new ProxySettings(proxyHost, port,
                    list(config.getOptionalValue("eddi.http.outbound.proxy.non-proxy-hosts", String.class), "|"),
                    blankToNull(config.getOptionalValue("eddi.http.outbound.proxy.credentials", String.class)));
        }
        // Spelled out per profile so ConfigurationReferenceCoverageTest sees each key.
        Map<Profile, Optional<String>> engines = new EnumMap<>(Profile.class);
        engines.put(Profile.DEFAULT, config.getOptionalValue("eddi.http.outbound.default.engine", String.class));
        engines.put(Profile.LLM_PROVIDER, config.getOptionalValue("eddi.http.outbound.llm-provider.engine", String.class));
        engines.put(Profile.APICALLS, config.getOptionalValue("eddi.http.outbound.apicalls.engine", String.class));
        engines.put(Profile.INTERNAL_PEER, config.getOptionalValue("eddi.http.outbound.internal-peer.engine", String.class));
        Map<Profile, Optional<Integer>> pools = new EnumMap<>(Profile.class);
        pools.put(Profile.DEFAULT, config.getOptionalValue("eddi.http.outbound.default.max-connections-per-host", Integer.class));
        pools.put(Profile.LLM_PROVIDER,
                config.getOptionalValue("eddi.http.outbound.llm-provider.max-connections-per-host", Integer.class));
        pools.put(Profile.APICALLS, config.getOptionalValue("eddi.http.outbound.apicalls.max-connections-per-host", Integer.class));
        pools.put(Profile.INTERNAL_PEER,
                config.getOptionalValue("eddi.http.outbound.internal-peer.max-connections-per-host", Integer.class));
        // httpcalls' own pool size (the Vert.x client behind them today) is the
        // apicalls
        // fallback, so a deployment that tuned it keeps its value once httpcalls move
        // here.
        Optional<Integer> legacyApicallsPool = config.getOptionalValue("httpClient.maxConnectionPerRoute", Integer.class);

        Map<Profile, ProfileSettings> all = new EnumMap<>(Profile.class);
        for (Profile profile : Profile.values()) {
            String prefix = "eddi.http.outbound." + profile.key();
            // apicalls is where a pre-send peer check matters most (editor-written URLs,
            // SSRF protection off by default), so it runs on Vert.x unless told otherwise.
            String fallback = profile == Profile.APICALLS && globalEngine.isEmpty() ? VERTX_ENGINE : engine;
            String profileEngine = engineName(prefix + ".engine", engines.get(profile)).orElse(fallback);
            Optional<Integer> explicitPool = pools.get(profile);
            Integer pool;
            if (JDK_ENGINE.equals(profileEngine)) {
                if (explicitPool.isPresent()) {
                    throw new IllegalStateException(prefix + ".max-connections-per-host is set, but the " + profile.key()
                            + " profile runs on the jdk engine, which has no per-host pool limit; set " + prefix
                            + ".engine=vertx or remove the limit");
                }
                pool = null;
            } else {
                pool = explicitPool
                        .or(() -> profile == Profile.APICALLS ? legacyApicallsPool : Optional.empty())
                        .orElse(DEFAULT_VERTX_MAX_CONNECTIONS_PER_HOST);
                if (pool < 1) {
                    throw new IllegalStateException(prefix + ".max-connections-per-host must be at least 1, was " + pool);
                }
            }
            all.put(profile, defaults.onEngine(profileEngine, pool));
        }
        return new OutboundSettings(engine, all, proxy);
    }

    private static Optional<String> engineName(String key, Optional<String> value) {
        return value.map(v -> v.trim().toLowerCase(Locale.ROOT)).filter(v -> !v.isEmpty()).map(v -> {
            if (!JDK_ENGINE.equals(v) && !VERTX_ENGINE.equals(v)) {
                throw new IllegalStateException(key + " must be '" + JDK_ENGINE + "' or '" + VERTX_ENGINE + "', was '" + v + "'");
            }
            return v;
        });
    }

    /** Whether any profile runs on the Vert.x engine. */
    public boolean usesVertx() {
        return profiles.values().stream().anyMatch(ProfileSettings::vertx);
    }

    /** These settings with every profile on {@code engine}. For tests. */
    OutboundSettings onEngine(String engine, Integer maxConnectionsPerHost) {
        Map<Profile, ProfileSettings> all = new EnumMap<>(Profile.class);
        profiles.forEach((profile, settings) -> all.put(profile, settings.onEngine(engine, maxConnectionsPerHost)));
        return new OutboundSettings(engine, all, proxy);
    }

    private static Duration positive(String key, Duration value) {
        if (value.isNegative() || value.isZero()) {
            throw new IllegalStateException(key + " must be positive, was " + value);
        }
        return value;
    }

    private static String blankToNull(Optional<String> value) {
        return value.map(String::trim).filter(v -> !v.isEmpty()).orElse(null);
    }

    private static List<String> list(Optional<String> value, String separator) {
        return value.map(v -> Arrays.stream(v.split(Pattern.quote(separator)))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList())
                .orElse(List.of());
    }
}
