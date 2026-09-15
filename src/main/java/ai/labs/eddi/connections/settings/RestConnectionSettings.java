/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.settings;

import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.connections.ConnectionStartupGuard;
import ai.labs.eddi.connections.ConnectionsConfig;
import ai.labs.eddi.connections.settings.ConnectionSettingsView.Setting;
import ai.labs.eddi.connections.settings.ConnectionSettingsView.Source;
import ai.labs.eddi.connections.settings.IConnectionSettingsStore.StoredConnectionSettings;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Reads and replaces the stored connection settings.
 * <p>
 * Validation happens here, at the write boundary, because this is where the
 * administrator who made the mistake is still looking at the response. The
 * startup guard used to refuse a bad base URL at boot instead, which turned a
 * typo into an outage and required a restart to fix.
 */
@ApplicationScoped
public class RestConnectionSettings implements IRestConnectionSettings {

    private static final Logger LOGGER = Logger.getLogger(RestConnectionSettings.class);

    static final String ANONYMOUS = "anonymous";

    /** The opt-out behind {@link #requireAuthenticatedWrite()}. */
    public static final String ALLOW_UNAUTHENTICATED_WRITES = "eddi.connections.settings.allow-unauthenticated-writes";

    private final ConnectionsConfig connectionsConfig;
    private final IConnectionSettingsStore store;
    private final SecurityIdentity securityIdentity;
    private final ConnectionStartupGuard startupGuard;
    private final boolean authorizationEnabled;
    private final boolean allowUnauthenticatedWrites;

    @Inject
    public RestConnectionSettings(ConnectionsConfig connectionsConfig, IConnectionSettingsStore store, SecurityIdentity securityIdentity,
            ConnectionStartupGuard startupGuard,
            @ConfigProperty(name = "authorization.enabled", defaultValue = "false") boolean authorizationEnabled,
            @ConfigProperty(name = ALLOW_UNAUTHENTICATED_WRITES, defaultValue = "false") boolean allowUnauthenticatedWrites) {
        this.connectionsConfig = connectionsConfig;
        this.store = store;
        this.securityIdentity = securityIdentity;
        this.startupGuard = startupGuard;
        this.authorizationEnabled = authorizationEnabled;
        this.allowUnauthenticatedWrites = allowUnauthenticatedWrites;
    }

    @Override
    public ConnectionSettingsView readSettings() {
        // A settings page is where someone checks whether their change landed, so
        // it must not answer from a cache another replica's write has not reached.
        connectionsConfig.refresh();
        return view();
    }

    @Override
    public ConnectionSettingsView updateSettings(ConnectionSettings requested) {
        requireAuthenticatedWrite();
        if (requested == null) {
            throw new BadRequestException("A settings document is required. Send {} to unset every stored value.");
        }
        ConnectionSettings pinned = connectionsConfig.pinnedSettings();
        ConnectionSettings previous = readStoredNow();
        boolean wasEnabled = connectionsConfig.isEnabled();

        var accepted = new ConnectionSettings();
        accepted.setEnabled(unlessPinned(requested.getEnabled(), pinned.getEnabled(), previous.getEnabled(), "enabled",
                ConnectionsConfig.ENABLED));
        accepted.setPublicBaseUrl(pinned.getPublicBaseUrl() != null
                ? keepPinnedBaseUrl(requested.getPublicBaseUrl(), pinned.getPublicBaseUrl(), previous.getPublicBaseUrl())
                : normalizePublicBaseUrl(requested.getPublicBaseUrl()));
        accepted.setCredentialEndpointAllowlist(pinned.getCredentialEndpointAllowlist() != null
                ? keepPinnedAllowlist(requested.getCredentialEndpointAllowlist(), previous.getCredentialEndpointAllowlist())
                : normalizeAllowlist(requested.getCredentialEndpointAllowlist()));
        accepted.setAllowPlaintextRemoteOrigins(unlessPinned(requested.getAllowPlaintextRemoteOrigins(), pinned.getAllowPlaintextRemoteOrigins(),
                previous.getAllowPlaintextRemoteOrigins(), "allowPlaintextRemoteOrigins", ConnectionsConfig.ALLOW_PLAINTEXT_REMOTE_ORIGINS));

        String principal = principal();
        var written = new StoredConnectionSettings(accepted, Instant.now(), principal);
        try {
            store.write(ConnectionsConfig.TENANT, written);
        } catch (RuntimeException e) {
            LOGGER.errorf(e, "[CONNECTIONS] Could not store the connection settings written by '%s'", sanitize(principal));
            throw new InternalServerErrorException("The connection settings could not be stored; nothing was changed.");
        }
        connectionsConfig.adopt(written);
        logChange(previous, accepted, principal);

        if (!wasEnabled && connectionsConfig.isEnabled() && startupGuard != null) {
            // The startup guard reports stored connections this deployment cannot
            // honour — but only for a boot on which the feature was already on.
            // Switched on here, nothing would ever report them until the next restart.
            startupGuard.reportStoredConnections();
        }
        return view();
    }

