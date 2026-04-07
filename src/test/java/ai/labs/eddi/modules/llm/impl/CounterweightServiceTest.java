package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.model.LlmConfiguration.CounterweightConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CounterweightService}.
 */
class CounterweightServiceTest {

    private CounterweightService service;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new CounterweightService(meterRegistry);
    }

    @Test
    void resolveInstructions_nullConfig_returnsEmpty() {
        assertEquals("", service.resolveInstructions(null));
    }

    @Test
    void resolveInstructions_disabledConfig_returnsEmpty() {
        var config = new CounterweightConfig();
        config.setEnabled(false);
        config.setLevel("strict");
        assertEquals("", service.resolveInstructions(config));
    }

    @Test
    void resolveInstructions_normalLevel_returnsEmpty() {
        var config = new CounterweightConfig();
        config.setEnabled(true);
        config.setLevel("normal");
        assertEquals("", service.resolveInstructions(config));
    }

    @Test
    void resolveInstructions_cautiousLevel_returnsCautiousText() {
        var config = new CounterweightConfig();
        config.setEnabled(true);
        config.setLevel("cautious");
        String result = service.resolveInstructions(config);
        assertFalse(result.isEmpty());
        assertTrue(result.contains("caution"));
        assertEquals(CounterweightService.CAUTIOUS_INSTRUCTIONS, result);
    }

    @Test
    void resolveInstructions_strictLevel_returnsStrictText() {
        var config = new CounterweightConfig();
        config.setEnabled(true);
        config.setLevel("strict");
        String result = service.resolveInstructions(config);
        assertFalse(result.isEmpty());
        assertTrue(result.contains("MUST"));
        assertEquals(CounterweightService.STRICT_INSTRUCTIONS, result);
    }

    @Test
    void resolveInstructions_customInstructions_overridesLevel() {
        var config = new CounterweightConfig();
        config.setEnabled(true);
        config.setLevel("strict");
        config.setInstructions(List.of("Be very careful.", "Always double check."));
        String result = service.resolveInstructions(config);
        assertEquals("Be very careful.\nAlways double check.", result);
        // Should NOT contain strict text since custom instructions override
        assertNotEquals(CounterweightService.STRICT_INSTRUCTIONS, result);
    }

    @Test
    void resolveInstructions_unknownLevel_returnsEmpty() {
        var config = new CounterweightConfig();
        config.setEnabled(true);
        config.setLevel("unknown_level");
        assertEquals("", service.resolveInstructions(config));
    }

    @Test
    void resolveInstructions_nullLevel_returnsEmpty() {
        var config = new CounterweightConfig();
        config.setEnabled(true);
        config.setLevel(null);
        assertEquals("", service.resolveInstructions(config));
    }

    @Test
    void resolveInstructions_cautiousLevel_incrementsMetric() {
        var config = new CounterweightConfig();
        config.setEnabled(true);
        config.setLevel("cautious");
        service.resolveInstructions(config);

        var counter = meterRegistry.find("eddi.counterweight.activation.count")
                .tag("level", "cautious").counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
    }

    @Test
    void resolveInstructions_caseInsensitive() {
        var config = new CounterweightConfig();
        config.setEnabled(true);
        config.setLevel("CAUTIOUS");
        String result = service.resolveInstructions(config);
        assertEquals(CounterweightService.CAUTIOUS_INSTRUCTIONS, result);
    }
}
