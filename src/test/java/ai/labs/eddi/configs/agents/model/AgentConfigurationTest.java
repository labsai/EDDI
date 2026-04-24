/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgentConfiguration} inner classes:
 * MemoryPolicy.isEffectivelyEnabled(), Capability defaults, SecurityConfig
 * defaults.
 */
class AgentConfigurationTest {

    @Nested
    @DisplayName("MemoryPolicy.isEffectivelyEnabled")
    class MemoryPolicyTest {

        @Test
        @DisplayName("default should not be effectively enabled")
        void defaultNotEnabled() {
            var policy = new AgentConfiguration.MemoryPolicy();
            assertFalse(policy.isEffectivelyEnabled());
        }

        @Test
        @DisplayName("enabled=true with digest should be effectively enabled")
        void enabledDigest() {
            var swd = new AgentConfiguration.StrictWriteDiscipline();
            swd.setEnabled(true);
            swd.setOnFailure("digest");
            var policy = new AgentConfiguration.MemoryPolicy();
            policy.setStrictWriteDiscipline(swd);
            assertTrue(policy.isEffectivelyEnabled());
        }

        @Test
        @DisplayName("enabled=true with keep_all should NOT be effectively enabled")
        void enabledKeepAll() {
            var swd = new AgentConfiguration.StrictWriteDiscipline();
            swd.setEnabled(true);
            swd.setOnFailure("keep_all");
            var policy = new AgentConfiguration.MemoryPolicy();
            policy.setStrictWriteDiscipline(swd);
            assertFalse(policy.isEffectivelyEnabled());
        }

        @Test
        @DisplayName("enabled=true with exclude_all should be effectively enabled")
        void enabledExcludeAll() {
            var swd = new AgentConfiguration.StrictWriteDiscipline();
            swd.setEnabled(true);
            swd.setOnFailure("exclude_all");
            var policy = new AgentConfiguration.MemoryPolicy();
            policy.setStrictWriteDiscipline(swd);
            assertTrue(policy.isEffectivelyEnabled());
        }

        @Test
        @DisplayName("null strictWriteDiscipline should not be effectively enabled")
        void nullSwd() {
            var policy = new AgentConfiguration.MemoryPolicy();
            policy.setStrictWriteDiscipline(null);
            assertFalse(policy.isEffectivelyEnabled());
        }
    }

    @Nested
    @DisplayName("StrictWriteDiscipline defaults")
    class StrictWriteDisciplineTest {

        @Test
        @DisplayName("defaults: not enabled, onFailure=digest")
        void defaults() {
            var swd = new AgentConfiguration.StrictWriteDiscipline();
            assertFalse(swd.isEnabled());
            assertEquals("digest", swd.getOnFailure());
        }
    }

    @Nested
    @DisplayName("Capability")
    class CapabilityTest {

        @Test
        @DisplayName("default constructor should set no skill")
        void defaultConstructor() {
            var cap = new AgentConfiguration.Capability();
            assertNull(cap.getSkill());
            assertEquals("medium", cap.getConfidence());
        }

        @Test
        @DisplayName("parameterized constructor with null attributes")
        void nullAttributes() {
            var cap = new AgentConfiguration.Capability("translation", null, null);
            assertNotNull(cap.getAttributes());
            assertEquals("medium", cap.getConfidence());
        }

        @Test
        @DisplayName("parameterized constructor with values")
        void withValues() {
            var cap = new AgentConfiguration.Capability("translation",
                    Map.of("languages", "en,de"), "high");
            assertEquals("translation", cap.getSkill());
            assertEquals("high", cap.getConfidence());
            assertEquals("en,de", cap.getAttributes().get("languages"));
        }
    }

    @Nested
    @DisplayName("SecurityConfig defaults")
    class SecurityConfigTest {

        @Test
        @DisplayName("all signing defaults should be false")
        void defaults() {
            var config = new AgentConfiguration.SecurityConfig();
            assertFalse(config.isSignInterAgentMessages());
            assertFalse(config.isSignMcpInvocations());
            assertFalse(config.isRequirePeerVerification());
        }
    }

    @Nested
    @DisplayName("AgentConfiguration defaults")
    class AgentConfigDefaults {

        @Test
        @DisplayName("new config should have empty workflows and channels")
        void defaults() {
            var config = new AgentConfiguration();
            assertNotNull(config.getWorkflows());
            assertTrue(config.getWorkflows().isEmpty());
            assertNotNull(config.getChannels());
            assertTrue(config.getChannels().isEmpty());
            assertFalse(config.isA2aEnabled());
            assertFalse(config.isEnableMemoryTools());
        }
    }

    @Nested
    @DisplayName("AgentIdentity")
    class AgentIdentityTest {

        @Test
        void defaultConstructor() {
            var id = new AgentConfiguration.AgentIdentity();
            assertNull(id.getAgentDid());
            assertNull(id.getPublicKey());
        }

        @Test
        void parameterizedConstructor() {
            var id = new AgentConfiguration.AgentIdentity("did:eddi:agent:1", "pubkey-abc");
            assertEquals("did:eddi:agent:1", id.getAgentDid());
            assertEquals("pubkey-abc", id.getPublicKey());
        }

