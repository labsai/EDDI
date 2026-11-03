/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.updatecheck;

import ai.labs.eddi.configs.branding.BrandRequest;
import ai.labs.eddi.configs.branding.BrandingService;
import ai.labs.eddi.configs.branding.model.ResolvedBrand;
import ai.labs.eddi.engine.httpclient.outbound.BodyMode;
import ai.labs.eddi.engine.httpclient.outbound.OutboundHttp;
import ai.labs.eddi.engine.httpclient.outbound.OutboundRequest;
import ai.labs.eddi.engine.httpclient.outbound.OutboundResponse;
import ai.labs.eddi.engine.httpclient.outbound.Profile;
import ai.labs.eddi.engine.security.spaces.notifications.WorkspaceNotifications;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * Looks for a newer EDDI release and tells the administrators when there is
 * one.
 *
 * <h3>On by default, off two ways</h3> A fresh install checks with no opt-in,
 * because an instance nobody tells about a release is an instance nobody
 * upgrades. It costs one anonymous request to {@code api.github.com} a day,
 * carrying nothing about the deployment, and there are two ways out:
 * <ul>
 * <li>{@code eddi.update-check.enabled=false} (env
 * {@code EDDI_UPDATE_CHECK_ENABLED=false}) — read before anything else,
 * <em>including before the database is touched</em>, so it stops the very first
 * check after the very first boot, before anyone can reach the Manager. It also
 * wins over the stored switch and is shown to the Manager as locked.</li>
 * <li>The Manager's Updates page (an admin), stored in
 * {@link ISystemSettingStore} and so shared by every node and surviving
 * restarts.</li>
 * </ul>
 *
 * <h3>Branding</h3> A white-labelled deployment that has not asked for the
 * update UI neither checks nor notifies: the notice would name EDDI and GitHub
 * inside somebody else's product.
 *
 * <h3>One notice per release</h3> The newest announced version is stored, so a
 * dismissed notification is not raised again by the next day's check or by a
 * restart; only a still newer release raises the next one.
 */
@ApplicationScoped
public class UpdateCheckService {

    private static final Logger LOGGER = Logger.getLogger(UpdateCheckService.class);

    /** {@code eddi.update-check.enabled} — the operator's switch. */
    public static final String ENABLED_PROPERTY = "eddi.update-check.enabled";

    static final String SETTING_ENABLED = "update-check.enabled";
    static final String SETTING_NOTIFIED_VERSION = "update-check.notified-version";

    static final String DEFAULT_URL = "https://api.github.com/repos/labsai/EDDI/releases/latest";
    private static final String RELEASE_PAGE_PREFIX = "https://github.com/labsai/EDDI/";
    private static final int MAX_RESPONSE_BYTES = 1_000_000;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /**
     * What the Manager is told.
     *
     * @param enabled
     *            whether checks run (property AND stored switch)
     * @param lockedByConfig
     *            {@code eddi.update-check.enabled=false} — the stored switch cannot
     *            turn the check on
     * @param uiEnabled
     *            the stored switch alone, so the Manager can show what it would
     *            return to
     */
    public record Status(boolean enabled, boolean lockedByConfig, boolean uiEnabled) {
    }

    /** A published release. */
    record Release(String version, String url) {
    }

    private final boolean propertyEnabled;
    private final String installedVersion;
    private final URI releaseApi;
    private final ISystemSettingStore settings;
    private final OutboundHttp http;
    private final ObjectMapper objectMapper;
    private final WorkspaceNotifications notifications;
    private final BrandingService branding;

    @Inject
    public UpdateCheckService(@ConfigProperty(name = ENABLED_PROPERTY, defaultValue = "true") boolean propertyEnabled,
            @ConfigProperty(name = "quarkus.application.version", defaultValue = "unknown") String installedVersion,
            @ConfigProperty(name = "eddi.update-check.url", defaultValue = DEFAULT_URL) String releaseApi, ISystemSettingStore settings,
            OutboundHttp http, ObjectMapper objectMapper, WorkspaceNotifications notifications, BrandingService branding) {
        this.propertyEnabled = propertyEnabled;
        this.installedVersion = installedVersion;
        this.releaseApi = URI.create(releaseApi);
        this.settings = settings;
        this.http = http;
        this.objectMapper = objectMapper;
        this.notifications = notifications;
        this.branding = branding;
    }

