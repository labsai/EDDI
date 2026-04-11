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
}
