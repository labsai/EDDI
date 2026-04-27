package ai.labs.eddi.configs.channels.rest;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RestChannelIntegrationStore#validateConfiguration}.
 * Covers all validation rules: name, channelType, targets, defaultTarget,
 * trigger uniqueness, null/blank triggers, and observeMode rejection.
 */
class RestChannelIntegrationStoreValidationTest {

    private RestChannelIntegrationStore store;
    private ChannelIntegrationConfiguration config;

    @BeforeEach
    void setUp() {
        // Construct with null dependencies — we only call validateConfiguration()
        store = new RestChannelIntegrationStore(null, null);
        config = validConfig();
    }

    /**
     * Produces a minimal valid config so tests can mutate one field at a time.
     */
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

    // ─── Happy path ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("valid config passes validation without exception")
    void validConfigPasses() {
        assertDoesNotThrow(() -> store.validateConfiguration(config));
    }

    // ─── Name ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Name validation")
    class NameValidation {

        @Test
        @DisplayName("null name → BadRequest")
        void nullName() {
            config.setName(null);
            var ex = assertThrows(BadRequestException.class,
                    () -> store.validateConfiguration(config));
            assertTrue(ex.getMessage().contains("name"));
        }

        @Test
        @DisplayName("blank name → BadRequest")
        void blankName() {
            config.setName("   ");
            assertThrows(BadRequestException.class,
                    () -> store.validateConfiguration(config));
        }
    }

    // ─── Channel type ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Channel type validation")
    class ChannelTypeValidation {

        @Test
        @DisplayName("null channelType → BadRequest")
        void nullChannelType() {
            config.setChannelType(null);
            assertThrows(BadRequestException.class,
                    () -> store.validateConfiguration(config));
        }

        @Test
        @DisplayName("unknown channelType → BadRequest with registered types")
        void unknownChannelType() {
            config.setChannelType("telegram");
            var ex = assertThrows(BadRequestException.class,
                    () -> store.validateConfiguration(config));
            assertTrue(ex.getMessage().contains("telegram"));
            assertTrue(ex.getMessage().contains("Registered types"));
        }

        @Test
        @DisplayName("'slack' is accepted")
        void slackAccepted() {
            config.setChannelType("slack");
            assertDoesNotThrow(() -> store.validateConfiguration(config));
        }
    }

    // ─── Targets ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Target validation")
    class TargetValidation {

        @Test
        @DisplayName("null targets → BadRequest")
        void nullTargets() {
            config.setTargets(null);
            assertThrows(BadRequestException.class,
                    () -> store.validateConfiguration(config));
        }

        @Test
        @DisplayName("empty targets → BadRequest")
        void emptyTargets() {
            config.setTargets(List.of());
            assertThrows(BadRequestException.class,
                    () -> store.validateConfiguration(config));
        }

        @Test
        @DisplayName("target with null name → BadRequest")
        void targetNullName() {
            // Include a valid target so the default-target check passes;
            // the null-name target must be second to reach the per-target loop
            var validTarget = new ChannelTarget();
            validTarget.setName("x");
            validTarget.setTargetId("agent-valid");
            validTarget.setTriggers(List.of("x"));

            var badTarget = new ChannelTarget();
            badTarget.setName(null);
            badTarget.setTargetId("agent-x");
            badTarget.setTriggers(List.of("y"));

            config.setTargets(List.of(validTarget, badTarget));
            config.setDefaultTargetName("x");

            var ex = assertThrows(BadRequestException.class,
                    () -> store.validateConfiguration(config));
            assertTrue(ex.getMessage().contains("name"));
        }

        @Test
        @DisplayName("target with null targetId → BadRequest")
        void targetNullTargetId() {
            var target = new ChannelTarget();
            target.setName("support");
            target.setTargetId(null);
            target.setTriggers(List.of("support"));
            config.setTargets(List.of(target));

            var ex = assertThrows(BadRequestException.class,
                    () -> store.validateConfiguration(config));
            assertTrue(ex.getMessage().contains("targetId"));
        }

        @Test
        @DisplayName("duplicate target names (case-insensitive) → BadRequest")
        void duplicateTargetNames() {
            var t1 = new ChannelTarget();
            t1.setName("Support");
            t1.setTargetId("agent-1");
            t1.setTriggers(List.of("assist"));

            var t2 = new ChannelTarget();
            t2.setName("support"); // same name, different case
            t2.setTargetId("agent-2");
            t2.setTriggers(List.of("review"));

            config.setTargets(List.of(t1, t2));
            config.setDefaultTargetName("Support");

            var ex = assertThrows(BadRequestException.class,
                    () -> store.validateConfiguration(config));
            assertTrue(ex.getMessage().contains("Duplicate target name"));
        }
    }

