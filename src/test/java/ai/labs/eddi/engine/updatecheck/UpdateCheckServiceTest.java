/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.updatecheck;

import ai.labs.eddi.configs.branding.BrandRequest;
import ai.labs.eddi.configs.branding.BrandingService;
import ai.labs.eddi.configs.branding.model.BrandAssets;
import ai.labs.eddi.configs.branding.model.BrandColors;
import ai.labs.eddi.configs.branding.model.BrandSource;
import ai.labs.eddi.configs.branding.model.ResolvedBrand;
import ai.labs.eddi.engine.httpclient.outbound.BodyMode;
import ai.labs.eddi.engine.httpclient.outbound.OutboundHttp;
import ai.labs.eddi.engine.httpclient.outbound.OutboundRequest;
import ai.labs.eddi.engine.httpclient.outbound.OutboundResponse;
import ai.labs.eddi.engine.security.spaces.notifications.WorkspaceNotifications;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The update check is on by default and has two ways out. What is pinned here:
 * the property wins over the stored switch and is honoured before the database
 * is touched (so it stops the very first check), a release is announced once,
 * and nothing that is not a plain EDDI release reaches an administrator's
 * inbox.
 */
class UpdateCheckServiceTest {

    private static final String RELEASE = "{\"tag_name\":\"v9.9.9\",\"html_url\":\"https://github.com/labsai/EDDI/releases/tag/9.9.9\"}";

    /** In-memory store, so the test sees what was written. */
    private static final class MemoryStore implements ISystemSettingStore {
        final Map<String, String> values = new HashMap<>();
        int reads;

        @Override
        public Optional<String> read(String key) {
            reads++;
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public void write(String key, String value, String updatedBy) {
            values.put(key, value);
        }
    }

    private MemoryStore store;
    private OutboundHttp http;
    private WorkspaceNotifications notifications;
    private BrandingService branding;

    @BeforeEach
    void setUp() throws Exception {
        store = new MemoryStore();
        http = mock(OutboundHttp.class);
        notifications = mock(WorkspaceNotifications.class);
        branding = mock(BrandingService.class);
        when(branding.current(any(BrandRequest.class))).thenReturn(ResolvedBrand.defaults(false));
        answer(200, RELEASE);
    }

    private void answer(int status, String body) throws Exception {
        when(http.send(any(OutboundRequest.class), any(BodyMode.class))).thenAnswer(
                i -> new OutboundResponse<>(status, "", Map.of(), URI.create(UpdateCheckService.DEFAULT_URL), List.of(), body, false));
    }

    private UpdateCheckService service(boolean property, String installed) {
        return new UpdateCheckService(property, installed, UpdateCheckService.DEFAULT_URL, store, http, new ObjectMapper(), notifications,
                branding);
    }

    @Test
    @DisplayName("is on by default: a newer release raises one notification")
    void notifiesWhenNewer() {
        var release = service(true, "6.7.0").checkNow();

        assertTrue(release.isPresent());
        verify(notifications).onUpdateAvailable("9.9.9", "https://github.com/labsai/EDDI/releases/tag/9.9.9");
        assertEquals("9.9.9", store.values.get(UpdateCheckService.SETTING_NOTIFIED_VERSION));
    }

    @Test
    @DisplayName("does not announce the same release again after it was dismissed")
    void announcedOnce() {
        var sut = service(true, "6.7.0");
        sut.checkNow();
        sut.checkNow();

        verify(notifications, times(1)).onUpdateAvailable(anyString(), any());
    }

    @Test
    @DisplayName("announces a release that is newer than the last one announced")
    void announcesNextRelease() throws Exception {
        store.values.put(UpdateCheckService.SETTING_NOTIFIED_VERSION, "9.9.8");

        service(true, "6.7.0").checkNow();

        verify(notifications).onUpdateAvailable(eq("9.9.9"), any());
    }