    // ---- the switch ----------------------------------------------------------

    public Status status() {
        boolean stored = storedEnabled();
        return new Status(propertyEnabled && stored, !propertyEnabled, stored);
    }

    /** Whether a check may run now. The property is tested before the store. */
    public boolean isEnabled() {
        return propertyEnabled && storedEnabled();
    }

    /**
     * Stores the Manager's switch.
     *
     * @return the resulting status
     */
    public Status setUiEnabled(boolean enabled, String user) {
        settings.write(SETTING_ENABLED, Boolean.toString(enabled), user);
        return status();
    }

    private boolean storedEnabled() {
        try {
            return !"false".equals(settings.read(SETTING_ENABLED).orElse("true"));
        } catch (RuntimeException e) {
            // Unreadable store: keep the default rather than silently going dark.
            LOGGER.debugf("Could not read the stored update-check switch: %s", e.getMessage());
            return true;
        }
    }

    // ---- the check -----------------------------------------------------------

    /**
     * The scheduled check. The first run is a minute after boot; the interval is
     * {@code eddi.update-check.interval} (default 24h).
     */
    @Scheduled(every = "${eddi.update-check.interval:24h}", delayed = "${eddi.update-check.initial-delay:1m}", identity = "update-check",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void scheduledCheck() {
        if (!propertyEnabled) {
            // Before any store access: with the property off nothing at all happens.
            return;
        }
        try {
            checkNow();
        } catch (RuntimeException e) {
            // Never let a background notice break anything; the next run retries.
            LOGGER.warnf("Update check failed: %s", e.getMessage());
        }
    }

    /**
     * Fetches the latest release and, when it is newer than this instance and not
     * yet announced, raises the notification.
     *
     * @return the newer release, or empty when up to date, switched off or the
     *         lookup failed
     */
    Optional<Release> checkNow() {
        if (!isEnabled() || brandingHidesUpdates()) {
            return Optional.empty();
        }
        Optional<Release> latest = fetchLatest();
        if (latest.isEmpty() || !ReleaseVersions.isNewer(installedVersion, latest.get().version())) {
            return Optional.empty();
        }
        Release release = latest.get();
        if (alreadyAnnounced(release.version())) {
            return Optional.of(release);
        }
        notifications.onUpdateAvailable(release.version(), release.url());
        try {
            settings.write(SETTING_NOTIFIED_VERSION, release.version(), "system");
        } catch (RuntimeException e) {
            LOGGER.debugf("Could not remember the announced version: %s", e.getMessage());
        }
        LOGGER.infof("EDDI %s is available (running %s)", release.version(), installedVersion);
        return Optional.of(release);
    }

    private boolean alreadyAnnounced(String version) {
        try {
            Optional<String> announced = settings.read(SETTING_NOTIFIED_VERSION);
            return announced.isPresent() && !ReleaseVersions.isNewer(announced.get(), version);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean brandingHidesUpdates() {
        try {
            ResolvedBrand brand = branding.current(BrandRequest.NONE);
            return brand.branded() && !brand.showUpdateCheck();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private Optional<Release> fetchLatest() {
        try {
            OutboundRequest request = OutboundRequest.get(Profile.DEFAULT, releaseApi).header("Accept", "application/vnd.github+json")
                    .timeout(TIMEOUT).build();
            OutboundResponse<String> response = http.send(request, BodyMode.string(MAX_RESPONSE_BYTES, BodyMode.OnOverflow.FAIL));
            if (response.status() < 200 || response.status() >= 300) {
                LOGGER.debugf("Update check answered %d", response.status());
                return Optional.empty();
            }
            return parse(response.body());
        } catch (IOException e) {
            LOGGER.debugf("Update check could not reach %s: %s", releaseApi.getHost(), e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    Optional<Release> parse(String body) {
        try {
            JsonNode json = objectMapper.readTree(body);
            String version = ReleaseVersions.normalize(json.path("tag_name").asText(""));
            if (!ReleaseVersions.isVersion(version)) {
                return Optional.empty();
            }
            String url = json.path("html_url").asText("");
            // The URL ends up in an administrator's inbox: only ever EDDI's own releases.
            if (!url.startsWith(RELEASE_PAGE_PREFIX)) {
                url = RELEASE_PAGE_PREFIX + "releases";
            }
            return Optional.of(new Release(version, url));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
