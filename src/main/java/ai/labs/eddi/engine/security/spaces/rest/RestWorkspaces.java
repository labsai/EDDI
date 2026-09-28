/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces.rest;

import ai.labs.eddi.engine.security.spaces.CallerSpaces;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.spaces.SpaceContext;
import ai.labs.eddi.engine.security.spaces.Subjects;
import ai.labs.eddi.engine.security.spaces.WorkspaceSettings;
import ai.labs.eddi.engine.security.spaces.directory.UserDirectory;
import ai.labs.eddi.engine.security.spaces.notifications.WorkspaceNotification;
import ai.labs.eddi.engine.security.spaces.notifications.WorkspaceNotifications;
import ai.labs.eddi.engine.security.spaces.rest.model.SpaceInfo;
import ai.labs.eddi.engine.security.spaces.rest.model.WorkspaceInfo;
import ai.labs.eddi.engine.security.spaces.rest.model.WorkspaceSettingsView;
import ai.labs.eddi.engine.security.spaces.settings.IWorkspaceSettingsStore;
import ai.labs.eddi.engine.security.spaces.settings.IWorkspaceSettingsStore.StoredWorkspaceSettings;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * @author ginccc
 */
@ApplicationScoped
public class RestWorkspaces implements IRestWorkspaces {

    private static final Logger LOGGER = Logger.getLogger(RestWorkspaces.class);

    private final SpaceContext spaceContext;
    private final WorkspaceSettings settings;
    private final ResourceAccessGuard accessGuard;
    private final UserDirectory directory;
    private final IWorkspaceSettingsStore settingsStore;
    private final WorkspaceNotifications notifications;

    @Inject
    public RestWorkspaces(SpaceContext spaceContext, WorkspaceSettings settings, ResourceAccessGuard accessGuard, UserDirectory directory,
            IWorkspaceSettingsStore settingsStore, WorkspaceNotifications notifications) {
        this.spaceContext = spaceContext;
        this.settings = settings;
        this.accessGuard = accessGuard;
        this.directory = directory;
        this.settingsStore = settingsStore;
        this.notifications = notifications;
    }

    /** With a settings store and no notifications. Test seam. */
    public RestWorkspaces(SpaceContext spaceContext, WorkspaceSettings settings, ResourceAccessGuard accessGuard, UserDirectory directory,
            IWorkspaceSettingsStore settingsStore) {
        this(spaceContext, settings, accessGuard, directory, settingsStore, null);
    }

    /** Without a settings store: the settings are read-only. Test seam. */
    public RestWorkspaces(SpaceContext spaceContext, WorkspaceSettings settings, ResourceAccessGuard accessGuard, UserDirectory directory) {
        this(spaceContext, settings, accessGuard, directory, null, null);
    }

    @Override
    public List<WorkspaceNotification> readNotifications(Boolean unreadOnly, Integer limit) {
        return notifications == null ? List.of() : notifications.inbox(Boolean.TRUE.equals(unreadOnly), limit == null ? 50 : limit);
    }

    @Override
    public Map<String, Long> countUnreadNotifications() {
        return Map.of("unread", notifications == null ? 0L : notifications.unreadCount());
    }

    @Override
    public Map<String, Long> markNotificationsRead(MarkRead request) {
        if (notifications == null) {
            return Map.of("marked", 0L);
        }
        List<String> ids = request == null ? null : request.ids();
        return Map.of("marked", notifications.markRead(ids));
    }

    @Override
    public List<UserDirectory.Match> searchDirectory(String query, Integer limit) {
        return directory.search(query, limit == null ? 10 : limit, spaceContext.current());
    }

    @Override
    public WorkspaceSettingsView readSettings() {
        // A settings page is where someone checks their change landed, so it must not
        // answer from a cache another replica's write has not reached.
        settings.refresh();
        return settingsView();
    }

    @Override
    public WorkspaceSettingsView updateSettings(WorkspaceSettingsView.Update update) {
        if (update == null) {
            throw new BadRequestException("A settings document is required. Send {} to unset every stored value.");
        }
        if (!settings.isStoreBacked()) {
            throw new ClientErrorException("Workspace settings are fixed on this deployment and cannot be changed at runtime.",
                    Response.Status.CONFLICT);
        }
        StoredWorkspaceSettings previous;
        try {
            settings.refresh();
            previous = settings.storedSettings().orElse(new StoredWorkspaceSettings(null, null, null, null));
        } catch (RuntimeException e) {
            throw new InternalServerErrorException("The workspace settings could not be read; nothing was changed.");
        }

        String defaultSpace = acceptDefaultSpace(update.defaultSpace(), previous.defaultSpace());
        String legacyVisibility = acceptLegacyVisibility(update.legacyVisibility(), previous.legacyVisibility());

        String principal = spaceContext.currentPrincipal();
        var written = new StoredWorkspaceSettings(defaultSpace, legacyVisibility, Instant.now(), principal);
        try {
            settingsStore.write(WorkspaceSettings.TENANT, written);
        } catch (RuntimeException e) {
            LOGGER.errorf(e, "[WORKSPACES] Could not store the workspace settings written by '%s'", sanitize(principal));
            throw new InternalServerErrorException("The workspace settings could not be stored; nothing was changed.");
        }
        settings.adopt(written);
        LOGGER.infof("[WORKSPACES] Settings changed by '%s': default-space %s -> %s; legacy-visibility %s -> %s", sanitize(principal),
                describe(previous.defaultSpace()), describe(defaultSpace), describe(previous.legacyVisibility()), describe(legacyVisibility));
        return settingsView();
    }