    @Test
    @DisplayName("says nothing when up to date or ahead")
    void quietWhenCurrent() {
        assertTrue(service(true, "9.9.9").checkNow().isEmpty());
        assertTrue(service(true, "10.0.0-SNAPSHOT").checkNow().isEmpty());
        assertTrue(service(true, "unknown").checkNow().isEmpty());

        verify(notifications, never()).onUpdateAvailable(any(), any());
    }

    @Test
    @DisplayName("eddi.update-check.enabled=false: no request, and the database is never read")
    void propertyOffTouchesNothing() {
        var sut = service(false, "6.7.0");

        sut.scheduledCheck();

        verifyNoInteractions(http, notifications);
        assertEquals(0, store.reads, "the property is read before the database, so the first check after the first boot is suppressed");
    }

    @Test
    @DisplayName("the property wins over a stored switch that says on")
    void propertyWinsOverStoredOn() {
        store.values.put(UpdateCheckService.SETTING_ENABLED, "true");
        var sut = service(false, "6.7.0");

        var status = sut.status();

        assertFalse(status.enabled());
        assertTrue(status.lockedByConfig());
        assertTrue(sut.checkNow().isEmpty());
    }

    @Test
    @DisplayName("an administrator can switch it off, and it is stored")
    void storedSwitchOff() {
        var sut = service(true, "6.7.0");
        assertTrue(sut.status().enabled());

        var status = sut.setUiEnabled(false, "admin");

        assertFalse(status.enabled());
        assertFalse(status.lockedByConfig());
        assertFalse(status.uiEnabled());
        assertEquals("false", store.values.get(UpdateCheckService.SETTING_ENABLED));
        sut.scheduledCheck();
        verifyNoInteractions(http);
    }

    @Test
    @DisplayName("an unreadable store keeps the default rather than silently going dark")
    void unreadableStoreDefaultsOn() {
        ISystemSettingStore broken = new ISystemSettingStore() {
            @Override
            public Optional<String> read(String key) {
                throw new IllegalStateException("down");
            }

            @Override
            public void write(String key, String value, String updatedBy) {
            }
        };
        var sut = new UpdateCheckService(true, "6.7.0", UpdateCheckService.DEFAULT_URL, broken, http, new ObjectMapper(), notifications, branding);

        assertTrue(sut.isEnabled());
    }

    @Test
    @DisplayName("a white-labelled deployment that did not ask for the update UI neither checks nor notifies")
    void brandedStaysSilent() {
        when(branding.current(any(BrandRequest.class))).thenReturn(new ResolvedBrand(1, BrandSource.DATABASE, false, true, "Acme", "Acme", null,
                BrandColors.EMPTY, true, false, BrandAssets.EMPTY));

        assertTrue(service(true, "6.7.0").checkNow().isEmpty());

        verifyNoInteractions(http, notifications);
    }

    @Test
    @DisplayName("a failed lookup is not an error and notifies no one")
    void failedLookup() throws Exception {
        answer(403, "{}");
        assertTrue(service(true, "6.7.0").checkNow().isEmpty());

        when(http.send(any(OutboundRequest.class), any(BodyMode.class))).thenThrow(new IOException("offline"));
        assertTrue(service(true, "6.7.0").checkNow().isEmpty());

        verify(notifications, never()).onUpdateAvailable(any(), any());
    }

    @Test
    @DisplayName("only EDDI's own release page ever reaches the inbox")
    void foreignUrlReplaced() {
        var release = service(true, "6.7.0").parse("{\"tag_name\":\"9.9.9\",\"html_url\":\"https://evil.example/x\"}");

        assertEquals("https://github.com/labsai/EDDI/releases", release.orElseThrow().url());
    }

    @Test
    @DisplayName("a tag that is not a version is ignored")
    void junkTag() {
        assertTrue(service(true, "6.7.0").parse("{\"tag_name\":\"<script>\"}").isEmpty());
        assertTrue(service(true, "6.7.0").parse("not json").isEmpty());
    }
}
