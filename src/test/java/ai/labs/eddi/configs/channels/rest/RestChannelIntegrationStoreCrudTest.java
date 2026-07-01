/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels.rest;

import ai.labs.eddi.configs.channels.IChannelIntegrationStore;
import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.channels.model.ObserveConfig;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.datastore.IResourceStore;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestChannelIntegrationStore} — CRUD operations,
 * descriptor sync, channel ID uniqueness, and ObserveConfig validation.
 * <p>
 * Validation-only tests live in the companion
 * {@code RestChannelIntegrationStoreValidationTest}.
 */
class RestChannelIntegrationStoreCrudTest {

    private static final String CHANNEL_ID = "aabbccddee1122334455";

    private IChannelIntegrationStore channelStore;
    private IDocumentDescriptorStore documentDescriptorStore;
    private RestChannelIntegrationStore sut;

    @BeforeEach
    void setUp() {
        channelStore = mock(IChannelIntegrationStore.class);
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        sut = new RestChannelIntegrationStore(channelStore, documentDescriptorStore);
    }

    private static ChannelIntegrationConfiguration validConfig() {
        var cfg = new ChannelIntegrationConfiguration();
        cfg.setName("My Slack Hub");
        cfg.setChannelType("slack");
        cfg.setDefaultTargetName("support");

        var target = new ChannelTarget();
        target.setName("support");
        target.setTargetId("agent-abc");
        target.setType(ChannelTarget.TargetType.AGENT);
        target.setTriggers(List.of("support"));

        cfg.setTargets(List.of(target));
        return cfg;
    }