        @Test
        void setters() {
            var id = new AgentConfiguration.AgentIdentity();
            id.setAgentDid("did:x");
            id.setPublicKey("key123");
            assertEquals("did:x", id.getAgentDid());
            assertEquals("key123", id.getPublicKey());
        }
    }

    @Nested
    @DisplayName("ChannelConnector")
    class ChannelConnectorTest {

        @Test
        void defaults() {
            var cc = new AgentConfiguration.ChannelConnector();
            assertNull(cc.getType());
            assertTrue(cc.getConfig().isEmpty());
        }

        @Test
        void setters() {
            var cc = new AgentConfiguration.ChannelConnector();
            cc.setType(java.net.URI.create("eddi://channel/slack"));
            cc.setConfig(Map.of("token", "xoxb-123"));
            assertEquals("eddi://channel/slack", cc.getType().toString());
            assertEquals("xoxb-123", cc.getConfig().get("token"));
        }
    }

    @Nested
    @DisplayName("UserMemoryConfig")
    class UserMemoryConfigTest {

        @Test
        void defaults() {
            var umc = new AgentConfiguration.UserMemoryConfig();
            assertEquals("self", umc.getDefaultVisibility());
            assertEquals(50, umc.getMaxRecallEntries());
            assertEquals(500, umc.getMaxEntriesPerUser());
            assertEquals("evict_oldest", umc.getOnCapReached());
            assertEquals("most_recent", umc.getRecallOrder());
            assertEquals(2, umc.getAutoRecallCategories().size());
            assertNotNull(umc.getGuardrails());
            assertNotNull(umc.getDream());
        }

        @Test
        void setters() {
            var umc = new AgentConfiguration.UserMemoryConfig();
            umc.setDefaultVisibility("global");
            umc.setMaxRecallEntries(100);
            umc.setMaxEntriesPerUser(1000);
            umc.setOnCapReached("reject");
            umc.setRecallOrder("most_relevant");
            umc.setAutoRecallCategories(java.util.List.of("preference"));
            umc.setGuardrails(new AgentConfiguration.Guardrails());
            umc.setDream(new AgentConfiguration.DreamConfig());
            assertEquals("global", umc.getDefaultVisibility());
            assertEquals(100, umc.getMaxRecallEntries());
        }
    }

    @Nested
    @DisplayName("Guardrails")
    class GuardrailsTest {

        @Test
        void defaults() {
            var g = new AgentConfiguration.Guardrails();
            assertEquals(100, g.getMaxKeyLength());
            assertEquals(1000, g.getMaxValueLength());
            assertEquals(10, g.getMaxWritesPerTurn());
            assertEquals(3, g.getAllowedCategories().size());
        }

        @Test
        void setters() {
            var g = new AgentConfiguration.Guardrails();
            g.setMaxKeyLength(50);
            g.setMaxValueLength(500);
            g.setMaxWritesPerTurn(5);
            g.setAllowedCategories(java.util.List.of("fact"));
            assertEquals(50, g.getMaxKeyLength());
            assertEquals(500, g.getMaxValueLength());
            assertEquals(5, g.getMaxWritesPerTurn());
            assertEquals(1, g.getAllowedCategories().size());
        }
    }

    @Nested
    @DisplayName("DreamConfig")
    class DreamConfigTest {

        @Test
        void defaults() {
            var dc = new AgentConfiguration.DreamConfig();
            assertFalse(dc.isEnabled());
            assertEquals("0 3 * * *", dc.getSchedule());
            assertTrue(dc.isDetectContradictions());
            assertEquals("keep_newest", dc.getContradictionResolution());
            assertEquals(90, dc.getPruneStaleAfterDays());
            assertFalse(dc.isSummarizeInteractions());
            assertEquals("anthropic", dc.getLlmProvider());
            assertEquals("claude-sonnet-4-6", dc.getLlmModel());
            assertEquals(5.0, dc.getMaxCostPerRun());
            assertEquals(50, dc.getBatchSize());
            assertEquals(1000, dc.getMaxUsersPerRun());
        }

        @Test
        void setters() {
            var dc = new AgentConfiguration.DreamConfig();
            dc.setEnabled(true);
            dc.setSchedule("0 * * * *");
            dc.setDetectContradictions(false);
            dc.setContradictionResolution("keep_oldest");
            dc.setPruneStaleAfterDays(30);
            dc.setSummarizeInteractions(true);
            dc.setLlmProvider("openai");
            dc.setLlmModel("gpt-4o");
            dc.setMaxCostPerRun(10.0);
            dc.setBatchSize(100);
            dc.setMaxUsersPerRun(500);
            assertTrue(dc.isEnabled());
            assertEquals(10.0, dc.getMaxCostPerRun());
        }
    }

    @Nested
    @DisplayName("SecurityConfig setters")
    class SecurityConfigSettersTest {

        @Test
        void setters() {
            var sc = new AgentConfiguration.SecurityConfig();
            sc.setSignInterAgentMessages(true);
            sc.setSignMcpInvocations(true);
            sc.setRequirePeerVerification(true);
            assertTrue(sc.isSignInterAgentMessages());
            assertTrue(sc.isSignMcpInvocations());
            assertTrue(sc.isRequirePeerVerification());
        }
    }
}
