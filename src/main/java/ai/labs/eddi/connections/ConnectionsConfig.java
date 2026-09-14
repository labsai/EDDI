/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections;

import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.connections.model.ConnectionReference;
import ai.labs.eddi.connections.settings.ConnectionSettings;
import ai.labs.eddi.connections.settings.ConnectionSettingsRules;
import ai.labs.eddi.connections.settings.IConnectionSettingsStore;
import ai.labs.eddi.connections.settings.IConnectionSettingsStore.StoredConnectionSettings;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongSupplier;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Deployment-level settings for the connections feature, changeable at runtime.
 * <p>
 * <b>Where a value comes from.</b> Each of the four settings resolves in this
 * order:
 * <ol>
 * <li><b>Pinned</b> — its property ({@link #ENABLED}, {@link #PUBLIC_BASE_URL},
 * {@link #CREDENTIAL_ENDPOINT_ALLOWLIST},
 * {@link #ALLOW_PLAINTEXT_REMOTE_ORIGINS}) or the matching environment variable
 * is set. Authoritative, and {@code PUT /connectionstore/settings} refuses to
 * change it.</li>
 * <li><b>Stored</b> — the settings document an administrator wrote.</li>
 * <li><b>Default</b> — disabled, no base URL, empty allowlist, plaintext remote
 * origins refused. Every default fails closed.</li>
 * </ol>
 * <p>
 * <b>Why a property still wins.</b> These were properties only, justified as
 * "an operator approves where a client secret may go, not an administrator".
 * That separation does not exist anywhere else: {@code eddi-admin} writes the
 * vault, and an httpcall can already send any {@code ${vault:…}} value to any
 * host. So keeping the values out of administrators' hands bought a restart and
 * no protection. A deployment that genuinely does separate the two roles still
 * can — by pinning — without every other deployment paying for it.
 * <p>
 * <b>No seeding.</b> A pinned value is never copied into the store. Copying
 * would make removing the property later look like it had been ignored, since
 * the copy would silently take over.
 * <p>
 * <b>Freshness.</b> Stored values are cached for {@link #STORED_SETTINGS_TTL}.
 * A write on this instance is adopted immediately; every other instance sees it
 * once its cache expires. The settings are read on hot paths (every resolution
 * asks about plaintext origins), so a store read per call is not an option, and
 * a short TTL bounds cross-replica staleness without a cluster event bus.
 * <p>
 * <b>When the store cannot be read</b>, the values last read are kept; before
 * the first successful read, only pinned values and defaults apply — so the
 * feature is off unless {@link #ENABLED} is pinned. Failing closed on a store
 * outage costs nothing extra: connections cannot be read then either.
 */
@ApplicationScoped
public class ConnectionsConfig {

    private static final Logger LOGGER = Logger.getLogger(ConnectionsConfig.class);

    /** Path the provider redirects back to. */
    public static final String CALLBACK_PATH = "/connections/callback";

    /** Where an administrator changes these settings. */
    public static final String SETTINGS_PATH = "/connectionstore/settings";

    /** Pins {@link #isEnabled()}. */
    public static final String ENABLED = "eddi.connections.enabled";

    /** Pins {@link #getPublicBaseUrl()}. */
    public static final String PUBLIC_BASE_URL = "eddi.connections.public-base-url";

    /** Pins {@link #credentialEndpointOrigins()}. */
    public static final String CREDENTIAL_ENDPOINT_ALLOWLIST = "eddi.connections.credential-endpoint-allowlist";

    /** Pins {@link #isAllowPlaintextRemoteOrigins()}. */
    public static final String ALLOW_PLAINTEXT_REMOTE_ORIGINS = "eddi.connections.allow-plaintext-remote-origins";

    /** How long a stored value may be served before it is read again. */
    public static final Duration STORED_SETTINGS_TTL = Duration.ofSeconds(5);

    /** The tenant whose settings apply, until multi-tenancy supplies one. */
    public static final String TENANT = ConnectionReference.DEFAULT_TENANT;

    private static final long TTL_NANOS = STORED_SETTINGS_TTL.toNanos();

    private static final Snapshot NOTHING_STORED = new Snapshot(null, Set.of(), 0L);

    /** Non-null fields are pinned. Never mutated after construction. */
    private final ConnectionSettings pinned;

    /** Canonical pinned origins, or null when the allowlist is not pinned. */
    private final Set<String> pinnedOrigins;

    /** Null for a fixed configuration, which has nothing stored. */
    private final IConnectionSettingsStore store;

    private final LongSupplier nanoClock;

    private volatile Snapshot snapshot;

    /**
     * One read of the store.
     *
     * @param stored
     *            null when nothing was ever stored
     * @param origins
     *            the stored allowlist, canonicalised once per read rather than per
     *            request
     */
    private record Snapshot(StoredConnectionSettings stored, Set<String> origins, long loadedAtNanos) {
    }

    @Inject
    public ConnectionsConfig(@ConfigProperty(name = ENABLED) Optional<Boolean> enabled,
            @ConfigProperty(name = PUBLIC_BASE_URL) Optional<String> publicBaseUrl,
            @ConfigProperty(name = CREDENTIAL_ENDPOINT_ALLOWLIST) Optional<String> credentialEndpointAllowlist,
            @ConfigProperty(name = ALLOW_PLAINTEXT_REMOTE_ORIGINS) Optional<Boolean> allowPlaintextRemoteOrigins,
            IConnectionSettingsStore store) {
        this(new ConnectionSettings(enabled.orElse(null), publicBaseUrl.map(ConnectionsConfig::blankToNull).orElse(null),
                credentialEndpointAllowlist.map(ConnectionsConfig::splitOrigins).orElse(null), allowPlaintextRemoteOrigins.orElse(null)),
                store, System::nanoTime);
    }

    /**
     * Fixed settings with nothing stored: {@code enabled} and a non-blank
     * {@code publicBaseUrl} are pinned. Test seam.
     */
    public ConnectionsConfig(boolean enabled, Optional<String> publicBaseUrl) {
        this(enabled, publicBaseUrl.orElse(null), false);
    }

    /** Test seam; see {@link #ConnectionsConfig(boolean, Optional)}. */
    ConnectionsConfig(boolean enabled, String publicBaseUrl) {
        this(enabled, publicBaseUrl, false);
    }

    /** Test seam; see {@link #ConnectionsConfig(boolean, Optional)}. */
    ConnectionsConfig(boolean enabled, String publicBaseUrl, boolean allowPlaintextRemoteOrigins) {
        this(new ConnectionSettings(enabled, blankToNull(publicBaseUrl), null, allowPlaintextRemoteOrigins), null, System::nanoTime);
    }

    /**
     * The general seam, and what the CDI constructor delegates to. Public for tests
     * in other packages.
     *
     * @param pinned
     *            a non-null field is pinned
     * @param store
     *            null for fixed settings with nothing stored
     * @throws IllegalArgumentException
     *             when a pinned allowlist entry is not a bare origin — at first use
     *             of the bean, which the startup guard makes boot
     */
    public ConnectionsConfig(ConnectionSettings pinned, IConnectionSettingsStore store, LongSupplier nanoClock) {
        this.pinned = copyOf(pinned);
        this.pinnedOrigins = this.pinned.getCredentialEndpointAllowlist() == null
                ? null
                : Collections.unmodifiableSet(new LinkedHashSet<>(
                        ConnectionSettingsRules.canonicalOrigins(this.pinned.getCredentialEndpointAllowlist(), CREDENTIAL_ENDPOINT_ALLOWLIST)));
        this.store = store;
        this.nanoClock = nanoClock;
    }

    // --- Effective values ---------------------------------------------------

    public boolean isEnabled() {
        return Boolean.TRUE.equals(effective(ConnectionSettings::getEnabled));
    }

    /**
     * Whether a connection may send its credential over plaintext http to a host
     * other than loopback.
     * <p>
     * Default {@code false}: such a credential crosses the network unencrypted, and
     * a per-connection allowlist entry — writable by one administrator request —
     * should not be the only thing standing between a key and anyone on the path.
     * When false the origin is refused at the write boundary, refused per request
     * by {@code ConnectionResolver}, and reported at ERROR by the startup guard;
     * when true it is accepted with a WARN. Loopback ({@code localhost},
     * {@code 127.0.0.1}, {@code [::1]}) is allowed either way.
     */
    public boolean isAllowPlaintextRemoteOrigins() {
        return Boolean.TRUE.equals(effective(ConnectionSettings::getAllowPlaintextRemoteOrigins));
    }

    /** The effective base URL, trimmed; empty when none is set. */
    public String getPublicBaseUrl() {
        String value = effective(ConnectionSettings::getPublicBaseUrl);
        return value == null ? "" : value.trim();
    }

    /** The effective credential endpoint allowlist, canonical and ordered. */
    public Set<String> credentialEndpointOrigins() {
        return pinnedOrigins != null ? pinnedOrigins : snapshot().origins();
    }

    /**
     * Why the effective base URL cannot serve as a redirect origin, or empty when
     * it can.
     * <p>
     * Asked at the moment of use rather than only at boot, because a stored value
     * can now change after boot — and can reach the store by a direct database
     * write that never met the write-boundary check.
     */
    public Optional<String> publicBaseUrlProblem() {
        try {
            ConnectionSettingsRules.requireBarePublicOrigin(getPublicBaseUrl(), describe("publicBaseUrl", PUBLIC_BASE_URL),
                    ConnectionSettingsRules.isDevOrTest());
            return Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.of(e.getMessage());
        }
    }

    // --- Provenance, for the settings resource -------------------------------

    /** A copy of the pinned values; a null field is not pinned. */
    public ConnectionSettings pinnedSettings() {
        return copyOf(pinned);
    }

    /** What is stored, as last read (at most {@link #STORED_SETTINGS_TTL} old). */
    public Optional<StoredConnectionSettings> storedSettings() {
        return Optional.ofNullable(snapshot().stored());
    }

    /** Re-reads the store now, keeping the previous values if it fails. */
    public void refresh() {
        if (store == null) {
            return;
        }
        synchronized (this) {
            snapshot = load(snapshot, nanoClock.getAsLong());
        }
    }

    /**
     * Serves a document this instance has just written, without reading it back.
     * <p>
     * Reading it back would work too — until the read failed, when the settings
     * page would show the old values right after a successful save.
     */
    public void adopt(StoredConnectionSettings written) {
        if (store == null) {
            return;
        }
        synchronized (this) {
            snapshot = new Snapshot(written, storedOrigins(written), nanoClock.getAsLong());
        }
    }

    /**
     * Names a setting in an error message by both of its handles, because the
     * reader could be the administrator who can fix it through the API or the
     * operator who can pin it.
     */
    public static String describe(String field, String property) {
        return field + " (" + SETTINGS_PATH + ", or the " + property + " property)";
    }

    // --- Redirects -----------------------------------------------------------

    /**
     * The {@code redirect_uri} registered at the provider.
     * <p>
     * Built from configuration rather than from the inbound request. A
     * request-derived value can be steered with a {@code Host} or
     * {@code X-Forwarded-Host} header, and this string is both sent to the provider
     * and replayed at the token exchange — the provider matches it exactly, so a
     * forged one turns into a failed exchange at best and a redirect to an
     * attacker's host at worst.
     */
    public String redirectUri() {
        return trimTrailingSlash(getPublicBaseUrl()) + CALLBACK_PATH;
    }

    /**
     * Whether a {@code returnTo} is a page of this deployment.
     * <p>
     * The user reaches this redirect immediately after authenticating at a
     * provider, which is the moment they are least likely to look at the address
     * bar — so an unvalidated value here is a particularly effective open redirect.
     * Same-origin only, and relative paths are accepted because that is what the
     * Manager actually sends.
     */
    public boolean isAllowedReturnTo(String returnTo) {
        if (returnTo == null || returnTo.isBlank()) {
            return false;
        }
        String candidate = returnTo.trim();
        // A protocol-relative URL ("//evil.example.com") has no scheme and is NOT a
        // relative path — browsers resolve it against the current scheme and go to
        // another host. Checked before the startsWith("/") shortcut below, which
        // would otherwise accept it.
        if (candidate.startsWith("//") || candidate.contains("\\")) {
            return false;
        }
        if (candidate.startsWith("/")) {
            // Parseability is part of "allowed". The redirect that eventually uses
            // this value builds a URI from it, and that happens in the CALLBACK —
            // after the single-use state is consumed and the grant is stored. A path
            // carrying an unencoded space is refused here, where the user can simply
            // be sent to the default page, rather than there, where they cannot.
            return isParseable(candidate);
        }
        String publicBaseUrl = getPublicBaseUrl();
        if (publicBaseUrl.isEmpty()) {
            return false;
        }
        try {
            URI target = new URI(candidate);
            URI base = new URI(publicBaseUrl);
            if (target.getScheme() == null || target.getHost() == null) {
                return false;
            }
            // Each side's port is folded against its OWN scheme. Raw ports would make
            // https://host and https://host:443 two different origins, so a base URL
            // written either way rejects a returnTo written the other way — and the
            // user lands on the default page right after authenticating, with nothing
            // saying why.
            return target.getScheme().equalsIgnoreCase(base.getScheme()) && target.getHost().equalsIgnoreCase(base.getHost())
                    && ConnectionConfiguration.normalizePort(target.getScheme(), target.getPort()) == ConnectionConfiguration
                            .normalizePort(base.getScheme(), base.getPort());
        } catch (Exception e) {
            return false;
        }
    }

    /** Where to send the browser when nothing valid was requested. */
    public String defaultReturnTo() {
        return trimTrailingSlash(getPublicBaseUrl()) + "/manage/connections";
    }

    // --- Internals -----------------------------------------------------------

    private <T> T effective(Function<ConnectionSettings, T> field) {
        T pinnedValue = field.apply(pinned);
        if (pinnedValue != null) {
            return pinnedValue;
        }
        StoredConnectionSettings stored = snapshot().stored();
        return stored == null || stored.settings() == null ? null : field.apply(stored.settings());
    }

    private Snapshot snapshot() {
        if (store == null) {
            return NOTHING_STORED;
        }
        Snapshot current = snapshot;
        if (current != null && nanoClock.getAsLong() - current.loadedAtNanos() < TTL_NANOS) {
            return current;
        }
        synchronized (this) {
            current = snapshot;
            long now = nanoClock.getAsLong();
            if (current != null && now - current.loadedAtNanos() < TTL_NANOS) {
                return current;
            }
            Snapshot loaded = load(current, now);
            snapshot = loaded;
            return loaded;
        }
    }

    private Snapshot load(Snapshot previous, long now) {
        try {
            StoredConnectionSettings stored = store.read(TENANT).orElse(null);
            return new Snapshot(stored, storedOrigins(stored), now);
        } catch (RuntimeException e) {
            if (previous != null) {
                LOGGER.warnf("[CONNECTIONS] Could not read the stored connection settings (%s); keeping the values last read.",
                        e.getClass().getSimpleName());
                return new Snapshot(previous.stored(), previous.origins(), now);
            }
            LOGGER.warnf("[CONNECTIONS] Could not read the stored connection settings (%s). Until they can be read only pinned properties "
                    + "and defaults apply, so connections stay disabled unless %s is set.", e.getClass().getSimpleName(), ENABLED);
            return new Snapshot(null, Set.of(), now);
        }
    }

    /**
     * Canonicalises the stored allowlist, dropping an entry that is not a bare
     * origin instead of failing every request on it. The write boundary refuses
     * such an entry, so one can only arrive by a direct database write — which is
     * exactly the case where a request-time exception would name nothing useful.
     */
    private static Set<String> storedOrigins(StoredConnectionSettings stored) {
        if (stored == null || stored.settings() == null || stored.settings().getCredentialEndpointAllowlist() == null) {
            return Set.of();
        }
        var origins = new LinkedHashSet<String>();
        for (String entry : stored.settings().getCredentialEndpointAllowlist()) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            try {
                origins.add(ConnectionConfiguration.requireCanonicalOrigin(entry.trim(), "credentialEndpointAllowlist"));
            } catch (IllegalArgumentException e) {
                LOGGER.errorf("[CONNECTIONS] The stored credentialEndpointAllowlist entry '%s' is not a bare origin and is ignored, so it "
                        + "approves nothing. Re-save the settings through %s.", sanitize(entry), SETTINGS_PATH);
            }
        }
        return Collections.unmodifiableSet(origins);
    }

    private static List<String> splitOrigins(String configured) {
        List<String> entries = Arrays.stream(configured.split(",")).map(String::trim).filter(entry -> !entry.isEmpty()).toList();
        // A property set to nothing but separators pins nothing. MicroProfile Config
        // already treats an empty value as absent; this extends that to " , ".
        return entries.isEmpty() ? null : entries;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ConnectionSettings copyOf(ConnectionSettings source) {
        if (source == null) {
            return new ConnectionSettings();
        }
        return new ConnectionSettings(source.getEnabled(), source.getPublicBaseUrl(),
                source.getCredentialEndpointAllowlist() == null ? null : List.copyOf(source.getCredentialEndpointAllowlist()),
                source.getAllowPlaintextRemoteOrigins());
    }

    /** Whether {@code URI.create} — what the redirect uses — accepts this value. */
    private static boolean isParseable(String candidate) {
        try {
            URI.create(candidate);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
