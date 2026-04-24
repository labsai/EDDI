package ai.labs.eddi.configs.channels.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the channel integration model POJOs:
 * {@link ChannelIntegrationConfiguration}, {@link ChannelTarget}, and
 * {@link ObserveConfig}.
 */
class ChannelModelTest {

    // ─── ChannelIntegrationConfiguration ──────────────────────────────────────

    @Nested
    @DisplayName("ChannelIntegrationConfiguration")
    class IntegrationConfigTest {

        @Test
        @DisplayName("default state — name/channelType null, collections empty")
        void defaultState() {
            var cfg = new ChannelIntegrationConfiguration();
            assertNull(cfg.getName());
            assertNull(cfg.getChannelType());
            assertNull(cfg.getDefaultTargetName());
            assertNotNull(cfg.getPlatformConfig());
            assertTrue(cfg.getPlatformConfig().isEmpty());
            assertNotNull(cfg.getTargets());
            assertTrue(cfg.getTargets().isEmpty());
        }

        @Test
        @DisplayName("setters and getters round-trip")
        void settersAndGetters() {
            var cfg = new ChannelIntegrationConfiguration();
            cfg.setName("My Hub");
            cfg.setChannelType("slack");
            cfg.setDefaultTargetName("support");
            cfg.setPlatformConfig(Map.of("botToken", "xoxb-123"));

            var target = new ChannelTarget();
            target.setName("support");
            cfg.setTargets(List.of(target));

            assertEquals("My Hub", cfg.getName());
            assertEquals("slack", cfg.getChannelType());
            assertEquals("support", cfg.getDefaultTargetName());
            assertEquals("xoxb-123", cfg.getPlatformConfig().get("botToken"));
            assertEquals(1, cfg.getTargets().size());
        }
    }

    // ─── ChannelTarget ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ChannelTarget")
    class TargetTest {

        @Test
        @DisplayName("default state — AGENT type, empty triggers")
        void defaultState() {
            var target = new ChannelTarget();
            assertEquals(ChannelTarget.TargetType.AGENT, target.getType());
            assertNotNull(target.getTriggers());
            assertTrue(target.getTriggers().isEmpty());
            assertNull(target.getName());
            assertNull(target.getTargetId());
            assertFalse(target.isObserveMode());
            assertNull(target.getObserveConfig());
        }

        @Test
        @DisplayName("setters and getters round-trip")
        void settersAndGetters() {
            var target = new ChannelTarget();
            target.setName("architect");
            target.setTargetId("agent-123");
            target.setType(ChannelTarget.TargetType.GROUP);
            target.setTriggers(List.of("arch", "architect"));
            target.setObserveMode(true);

            var observeConfig = new ObserveConfig();
            target.setObserveConfig(observeConfig);

            assertEquals("architect", target.getName());
            assertEquals("agent-123", target.getTargetId());
            assertEquals(ChannelTarget.TargetType.GROUP, target.getType());
            assertEquals(2, target.getTriggers().size());
            assertTrue(target.isObserveMode());
            assertNotNull(target.getObserveConfig());
        }

        @Test
        @DisplayName("TargetType enum values")
        void targetTypeValues() {
            assertEquals(2, ChannelTarget.TargetType.values().length);
            assertNotNull(ChannelTarget.TargetType.valueOf("AGENT"));
            assertNotNull(ChannelTarget.TargetType.valueOf("GROUP"));
        }
    }

    // ─── ObserveConfig ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ObserveConfig")
    class ObserveConfigTest {

        @Test
        @DisplayName("default values — sensible production defaults")
        void defaultValues() {
            var cfg = new ObserveConfig();
            assertNotNull(cfg.getTriggerKeywords());
            assertTrue(cfg.getTriggerKeywords().isEmpty());
            assertNotNull(cfg.getTriggerMimeTypes());
            assertTrue(cfg.getTriggerMimeTypes().isEmpty());
            assertEquals(60, cfg.getCooldownSeconds());
            assertEquals(50, cfg.getMaxDailyResponses());
            assertEquals(5.0, cfg.getMaxCostPerDay(), 0.01);
        }

        @Test
        @DisplayName("setters and getters round-trip")
        void settersAndGetters() {
            var cfg = new ObserveConfig();
            cfg.setTriggerKeywords(List.of("urgent", "help"));
            cfg.setTriggerMimeTypes(List.of("application/pdf"));
            cfg.setCooldownSeconds(120);
            cfg.setMaxDailyResponses(100);
            cfg.setMaxCostPerDay(10.50);

            assertEquals(List.of("urgent", "help"), cfg.getTriggerKeywords());
            assertEquals(List.of("application/pdf"), cfg.getTriggerMimeTypes());
            assertEquals(120, cfg.getCooldownSeconds());
            assertEquals(100, cfg.getMaxDailyResponses());
            assertEquals(10.50, cfg.getMaxCostPerDay(), 0.01);
        }
    }
}