    // --- Who may write -------------------------------------------------------

    /**
     * Refuses a write that no verified identity stands behind, outside dev and
     * test.
     * <p>
     * {@code @RolesAllowed} is a no-op while {@code authorization.enabled=false},
     * and the shipped compose files and manifests run that way. Before these
     * settings were writable, an anonymous caller on such a deployment could not
     * approve a new credential endpoint or turn the feature on at all; this
     * endpoint must not quietly hand them that. An operator who genuinely wants
     * unauthenticated writes says so with {@link #ALLOW_UNAUTHENTICATED_WRITES} —
     * the same narrow, per-surface opt-out {@code HighValueSurfaceGuard} gives
     * {@code /mcp} and {@code /secretstore} — or pins the values with their
     * properties instead.
     */
    private void requireAuthenticatedWrite() {
        if (authorizationEnabled || allowUnauthenticatedWrites || ConnectionSettingsRules.isDevOrTest()) {
            return;
        }
        throw new ForbiddenException("Changing the connection settings needs a verified identity, and authorization.enabled=false. "
                + "Enable OIDC, pin the values with their eddi.connections.* properties, or set " + ALLOW_UNAUTHENTICATED_WRITES
                + "=true to accept unauthenticated writes deliberately.");
    }

    // --- Validation ----------------------------------------------------------

    /**
     * The stored value to write for one pinned-or-not scalar field.
     * <p>
     * A pinned field keeps whatever is stored for it, untouched: overwriting it
     * with the request's copy of the pinned value would seed the store, and the
     * copy would silently take over the day the property is removed. A request that
     * tries to <em>change</em> a pinned field is refused rather than ignored,
     * because "saved" followed by no effect is the confusion pinning must not
     * cause. What is kept is surfaced by {@link #view()}, so it is never invisible.
     */
    private static <T> T unlessPinned(T requested, T pinnedValue, T previouslyStored, String field, String property) {
        if (pinnedValue == null) {
            return requested;
        }
        if (requested != null && !Objects.equals(requested, pinnedValue)) {
            throw pinnedConflict(field, property);
        }
        return previouslyStored;
    }

    /**
     * A pinned base URL may be restated in any form that yields the same redirect
     * URI — a trailing slash or an upper-cased scheme is not a change. The
     * comparison runs before the strict shape check, so restating a pinned value
     * can never be refused for that value's own shape.
     */
    private static String keepPinnedBaseUrl(String requested, String pinnedValue, String previouslyStored) {
        if (requested != null && !requested.isBlank() && !sameBaseUrl(requested, pinnedValue)) {
            throw pinnedConflict("publicBaseUrl", ConnectionsConfig.PUBLIC_BASE_URL);
        }
        return previouslyStored;
    }

    /**
     * A pinned allowlist may be restated in any order, case or port spelling that
     * canonicalises to the same set. Compared before the plaintext rule, because a
     * pinned list is the operator's and may legitimately hold an entry an
     * administrator could not store.
     */
    private List<String> keepPinnedAllowlist(List<String> requested, List<String> previouslyStored) {
        if (requested != null) {
            List<String> canonical;
            try {
                canonical = ConnectionSettingsRules.canonicalOrigins(requested, "credentialEndpointAllowlist");
            } catch (IllegalArgumentException e) {
                throw new BadRequestException(e.getMessage());
            }
            if (!new LinkedHashSet<>(canonical).equals(connectionsConfig.credentialEndpointOrigins())) {
                throw pinnedConflict("credentialEndpointAllowlist", ConnectionsConfig.CREDENTIAL_ENDPOINT_ALLOWLIST);
            }
        }
        return previouslyStored;
    }