    // ─── Default target ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Default target validation")
    class DefaultTargetValidation {

        @Test
        @DisplayName("null defaultTargetName → BadRequest")
        void nullDefaultTarget() {
            config.setDefaultTargetName(null);
            assertThrows(BadRequestException.class,
                    () -> store.validateConfiguration(config));
        }

        @Test
        @DisplayName("defaultTargetName not matching any target → BadRequest")
        void defaultTargetMismatch() {
            config.setDefaultTargetName("nonexistent");
            var ex = assertThrows(BadRequestException.class,
                    () -> store.validateConfiguration(config));
            assertTrue(ex.getMessage().contains("nonexistent"));
        }
    }

    // ─── Trigger validation ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Trigger validation")
    class TriggerValidation {

        @Test
        @DisplayName("duplicate trigger across targets → BadRequest")
        void duplicateTrigger() {
            var t1 = new ChannelTarget();
            t1.setName("alpha");
            t1.setTargetId("agent-1");
            t1.setTriggers(List.of("support"));

            var t2 = new ChannelTarget();
            t2.setName("beta");
            t2.setTargetId("agent-2");
            t2.setTriggers(List.of("SUPPORT")); // case-insensitive dup

            config.setTargets(List.of(t1, t2));
            config.setDefaultTargetName("alpha");

            var ex = assertThrows(BadRequestException.class,
                    () -> store.validateConfiguration(config));
            assertTrue(ex.getMessage().toLowerCase().contains("duplicate"));
        }

        @Test
        @DisplayName("null trigger in list → BadRequest")
        void nullTrigger() {
            var triggers = new ArrayList<String>();
            triggers.add("support");
            triggers.add(null);

            var target = new ChannelTarget();
            target.setName("support");
            target.setTargetId("agent-abc");
            target.setTriggers(triggers);
            config.setTargets(List.of(target));

            var ex = assertThrows(BadRequestException.class,
                    () -> store.validateConfiguration(config));
            assertTrue(ex.getMessage().contains("null or blank"));
        }

        @Test
        @DisplayName("blank trigger in list → BadRequest")
        void blankTrigger() {
            var target = new ChannelTarget();
            target.setName("support");
            target.setTargetId("agent-abc");
            target.setTriggers(List.of("support", "   "));
            config.setTargets(List.of(target));

            assertThrows(BadRequestException.class,
                    () -> store.validateConfiguration(config));
        }
    }

    // ─── Observe mode ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Observe mode validation")
    class ObserveModeValidation {

        @Test
        @DisplayName("observeMode=true → BadRequest (not yet implemented)")
        void observeModeRejected() {
            var target = new ChannelTarget();
            target.setName("support");
            target.setTargetId("agent-abc");
            target.setTriggers(List.of("support"));
            target.setObserveMode(true);
            config.setTargets(List.of(target));

            var ex = assertThrows(BadRequestException.class,
                    () -> store.validateConfiguration(config));
            assertTrue(ex.getMessage().contains("observeMode"));
        }

        @Test
        @DisplayName("observeMode=false → passes")
        void observeModeFalse() {
            var target = new ChannelTarget();
            target.setName("support");
            target.setTargetId("agent-abc");
            target.setTriggers(List.of("support"));
            target.setObserveMode(false);
            config.setTargets(List.of(target));

            assertDoesNotThrow(() -> store.validateConfiguration(config));
        }
    }

    // ─── Reserved triggers ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Reserved trigger validation")
    class ReservedTriggerValidation {

        @Test
        @DisplayName("trigger 'help' → BadRequest (reserved)")
        void helpTriggerRejected() {
            var target = new ChannelTarget();
            target.setName("support");
            target.setTargetId("agent-abc");
            target.setTriggers(List.of("help"));
            config.setTargets(List.of(target));

            var ex = assertThrows(BadRequestException.class,
                    () -> store.validateConfiguration(config));
            assertTrue(ex.getMessage().contains("reserved"));
        }

        @Test
        @DisplayName("trigger 'HELP' → BadRequest (case-insensitive)")
        void helpUpperCaseRejected() {
            var target = new ChannelTarget();
            target.setName("support");
            target.setTargetId("agent-abc");
            target.setTriggers(List.of("HELP"));
            config.setTargets(List.of(target));

            assertThrows(BadRequestException.class,
                    () -> store.validateConfiguration(config));
        }

        @Test
        @DisplayName("trigger 'helper' → passes (not reserved)")
        void helperAllowed() {
            var target = new ChannelTarget();
            target.setName("support");
            target.setTargetId("agent-abc");
            target.setTriggers(List.of("helper"));
            config.setTargets(List.of(target));

            assertDoesNotThrow(() -> store.validateConfiguration(config));
        }
    }
}
