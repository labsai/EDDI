/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces;

import ai.labs.eddi.engine.security.spaces.settings.IWorkspaceSettingsStore;
import ai.labs.eddi.engine.security.spaces.settings.IWorkspaceSettingsStore.StoredWorkspaceSettings;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Operator-facing settings for per-user workspaces.
 *
 * <h3>Two kinds of setting, on purpose</h3>
 * <ul>
 * <li><b>Startup only</b> — {@code eddi.workspaces.enabled} and
 * {@code eddi.workspaces.groups-claim}. They are the deployment's security
 * posture: whether isolation is enforced at all, and where team membership
 * comes from. Neither should be one API call away from being switched off by a
 * leaked administrator token, so neither is stored.</li>
 * <li><b>Changeable at runtime</b> — the default space new resources land in,
 * and what happens to resources that predate ownership. These are policy, not
 * posture, and used to cost a restart for no protection. An administrator
 * changes them with {@code PUT /workspaces/settings}; an operator who wants a
 * value out of administrators' reach sets its property, which <em>pins</em> it
 * — the same model {@code ConnectionsConfig} uses.</li>
 * </ul>
 *
 * <h3>Freshness</h3> Stored values are cached for {@link #STORED_SETTINGS_TTL}.
 * A write on this instance is adopted at once; other instances see it when
 * their cache expires. Access checks read these on every request, so a store
 * read per call is not an option — and neither is making them wait for one.
 * Once the cache has expired, the first caller re-reads the store and every
 * other caller is served the values last read until it has answered
 * (stale-while-revalidate); only the very first read, with nothing to serve
 * yet, is waited for. When the store cannot be read, the values last read are
 * kept; when it has never been read, unowned resources stay admin-only until it
 * can be.
 * <p>
 * A stored value is validated once, when it is read: a
 * {@code legacy-visibility} that is neither {@code shared} nor
 * {@code admin-only} — only a direct database write can store one — is logged
 * and treated as unset, so the default applies and the settings resource
 * reports it as {@code DEFAULT}.
 *
 * <h3>Why enforcement is a separate switch from ownership</h3>
 * {@code eddi.workspaces.enabled} gates <em>enforcement</em> only. Ownership is
 * stamped on every new resource regardless, so an operator can run a release
 * with attribution recorded and nothing filtered, confirm the data looks right,
 * and only then turn enforcement on.
 *
 * <h3>Why {@code @Startup}</h3> {@link #validate()} refuses to boot on an
 * unrecognised pinned {@code legacy-visibility}. Without {@code @Startup} an
 * {@code @ApplicationScoped} bean is created on first use, so a typo booted
 * green and then failed every guarded request.
 *
 * @author ginccc
 */
@Startup
@ApplicationScoped
public class WorkspaceSettings {

    private static final Logger LOGGER = Logger.getLogger(WorkspaceSettings.class);

    /**
     * {@code eddi.workspaces.legacy-visibility} — admit unowned data to everyone.
     */
    public static final String LEGACY_SHARED = "shared";

    /**
     * {@code eddi.workspaces.legacy-visibility} — admit unowned data to admins
     * only.
     */
    public static final String LEGACY_ADMIN_ONLY = "admin-only";

    /** Pins {@link #getDefaultSpaceTeam()}. */
    public static final String DEFAULT_SPACE_PROPERTY = "eddi.workspaces.default-space";

    /** Pins {@link #admitsLegacy()}. */
    public static final String LEGACY_VISIBILITY_PROPERTY = "eddi.workspaces.legacy-visibility";

    /** How long a stored value may be served before it is read again. */
    public static final Duration STORED_SETTINGS_TTL = Duration.ofSeconds(5);

    /** The tenant whose settings apply, until multi-tenancy supplies one. */
    public static final String TENANT = "default";

    private static final long TTL_NANOS = STORED_SETTINGS_TTL.toNanos();

    private static final Snapshot NOTHING_STORED = new Snapshot(null, 0L, false);

    private final boolean enabled;
    private final boolean authEnabled;
    private final String groupsClaim;

    /** The pinned legacy policy, or null when the property is not set. */
    private final String pinnedLegacyVisibility;

    /** The pinned default team (normalised group name), or null. */
    private final String pinnedDefaultSpace;

    /** Null for fixed settings with nothing stored. */
    private final IWorkspaceSettingsStore store;

    private final LongSupplier nanoClock;

    /** What was last read from the store; null until the first read. */
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();

    /** Set while one caller re-reads an expired snapshot. */
    private final AtomicBoolean refreshing = new AtomicBoolean();

    /**
     * The invalid stored legacy-visibility last reported, so a bad row is logged
     * once rather than at every re-read.
     */
    private volatile String lastIgnoredLegacyVisibility;

    /**
     * @param stored
     *            as read, with {@code legacyVisibility} already validated: trimmed
     *            and lower-case, or null
     * @param unreadable
     *            the store could not be read and nothing was ever read from it — so
     *            what an administrator stored is unknown, not absent
     */
    private record Snapshot(StoredWorkspaceSettings stored, long loadedAtNanos, boolean unreadable) {
    }

    @Inject
    public WorkspaceSettings(@ConfigProperty(name = "eddi.workspaces.enabled", defaultValue = "false") boolean enabled,
            @ConfigProperty(name = "authorization.enabled", defaultValue = "false") boolean authEnabled,
            @ConfigProperty(name = "eddi.workspaces.groups-claim", defaultValue = "groups") String groupsClaim,
            @ConfigProperty(name = LEGACY_VISIBILITY_PROPERTY) Optional<String> legacyVisibility,
            @ConfigProperty(name = DEFAULT_SPACE_PROPERTY) Optional<String> defaultSpaceTeam, IWorkspaceSettingsStore store) {
        this(enabled, authEnabled, groupsClaim, legacyVisibility.orElse(null), defaultSpaceTeam, store, System::nanoTime);
    }

    /**
     * Fixed settings with nothing stored — both policy values pinned exactly as
     * given. Test seam, and the shape this class had before runtime settings.
     */
    public WorkspaceSettings(boolean enabled, boolean authEnabled, String groupsClaim, String legacyVisibility, Optional<String> defaultSpaceTeam) {
        this(enabled, authEnabled, groupsClaim, legacyVisibility == null || legacyVisibility.isBlank() ? LEGACY_SHARED : legacyVisibility,
                defaultSpaceTeam, null, System::nanoTime);
    }

    /** The general seam. Public for tests in other packages. */
    public WorkspaceSettings(boolean enabled, boolean authEnabled, String groupsClaim, String pinnedLegacyVisibility,
            Optional<String> pinnedDefaultSpace, IWorkspaceSettingsStore store, LongSupplier nanoClock) {
        this.enabled = enabled;
        this.authEnabled = authEnabled;
        this.groupsClaim = groupsClaim == null || groupsClaim.isBlank() ? "groups" : groupsClaim.trim();
        this.pinnedLegacyVisibility = pinnedLegacyVisibility == null || pinnedLegacyVisibility.isBlank() ? null : pinnedLegacyVisibility.trim();
        this.pinnedDefaultSpace = pinnedDefaultSpace == null
                ? null
                : pinnedDefaultSpace.map(WorkspaceSettings::normalizeTeam).orElse(null);
        this.store = store;
        this.nanoClock = nanoClock;
    }

    @PostConstruct
    void validate() {
        if (pinnedLegacyVisibility != null && !isValidLegacyVisibility(pinnedLegacyVisibility)) {
            // Fail loud rather than silently picking a policy: the two options differ on
            // whether every pre-upgrade agent is visible, which is not a difference to
            // resolve by guessing.
            throw new IllegalStateException(LEGACY_VISIBILITY_PROPERTY + " must be '" + LEGACY_SHARED + "' or '" + LEGACY_ADMIN_ONLY
                    + "', but was '" + pinnedLegacyVisibility + "'");
        }

        if (enabled && !authEnabled) {
            // Without authentication every caller is anonymous, so there is no principal to
            // scope anything to. Enforcing in that state would deny everyone everything, so
            // isEnforcing() reports false — say why, once.
            LOGGER.warn("eddi.workspaces.enabled=true has no effect while authorization.enabled=false: "
                    + "there is no authenticated principal to scope resources to. Enable OIDC to enforce workspaces.");
        }

        if (enabled && authEnabled) {
            LOGGER.infov("Workspace isolation is ENFORCED (legacy-visibility={0}, groups-claim={1}, default-space={2}); the first and last "
                    + "can be changed at runtime through /workspaces/settings unless pinned", describePin(pinnedLegacyVisibility),
                    groupsClaim, describePin(pinnedDefaultSpace));
        }
    }

    private static String describePin(String pinned) {
        return pinned == null ? "<not pinned>" : pinned;
    }

    /**
     * Whether listings and reads are actually filtered.
     * <p>
     * Both switches must be on. Enforcing without authentication would scope every
     * request to the anonymous principal, which owns nothing.
     */
    public boolean isEnforcing() {
        return enabled && authEnabled;
    }

    /**
     * Whether ownership is recorded on newly created resources. Deliberately
     * independent of {@link #isEnforcing()} and true whenever authentication is on,
     * so the data is already correct by the time an operator flips enforcement.
     */
    public boolean isStampingOwnership() {
        return authEnabled;
    }

    /** The JWT claim listing the caller's groups. */
    public String getGroupsClaim() {
        return groupsClaim;
    }

    /** Whether resources with no recorded owner are visible to non-admins. */
    public boolean admitsLegacy() {
        return LEGACY_SHARED.equalsIgnoreCase(effectiveLegacyVisibility());
    }

    /**
     * {@code shared} or {@code admin-only}: pinned, else stored, else
     * {@code shared}.
     */
    public String effectiveLegacyVisibility() {
        if (pinnedLegacyVisibility != null) {
            return pinnedLegacyVisibility.toLowerCase(Locale.ROOT);
        }
        Snapshot current = snapshot();
        if (current.unreadable()) {
            // Unknown is not "unset". Answering the default here would open every
            // unowned resource to every user whenever the store blinked, on a
            // deployment whose administrator had closed them. Fail closed until the
            // stored value can be read.
            return LEGACY_ADMIN_ONLY;
        }
        // Already validated when it was read, so this stays a plain read: it runs on
        // every access check.
        StoredWorkspaceSettings stored = current.stored();
        return stored != null && stored.legacyVisibility() != null ? stored.legacyVisibility() : LEGACY_SHARED;
    }

    /**
     * The team every new resource is filed under when a request names no space, as
     * a group name — pinned, else stored, else empty.
     * <p>
     * Empty — the default — files new resources in the creator's personal space.
     * Setting it gives a team-first deployment: colleagues see each other's work by
     * default. A request can still name a space of its own with the
     * {@code X-EDDI-Space} header; this is only the fallback.
     */
    public Optional<String> getDefaultSpaceTeam() {
        if (pinnedDefaultSpace != null) {
            return Optional.of(pinnedDefaultSpace);
        }
        StoredWorkspaceSettings stored = snapshot().stored();
        return stored == null ? Optional.empty() : Optional.ofNullable(normalizeTeam(stored.defaultSpace()));
    }

    // --- Provenance, for the settings resource -------------------------------

    /** The pinned legacy policy, or null. */
    public String pinnedLegacyVisibility() {
        return pinnedLegacyVisibility;
    }

    /** The pinned default team, or null. */
    public String pinnedDefaultSpace() {
        return pinnedDefaultSpace;
    }

    /** What is stored, as last read (at most {@link #STORED_SETTINGS_TTL} old). */
    public Optional<StoredWorkspaceSettings> storedSettings() {
        return Optional.ofNullable(snapshot().stored());
    }

    /** Whether there is a store behind these settings at all. */
    public boolean isStoreBacked() {
        return store != null;
    }

    /**
     * Re-reads the store now, keeping the previous values if it fails. A value that
     * landed while the store was being read — an {@link #adopt}ed write — is newer
     * than this read and is kept instead.
     */
    public void refresh() {
        if (store == null) {
            return;
        }
        Snapshot current = snapshot.get();
        snapshot.compareAndSet(current, load(current, nanoClock.getAsLong()));
    }

    /**
     * Serves a document this instance has just written, without reading it back —
     * so a settings page shows the new values even if a read right after the write
     * would have failed.
     */
    public void adopt(StoredWorkspaceSettings written) {
        if (store == null) {
            return;
        }
        snapshot.set(new Snapshot(validated(written), nanoClock.getAsLong(), false));
    }

    public static boolean isValidLegacyVisibility(String value) {
        return value != null && (LEGACY_SHARED.equalsIgnoreCase(value.trim()) || LEGACY_ADMIN_ONLY.equalsIgnoreCase(value.trim()));
    }

    /**
     * A group name as a default space: surrounding slashes and whitespace removed,
     * blank meaning none. A value written as {@code team:engineering} is accepted
     * and means the same group.
     */
    public static String normalizeTeam(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith(Subjects.TEAM_PREFIX)) {
            trimmed = Subjects.decode(trimmed.substring(Subjects.TEAM_PREFIX.length()));
        }
        String normalized = Subjects.normalizeGroup(trimmed);
        return normalized.isEmpty() ? null : normalized;
    }

    private Snapshot snapshot() {
        if (store == null) {
            return NOTHING_STORED;
        }
        Snapshot current = snapshot.get();
        if (current == null) {
            return firstLoad();
        }
        if (isFresh(current)) {
            return current;
        }
        if (!refreshing.compareAndSet(false, true)) {
            // Another caller is already reading the store. Serve what was last read
            // rather than queue every access check behind a database call that has no
            // timeout of its own.
            return current;
        }
        try {
            current = snapshot.get();
            if (isFresh(current)) {
                return current; // re-read by a caller that finished just before this one
            }
            Snapshot loaded = load(current, nanoClock.getAsLong());
            // Losing the swap means an adopt() or refresh() landed while the store was
            // being read; theirs is at least as new as this read, so it stays.
            return snapshot.compareAndSet(current, loaded) ? loaded : snapshot.get();
        } finally {
            refreshing.set(false);
        }
    }

    /**
     * The first read, which callers do wait for: there is nothing to serve them
     * yet, and answering the defaults instead would open unowned resources on a
     * deployment whose administrator had closed them.
     */
    private synchronized Snapshot firstLoad() {
        Snapshot current = snapshot.get();
        if (current != null) {
            return current;
        }
        Snapshot loaded = load(null, nanoClock.getAsLong());
        return snapshot.compareAndSet(null, loaded) ? loaded : snapshot.get();
    }

    private boolean isFresh(Snapshot candidate) {
        return nanoClock.getAsLong() - candidate.loadedAtNanos() < TTL_NANOS;
    }

    /**
     * {@code stored} with its legacy-visibility checked once, here, instead of on
     * every access check: trimmed and lower-cased when valid, dropped when not.
     */
    private StoredWorkspaceSettings validated(StoredWorkspaceSettings stored) {
        if (stored == null || stored.legacyVisibility() == null) {
            lastIgnoredLegacyVisibility = null;
            return stored;
        }
        String raw = stored.legacyVisibility();
        if (isValidLegacyVisibility(raw)) {
            lastIgnoredLegacyVisibility = null;
            String normalized = raw.trim().toLowerCase(Locale.ROOT);
            return normalized.equals(raw)
                    ? stored
                    : new StoredWorkspaceSettings(stored.defaultSpace(), normalized, stored.updatedAt(), stored.updatedBy());
        }
        // The write boundary refuses this, so it can only come from a direct database
        // write. Falling back to the default is what an unconfigured deployment does.
        if (!raw.equals(lastIgnoredLegacyVisibility)) {
            lastIgnoredLegacyVisibility = raw;
            LOGGER.warnf("Ignoring the stored legacy-visibility '%s': it is neither '%s' nor '%s', so the default '%s' applies and "
                    + "GET /workspaces/settings reports it as DEFAULT. Set it again with PUT /workspaces/settings.", sanitize(raw),
                    LEGACY_SHARED, LEGACY_ADMIN_ONLY, LEGACY_SHARED);
        }
        return new StoredWorkspaceSettings(stored.defaultSpace(), null, stored.updatedAt(), stored.updatedBy());
    }

    private Snapshot load(Snapshot previous, long now) {
        try {
            return new Snapshot(validated(store.read(TENANT).orElse(null)), now, false);
        } catch (RuntimeException e) {
            if (previous != null) {
                LOGGER.warnf("Could not read the stored workspace settings (%s); keeping the values last read.", e.getClass().getSimpleName());
                return new Snapshot(previous.stored(), now, previous.unreadable());
            }
            LOGGER.warnf("Could not read the stored workspace settings (%s); pinned properties apply, and unowned resources stay "
                    + "admin-only, until they can be read.", e.getClass().getSimpleName());
            return new Snapshot(null, now, true);
        }
    }
}