    /**
     * The stored default space to write. A pinned value keeps whatever is stored
     * untouched — overwriting it with a copy of the pinned value would seed the
     * store, and the copy would silently take over the day the property goes — and
     * a request that tries to change it is refused rather than ignored.
     */
    private String acceptDefaultSpace(String requested, String previouslyStored) {
        String normalized = WorkspaceSettings.normalizeTeam(requested);
        String pinned = settings.pinnedDefaultSpace();
        if (pinned != null) {
            if (requested != null && !requested.isBlank() && !pinned.equals(normalized)) {
                throw pinnedConflict("defaultSpace", WorkspaceSettings.DEFAULT_SPACE_PROPERTY);
            }
            return previouslyStored;
        }
        return normalized;
    }

    private String acceptLegacyVisibility(String requested, String previouslyStored) {
        String normalized = requested == null || requested.isBlank() ? null : requested.trim().toLowerCase(Locale.ROOT);
        if (normalized != null && !WorkspaceSettings.isValidLegacyVisibility(normalized)) {
            throw new BadRequestException("legacyVisibility must be '" + WorkspaceSettings.LEGACY_SHARED + "' or '"
                    + WorkspaceSettings.LEGACY_ADMIN_ONLY + "' — was '" + sanitize(requested) + "'");
        }
        String pinned = settings.pinnedLegacyVisibility();
        if (pinned != null) {
            if (normalized != null && !pinned.equalsIgnoreCase(normalized)) {
                throw pinnedConflict("legacyVisibility", WorkspaceSettings.LEGACY_VISIBILITY_PROPERTY);
            }
            return previouslyStored;
        }
        return normalized;
    }

    private static ClientErrorException pinnedConflict(String field, String property) {
        return new ClientErrorException(field + " is pinned by the " + property + " property, so it cannot be changed here. Remove it "
                + "from the request, or ask whoever operates this deployment to unset the property.", Response.Status.CONFLICT);
    }

    private WorkspaceSettingsView settingsView() {
        String pinnedSpace = settings.pinnedDefaultSpace();
        String pinnedLegacy = settings.pinnedLegacyVisibility();
        StoredWorkspaceSettings stored = settings.storedSettings().orElse(null);

        WorkspaceSettingsView.Setting defaultSpace = new WorkspaceSettingsView.Setting(settings.getDefaultSpaceTeam().orElse(null),
                pinnedSpace != null
                        ? WorkspaceSettingsView.Source.PINNED
                        : stored != null && stored.defaultSpace() != null
                                ? WorkspaceSettingsView.Source.STORED
                                : WorkspaceSettingsView.Source.DEFAULT,
                WorkspaceSettings.DEFAULT_SPACE_PROPERTY);
        WorkspaceSettingsView.Setting legacy = new WorkspaceSettingsView.Setting(settings.effectiveLegacyVisibility(),
                pinnedLegacy != null
                        ? WorkspaceSettingsView.Source.PINNED
                        : stored != null && stored.legacyVisibility() != null
                                ? WorkspaceSettingsView.Source.STORED
                                : WorkspaceSettingsView.Source.DEFAULT,
                WorkspaceSettings.LEGACY_VISIBILITY_PROPERTY);

        List<String> warnings = new ArrayList<>();
        if (!settings.isEnforcing()) {
            warnings.add("Workspaces are not enforced (eddi.workspaces.enabled=false, or authentication is off). These settings are recorded "
                    + "and take effect once enforcement is switched on.");
        }
        settings.getDefaultSpaceTeam().ifPresent(team -> {
            String subject = Subjects.team(team);
            if (directory.isActive() && directory.teamMembers(subject, 1).isEmpty()) {
                warnings.add("Nobody who has signed in belongs to the team '" + team + "'. New resources filed there are still owned by "
                        + "their creators, but no teammate will see them until members of that group sign in.");
            }
        });
        return new WorkspaceSettingsView(settings.isEnforcing(), settings.getGroupsClaim(), defaultSpace, legacy,
                stored == null || stored.updatedAt() == null ? null : stored.updatedAt().toString(), stored == null ? null : stored.updatedBy(),
                List.copyOf(warnings));
    }

    private static String describe(String value) {
        return value == null ? "unset" : value;
    }

    @Override
    public WorkspaceInfo readWorkspaceInfo() {
        CallerSpaces caller = spaceContext.current();

        // Reported even when enforcement is off, so a client can show the same
        // "you are alice" affordances either way. What changes with the flag is
        // whether any of it is *enforced* — which `enabled` says plainly.
        return new WorkspaceInfo(settings.isEnforcing(),
                spaceContext.currentPrincipal(),
                spaceContext.defaultWriteSpace(),
                describe(caller),
                accessGuard.seesEverything());
    }

    /**
     * Renders the caller's spaces in the order {@link CallerSpaces} holds them —
     * personal first, then teams in token order. The label is decoded here rather
     * than by the client, because the encoding is this package's business.
     */
    private List<SpaceInfo> describe(CallerSpaces caller) {
        List<SpaceInfo> spaces = new ArrayList<>(caller.spaces().size());
        for (String id : caller.spaces()) {
            if (id.startsWith(Subjects.USER_PREFIX)) {
                spaces.add(new SpaceInfo(id, SpaceInfo.KIND_PERSONAL,
                        Subjects.decode(id.substring(Subjects.USER_PREFIX.length()))));
            } else if (id.startsWith(Subjects.TEAM_PREFIX)) {
                spaces.add(new SpaceInfo(id, SpaceInfo.KIND_TEAM,
                        Subjects.decode(id.substring(Subjects.TEAM_PREFIX.length()))));
            }
            // Anything else is not a space a client can switch to. Dropping it is
            // deliberate: an id whose shape we do not recognise would render as a
            // filter that silently matches nothing.
        }
        return spaces;
    }
}