    private static ClientErrorException pinnedConflict(String field, String property) {
        return new ClientErrorException(field + " is pinned by the " + property + " property, so it cannot be changed here. Remove it "
                + "from the request, or ask whoever operates this deployment to unset the property.", Response.Status.CONFLICT);
    }

    private static boolean sameBaseUrl(String a, String b) {
        return comparableBaseUrl(a).equals(comparableBaseUrl(b));
    }

    private static String comparableBaseUrl(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static String normalizePublicBaseUrl(String requested) {
        if (requested == null || requested.isBlank()) {
            return null;
        }
        try {
            return ConnectionSettingsRules.requireBarePublicOrigin(requested, "publicBaseUrl", ConnectionSettingsRules.isDevOrTest());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
    }

    /**
     * Canonical, de-duplicated, and free of entries that could never approve
     * anything.
     * <p>
     * A remote plaintext origin is refused because a token or authorization
     * endpoint must be https (or http on loopback) before anything is sent to it,
     * so such an entry would sit in the list looking like an approval and approve
     * nothing.
     */
    private static List<String> normalizeAllowlist(List<String> requested) {
        if (requested == null) {
            return null;
        }
        List<String> canonical;
        try {
            canonical = ConnectionSettingsRules.canonicalOrigins(requested, "credentialEndpointAllowlist");
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
        for (String origin : canonical) {
            if (ConnectionConfiguration.isPlaintextRemoteOrigin(origin)) {
                throw new BadRequestException("credentialEndpointAllowlist entry " + origin + " is plaintext http to a remote host. A token "
                        + "or authorization endpoint must be https, or http on loopback, before a client secret is sent to it — so this "
                        + "entry would approve nothing. Use the https origin.");
            }
        }
        return canonical;
    }

    // --- The effective view --------------------------------------------------

    ConnectionSettingsView view() {
        ConnectionSettings pinned = connectionsConfig.pinnedSettings();
        Optional<StoredConnectionSettings> stored = connectionsConfig.storedSettings();
        ConnectionSettings storedModel = stored.map(StoredConnectionSettings::settings).orElseGet(ConnectionSettings::new);

        boolean enabled = connectionsConfig.isEnabled();
        String publicBaseUrl = connectionsConfig.getPublicBaseUrl();
        List<String> origins = List.copyOf(connectionsConfig.credentialEndpointOrigins());
        boolean allowPlaintext = connectionsConfig.isAllowPlaintextRemoteOrigins();
        Optional<String> baseUrlProblem = connectionsConfig.publicBaseUrlProblem();

        Setting<Boolean> enabledSetting = setting(enabled, pinned, storedModel, ConnectionSettings::getEnabled, ConnectionsConfig.ENABLED);
        Setting<String> baseUrlSetting = setting(publicBaseUrl.isEmpty() ? null : publicBaseUrl, pinned, storedModel,
                ConnectionSettings::getPublicBaseUrl, ConnectionsConfig.PUBLIC_BASE_URL);
        Setting<List<String>> allowlistSetting = setting(origins, pinned, storedModel, ConnectionSettings::getCredentialEndpointAllowlist,
                ConnectionsConfig.CREDENTIAL_ENDPOINT_ALLOWLIST);
        Setting<Boolean> plaintextSetting = setting(allowPlaintext, pinned, storedModel, ConnectionSettings::getAllowPlaintextRemoteOrigins,
                ConnectionsConfig.ALLOW_PLAINTEXT_REMOTE_ORIGINS);

        var warnings = new ArrayList<String>();
        if (enabled && baseUrlProblem.isPresent()) {
            warnings.add(baseUrlProblem.get() + " Linking an account to an OAUTH2_AUTHORIZATION_CODE connection is refused until it is set; "
                    + "other connection types are unaffected.");
        }
        if (enabled && origins.isEmpty()) {
            warnings.add("credentialEndpointAllowlist is empty, so no OAuth connection can resolve. STATIC and BASIC connections are "
                    + "unaffected.");
        }
        if (allowPlaintext) {
            warnings.add("allowPlaintextRemoteOrigins is on: a connection may send its credential over plaintext http to a remote host, "
                    + "where it crosses the network unencrypted.");
        }
        for (Setting<?> shadowing : List.of(enabledSetting, baseUrlSetting, allowlistSetting, plaintextSetting)) {
            if (shadowing.shadowedStoredValue() != null) {
                warnings.add(shadowing.property() + " pins a value, and a different stored value (" + shadowing.shadowedStoredValue()
                        + ") is hidden behind it. That stored value takes effect the moment the property is removed.");
            }
        }

        return new ConnectionSettingsView(enabledSetting, baseUrlSetting, allowlistSetting, plaintextSetting,
                baseUrlProblem.isEmpty() ? connectionsConfig.redirectUri() : null,
                stored.map(StoredConnectionSettings::updatedAt).map(Instant::toString).orElse(null),
                stored.map(StoredConnectionSettings::updatedBy).orElse(null), List.copyOf(warnings));
    }

    /**
     * One effective value, with where it came from — and, for a pinned value, any
     * different stored value it hides. Without that, an administrator who stored a
     * permissive value before an operator pinned a restrictive one could not see
     * that removing the pin would bring the permissive value straight back.
     */
    private static <T> Setting<T> setting(T effectiveValue, ConnectionSettings pinned, ConnectionSettings stored,
                                          Function<ConnectionSettings, T> field, String property) {
        T pinnedValue = field.apply(pinned);
        T storedValue = field.apply(stored);
        if (pinnedValue != null) {
            T shadowed = storedValue != null && !equivalent(storedValue, effectiveValue) ? storedValue : null;
            return new Setting<>(effectiveValue, Source.PINNED, property, shadowed);
        }
        return new Setting<>(effectiveValue, storedValue != null ? Source.STORED : Source.DEFAULT, property, null);
    }

    private static boolean equivalent(Object stored, Object effective) {
        if (stored instanceof List<?> storedList && effective instanceof List<?> effectiveList) {
            return new LinkedHashSet<>(storedList).equals(new LinkedHashSet<>(effectiveList));
        }
        if (stored instanceof String storedString && effective instanceof String effectiveString) {
            return sameBaseUrl(storedString, effectiveString);
        }
        return Objects.equals(stored, effective);
    }

    // --- Helpers -------------------------------------------------------------

    /**
     * The store as it is now, not as cached: a pinned field's stored value is
     * carried forward from this, and a five-second-old copy would silently revert
     * another administrator's write.
     */
    private ConnectionSettings readStoredNow() {
        try {
            return store.read(ConnectionsConfig.TENANT).map(StoredConnectionSettings::settings).orElseGet(ConnectionSettings::new);
        } catch (RuntimeException e) {
            LOGGER.errorf(e, "[CONNECTIONS] Could not read the stored connection settings before a write");
            throw new InternalServerErrorException("The connection settings could not be read; nothing was changed.");
        }
    }

    private String principal() {
        if (securityIdentity == null || securityIdentity.isAnonymous() || securityIdentity.getPrincipal() == null) {
            return ANONYMOUS;
        }
        String name = securityIdentity.getPrincipal().getName();
        return name == null || name.isBlank() ? ANONYMOUS : name;
    }

    /**
     * One INFO line naming who changed what.
     * <p>
     * Values are logged, not redacted: none of these is a secret — an origin, a
     * base URL and two booleans — and "the allowlist changed" without saying to
     * what is not something anybody can review afterwards.
     */
    private static void logChange(ConnectionSettings previous, ConnectionSettings accepted, String principal) {
        var changes = new ArrayList<String>();
        describeChange(changes, "enabled", previous, accepted, ConnectionSettings::getEnabled);
        describeChange(changes, "publicBaseUrl", previous, accepted, ConnectionSettings::getPublicBaseUrl);
        describeChange(changes, "credentialEndpointAllowlist", previous, accepted, ConnectionSettings::getCredentialEndpointAllowlist);
        describeChange(changes, "allowPlaintextRemoteOrigins", previous, accepted, ConnectionSettings::getAllowPlaintextRemoteOrigins);
        if (changes.isEmpty()) {
            LOGGER.infof("[CONNECTIONS] Settings re-saved by '%s' without changes", sanitize(principal));
        } else {
            LOGGER.infof("[CONNECTIONS] Settings changed by '%s': %s", sanitize(principal), sanitize(String.join("; ", changes)));
        }
    }

    private static void describeChange(List<String> changes, String field, ConnectionSettings previous, ConnectionSettings accepted,
                                       Function<ConnectionSettings, ?> getter) {
        Object before = getter.apply(previous);
        Object after = getter.apply(accepted);
        if (!Objects.equals(before, after)) {
            changes.add(field + " " + (before == null ? "unset" : before) + " -> " + (after == null ? "unset" : after));
        }
    }
}
