/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.channels;

import ai.labs.eddi.integrations.channels.types.SlackChannelType;
import ai.labs.eddi.integrations.channels.types.WebhookChannelType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelTypeRegistryTest {

    private static Map<String, String> fakeConfig() {
        var pc = new HashMap<String, String>();
        pc.put("connection", "fakechat-bot");
        pc.put("appId", "app-1");
        return pc;
    }

    @Nested
    @DisplayName("Discovery")
    class Discovery {

        @Test
        @DisplayName("the built-ins are slack, webhook and email, found case-insensitively")
        void builtIns() {
            var registry = ChannelTypeRegistry.builtIns();
            assertEquals(Set.of("email", "slack", "webhook"), registry.ids());
            assertTrue(registry.find("SLACK").isPresent());
            assertTrue(registry.find("teams").isEmpty());
            assertTrue(registry.find(null).isEmpty());
            assertTrue(registry.find(" email").isEmpty(), "not trimmed: the stored string is what everything downstream compares");
        }

        @Test
        @DisplayName("two descriptors with one id fail fast")
        void duplicateId() {
            var ex = assertThrows(IllegalStateException.class,
                    () -> new ChannelTypeRegistry(List.of(new SlackChannelType(), new SlackChannelType())));
            assertTrue(ex.getMessage().contains("registered twice"));
        }

        @Test
        @DisplayName("an id that is not lower-case fails fast")
        void upperCaseId() {
            ChannelTypeDescriptor bad = () -> "Teams";
            assertThrows(IllegalStateException.class, () -> new ChannelTypeRegistry(List.of(bad)));
        }

        @Test
        @DisplayName("a connection-backed type without an allow-list fails fast")
        void connectionBackedWithoutAllowList() {
            ChannelTypeDescriptor bad = new ChannelTypeDescriptor() {
                @Override
                public String id() {
                    return "bad";
                }

                @Override
                public boolean connectionBacked() {
                    return true;
                }
            };
            assertThrows(IllegalStateException.class, () -> new ChannelTypeRegistry(List.of(bad)));
        }
    }

    @Nested
    @DisplayName("platformConfig validation")
    class Validation {

        private final ChannelTypeDescriptor fake = new FakeChatChannelType();

        @Test
        @DisplayName("allowed keys and a connection pass")
        void valid() {
            assertEquals(List.of(), ChannelTypeRegistry.validatePlatformConfig(fake, fakeConfig()));
        }

        @Test
        @DisplayName("a plaintext token under an unknown key is refused, pointing to connections")
        void plaintextToken() {
            var pc = fakeConfig();
            pc.put("botToken", "xoxb-not-a-real-token");
            var errors = ChannelTypeRegistry.validatePlatformConfig(fake, pc);
            assertEquals(1, errors.size());
            assertTrue(errors.get(0).contains("platformConfig.botToken is not a fakechat setting"));
            assertTrue(errors.get(0).contains("platformConfig.connection"));
            assertTrue(errors.get(0).contains("Allowed:"));
        }

        @Test
        @DisplayName("a vault reference under an unknown key is refused too")
        void vaultReference() {
            var pc = fakeConfig();
            pc.put("appPassword", "${vault:teams-app-password}");
            var errors = ChannelTypeRegistry.validatePlatformConfig(fake, pc);
            assertEquals(1, errors.size());
            assertTrue(errors.get(0).contains("not even as a vault reference"));
        }

        @Test
        @DisplayName("a missing connection is refused")
        void missingConnection() {
            var pc = fakeConfig();
            pc.remove("connection");
            var errors = ChannelTypeRegistry.validatePlatformConfig(fake, pc);
            assertTrue(errors.stream().anyMatch(e -> e.contains("requires platformConfig.connection")), errors.toString());
        }

        @Test
        @DisplayName("a malformed connection name is refused; ${connection:name} is accepted")
        void connectionName() {
            var pc = fakeConfig();
            pc.put("connection", "not a name!");
            assertFalse(ChannelTypeRegistry.validatePlatformConfig(fake, pc).isEmpty());
            pc.put("connection", "${connection:fakechat-bot}");
            assertEquals(List.of(), ChannelTypeRegistry.validatePlatformConfig(fake, pc));
        }

        @Test
        @DisplayName("a channelId that looks like an application key is refused")
        void channelIdLikeApplicationKey() {
            var pc = fakeConfig();
            pc.put("channelId", "@app:someone-elses-app");
            assertEquals(List.of("platformConfig.channelId must not start with '@'."), ChannelTypeRegistry.validatePlatformConfig(fake, pc));
        }

        @Test
        @DisplayName("the type's own rules run as well")
        void ownRules() {
            var pc = fakeConfig();
            pc.remove("appId");
            assertEquals(List.of("A fakechat integration requires platformConfig.appId."),
                    ChannelTypeRegistry.validatePlatformConfig(fake, pc));
        }

        @Test
        @DisplayName("a legacy type accepts keys it does not know, so stored Slack documents stay valid")
        void legacyTypeAcceptsAnyKey() {
            var pc = Map.of("channelId", "C1", "botToken", "${vault:slack-bot}", "somethingOld", "x");
            assertEquals(List.of(), ChannelTypeRegistry.validatePlatformConfig(new SlackChannelType(), pc));
        }
    }

    @Nested
    @DisplayName("Routing keys")
    class RoutingKeys {

        @Test
        @DisplayName("channelId wins; without one the application key; without both nothing")
        void defaults() {
            var fake = new FakeChatChannelType();
            assertEquals(List.of("C1"), fake.routingKeys(null, Map.of("appId", "a", "channelId", "C1")));
            assertEquals(List.of("@app:a"), fake.routingKeys(null, Map.of("appId", "a")));
            assertEquals(List.of(), fake.routingKeys(null, Map.of()));
            assertEquals(List.of(), new SlackChannelType().routingKeys(null, Map.of("botToken", "x")));
        }

        @Test
        @DisplayName("a webhook is routed by its resource id, an email integration not at all")
        void webhookAndEmail() {
            var registry = ChannelTypeRegistry.builtIns();
            assertEquals(List.of("abc"), registry.routingKeys("webhook", "abc", Map.of()));
            assertEquals(List.of(), new WebhookChannelType().routingKeys(null, Map.of()));
            assertEquals(List.of(), registry.routingKeys("email", "abc", Map.of("channelId", "C1")));
        }

        @Test
        @DisplayName("a type no descriptor claims is routed by channelId, as before descriptors")
        void unknownType() {
            assertEquals(List.of("C9"), ChannelTypeRegistry.builtIns().routingKeys("gone", null, Map.of("channelId", "C9")));
            assertEquals(List.of(), ChannelTypeRegistry.builtIns().routingKeys("gone", null, Map.of()));
        }

        @Test
        @DisplayName("error messages describe application keys and channel keys differently")
        void describe() {
            assertEquals("application id 'a'", ChannelTypeDescriptor.describeRoutingKey("@app:a"));
            assertEquals("channelId 'C1'", ChannelTypeDescriptor.describeRoutingKey("C1"));
            assertEquals(Optional.empty(), new SlackChannelType().applicationId(Map.of("appId", "x")));
        }
    }
}