    private IResourceStore.IResourceId dummyResourceId(String id, int version) {
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return id;
            }
            @Override
            public Integer getVersion() {
                return version;
            }
        };
    }

    // ─── readChannelDescriptors ────────────────────────────────────────────────

    @Nested
    @DisplayName("readChannelDescriptors")
    class ReadDescriptors {

        @Test
        @DisplayName("should delegate to documentDescriptorStore")
        void delegatesToStore() throws Exception {
            var descriptors = List.of(new DocumentDescriptor());
            when(documentDescriptorStore.readDescriptors("ai.labs.channel", "filter", 0, 10, false))
                    .thenReturn(descriptors);

            List<DocumentDescriptor> result = sut.readChannelDescriptors("filter", 0, 10);

            assertEquals(1, result.size());
        }
    }

    // ─── readChannel ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("readChannel")
    class ReadChannel {

        @Test
        @DisplayName("should delegate to channelStore via restVersionInfo")
        void delegatesToStore() throws Exception {
            var config = validConfig();
            when(channelStore.read(CHANNEL_ID, 1)).thenReturn(config);

            ChannelIntegrationConfiguration result = sut.readChannel(CHANNEL_ID, 1);

            assertNotNull(result);
            assertEquals("My Slack Hub", result.getName());
        }
    }

    // ─── createChannel ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createChannel")
    class CreateChannel {

        @Test
        @DisplayName("should create channel and return 201")
        void createSuccess() throws Exception {
            var config = validConfig();
            when(channelStore.create(any())).thenReturn(dummyResourceId(CHANNEL_ID, 1));
            // No existing channels for uniqueness check
            lenient().when(documentDescriptorStore.readDescriptors(eq("ai.labs.channel"), eq(""), eq(0), eq(1000), eq(false)))
                    .thenReturn(List.of());

            Response response = sut.createChannel(config);

            assertEquals(201, response.getStatus());
            verify(channelStore).create(any());
        }

        @Test
        @DisplayName("should reject invalid config on create")
        void rejectInvalid() {
            var config = new ChannelIntegrationConfiguration(); // missing required fields

            assertThrows(BadRequestException.class, () -> sut.createChannel(config));
        }
    }

    // ─── updateChannel ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("updateChannel")
    class UpdateChannel {

        @Test
        @DisplayName("should update channel and return OK")
        void updateSuccess() throws Exception {
            var config = validConfig();
            when(channelStore.update(eq(CHANNEL_ID), eq(1), any())).thenReturn(2);
            when(channelStore.getCurrentResourceId(CHANNEL_ID)).thenReturn(dummyResourceId(CHANNEL_ID, 2));
            when(documentDescriptorStore.readDescriptor(CHANNEL_ID, 2)).thenReturn(new DocumentDescriptor());
            lenient().when(documentDescriptorStore.readDescriptors(eq("ai.labs.channel"), eq(""), eq(0), eq(1000), eq(false)))
                    .thenReturn(List.of());

            Response response = sut.updateChannel(CHANNEL_ID, 1, config);

            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("should reject invalid config on update")
        void rejectInvalidOnUpdate() {
            var config = new ChannelIntegrationConfiguration(); // missing name

            assertThrows(BadRequestException.class,
                    () -> sut.updateChannel(CHANNEL_ID, 1, config));
        }
    }

    // ─── deleteChannel ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("deleteChannel")
    class DeleteChannel {

        @Test
        @DisplayName("should soft-delete channel")
        void softDelete() throws Exception {
            when(channelStore.getCurrentResourceId(CHANNEL_ID)).thenReturn(dummyResourceId(CHANNEL_ID, 1));

            Response response = sut.deleteChannel(CHANNEL_ID, 1, false);

            assertEquals(200, response.getStatus());
            verify(channelStore).delete(CHANNEL_ID, 1);
        }

        @Test
        @DisplayName("should permanent-delete channel")
        void permanentDelete() throws Exception {
            when(channelStore.getCurrentResourceId(CHANNEL_ID)).thenReturn(dummyResourceId(CHANNEL_ID, 1));

            Response response = sut.deleteChannel(CHANNEL_ID, 1, true);

            assertEquals(200, response.getStatus());
            verify(channelStore).deleteAllPermanently(CHANNEL_ID);
        }
    }

    // ─── duplicateChannel ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("duplicateChannel")
    class DuplicateChannel {

        @Test
        @DisplayName("should duplicate and clear channelId from platformConfig")
        void duplicateRemovesChannelId() throws Exception {
            var config = validConfig();
            config.setPlatformConfig(new HashMap<>(Map.of("channelId", "C12345", "botToken", "xoxb-xxx")));

            when(channelStore.read(CHANNEL_ID, 1)).thenReturn(config);
            when(channelStore.getCurrentResourceId(CHANNEL_ID)).thenReturn(dummyResourceId(CHANNEL_ID, 1));
            when(channelStore.create(any())).thenReturn(dummyResourceId("newId12345678901234", 1));
            lenient().when(documentDescriptorStore.readDescriptors(eq("ai.labs.channel"), eq(""), eq(0), eq(1000), eq(false)))
                    .thenReturn(List.of());

            Response response = sut.duplicateChannel(CHANNEL_ID, 1);

            assertEquals(201, response.getStatus());
            verify(channelStore).create(any());
        }

        @Test
        @DisplayName("should work when platformConfig is null")
        void duplicateNullPlatformConfig() throws Exception {
            var config = validConfig();
            config.setPlatformConfig(null);

            when(channelStore.read(CHANNEL_ID, 1)).thenReturn(config);
            when(channelStore.getCurrentResourceId(CHANNEL_ID)).thenReturn(dummyResourceId(CHANNEL_ID, 1));
            when(channelStore.create(any())).thenReturn(dummyResourceId("newId12345678901234", 1));
            lenient().when(documentDescriptorStore.readDescriptors(eq("ai.labs.channel"), eq(""), eq(0), eq(1000), eq(false)))
                    .thenReturn(List.of());

            assertDoesNotThrow(() -> sut.duplicateChannel(CHANNEL_ID, 1));
        }
    }

    // ─── getResourceURI / getCurrentResourceId ─────────────────────────────────

    @Nested
    @DisplayName("Utility methods")
    class UtilityMethods {

        @Test
        @DisplayName("getResourceURI should return the resource URI")
        void getResourceURI() {
            String uri = sut.getResourceURI();

            assertNotNull(uri);
            assertTrue(uri.contains("channel"));
        }

        @Test
        @DisplayName("getCurrentResourceId should delegate to channelStore")
        void getCurrentResourceId() throws Exception {
            when(channelStore.getCurrentResourceId(CHANNEL_ID)).thenReturn(dummyResourceId(CHANNEL_ID, 3));

            IResourceStore.IResourceId result = sut.getCurrentResourceId(CHANNEL_ID);

            assertEquals(CHANNEL_ID, result.getId());
            assertEquals(3, result.getVersion());
        }
    }

    // ─── ObserveConfig validation (edge cases beyond what validation test covers)
    // ──

    @Nested
    @DisplayName("ObserveConfig validation bounds")
    class ObserveConfigBounds {

        @Test
        @DisplayName("should reject negative cooldownSeconds")
        void negativeCooldown() {
            var config = validConfig();
            var target = new ChannelTarget();
            target.setName("support");
            target.setTargetId("agent-abc");
            target.setTriggers(List.of("support"));
            var oc = new ObserveConfig();
            oc.setCooldownSeconds(-1);
            target.setObserveConfig(oc);
            config.setTargets(List.of(target));

            assertThrows(BadRequestException.class,
                    () -> sut.validateConfiguration(config));
        }

        @Test
        @DisplayName("should reject negative maxDailyResponses")
        void negativeMaxDailyResponses() {
            var config = validConfig();
            var target = new ChannelTarget();
            target.setName("support");
            target.setTargetId("agent-abc");
            target.setTriggers(List.of("support"));
            var oc = new ObserveConfig();
            oc.setMaxDailyResponses(-5);
            target.setObserveConfig(oc);
            config.setTargets(List.of(target));

            assertThrows(BadRequestException.class,
                    () -> sut.validateConfiguration(config));
        }

        @Test
        @DisplayName("should reject negative maxCostPerDay")
        void negativeMaxCostPerDay() {
            var config = validConfig();
            var target = new ChannelTarget();
            target.setName("support");
            target.setTargetId("agent-abc");
            target.setTriggers(List.of("support"));
            var oc = new ObserveConfig();
            oc.setMaxCostPerDay(-1.0);
            target.setObserveConfig(oc);
            config.setTargets(List.of(target));

            assertThrows(BadRequestException.class,
                    () -> sut.validateConfiguration(config));
        }
    }

    // ─── Channel ID uniqueness ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Channel ID uniqueness validation")
    class ChannelIdUniqueness {

        @Test
        @DisplayName("should reject duplicate channelId across configs")
        void duplicateChannelId() throws Exception {
            var config = validConfig();
            config.setPlatformConfig(new HashMap<>(Map.of("channelId", "C12345")));

            // Existing config with the same channelId
            var descriptor = new DocumentDescriptor();
            descriptor.setResource(URI.create("eddi://ai.labs.channel/channelstore/channels/aabbccddeeff112233445566?version=1"));
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.channel"), eq(""), eq(0), eq(1000), eq(false)))
                    .thenReturn(List.of(descriptor));

            var existing = validConfig();
            existing.setPlatformConfig(Map.of("channelId", "C12345"));
            existing.setName("Other Config");
            when(channelStore.read("aabbccddeeff112233445566", 1)).thenReturn(existing);

            assertThrows(BadRequestException.class,
                    () -> sut.createChannel(config));
        }

        @Test
        @DisplayName("should allow channelId when config is the same document being updated")
        void sameDocumentOnUpdate() throws Exception {
            var config = validConfig();
            config.setPlatformConfig(new HashMap<>(Map.of("channelId", "C12345")));

            var descriptor = new DocumentDescriptor();
            descriptor.setResource(URI.create("eddi://ai.labs.channel/channelstore/channels/" + CHANNEL_ID + "?version=1"));
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.channel"), eq(""), eq(0), eq(1000), eq(false)))
                    .thenReturn(List.of(descriptor));

            var existing = validConfig();
            existing.setPlatformConfig(Map.of("channelId", "C12345"));
            when(channelStore.read(CHANNEL_ID, 1)).thenReturn(existing);
            when(channelStore.update(eq(CHANNEL_ID), eq(1), any())).thenReturn(2);
            when(channelStore.getCurrentResourceId(CHANNEL_ID)).thenReturn(dummyResourceId(CHANNEL_ID, 2));
            when(documentDescriptorStore.readDescriptor(CHANNEL_ID, 2)).thenReturn(new DocumentDescriptor());

            // Should not throw — updating the same config
            assertDoesNotThrow(() -> sut.updateChannel(CHANNEL_ID, 1, config));
        }

        @Test
        @DisplayName("should skip uniqueness check when no channelId in platformConfig")
        void noChannelId() throws Exception {
            var config = validConfig();
            config.setPlatformConfig(new HashMap<>()); // no channelId

            when(channelStore.create(any())).thenReturn(dummyResourceId("newChan12345678901", 1));

            assertDoesNotThrow(() -> sut.createChannel(config));
        }

        @Test
        @DisplayName("should skip uniqueness check when platformConfig is null")
        void nullPlatformConfig() throws Exception {
            var config = validConfig();
            config.setPlatformConfig(null);

            when(channelStore.create(any())).thenReturn(dummyResourceId("newChan12345678901", 1));

            assertDoesNotThrow(() -> sut.createChannel(config));
        }
    }

    // ─── sanitizeForLog ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("sanitizeForLog edge cases")
    class SanitizeForLog {

        @Test
        @DisplayName("descriptor sync should handle exceptions gracefully")
        void descriptorSyncError() throws Exception {
            var config = validConfig();
            when(channelStore.create(any())).thenReturn(dummyResourceId(CHANNEL_ID, 1));
            when(channelStore.getCurrentResourceId(CHANNEL_ID)).thenThrow(new IResourceStore.ResourceNotFoundException("not found"));
            lenient().when(documentDescriptorStore.readDescriptors(eq("ai.labs.channel"), eq(""), eq(0), eq(1000), eq(false)))
                    .thenReturn(List.of());

            // Should not throw — descriptor sync failure is logged, not rethrown
            assertDoesNotThrow(() -> sut.createChannel(config));
        }
    }
}
