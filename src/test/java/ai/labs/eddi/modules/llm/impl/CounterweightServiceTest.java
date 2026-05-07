/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.model.LlmConfiguration.CounterweightConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CounterweightService}.
 */
class CounterweightServiceTest {

    private CounterweightService service;
    private MeterRegistry meterRegistry;
    private PromptSnippetService promptSnippetService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        promptSnippetService = mock(PromptSnippetService.class);
        when(promptSnippetService.getAll()).thenReturn(Collections.emptyMap());
        service = new CounterweightService(promptSnippetService, meterRegistry);
        service.initMetrics();
    }

    @Test
    void apply_nullConfig_returnsUnchanged() {
        String result = service.apply("Hello system", null, null);
        assertEquals("Hello system", result);
    }

    @Test
    void apply_disabledConfig_returnsUnchanged() {
        var config = new CounterweightConfig();
        config.setEnabled(false);
        config.setLevel("strict");

        String result = service.apply("Hello system", config, null);
        assertEquals("Hello system", result);
    }

    @Test
    void apply_normalLevel_noInjection() {
        var config = new CounterweightConfig();
        config.setEnabled(true);
        config.setLevel("normal");

        String result = service.apply("Hello system", config, null);
        assertEquals("Hello system", result);
        assertEquals(1.0, meterRegistry.counter("eddi.counterweight.activation.count", "level", "normal").count());
    }

    @Test
    void apply_cautiousLevel_suffixPlacement() {
        var config = new CounterweightConfig();
        config.setEnabled(true);
        config.setLevel("cautious");
        config.setPlacement("suffix");

        String result = service.apply("Base prompt", config, null);
        assertTrue(result.startsWith("Base prompt"));
        assertTrue(result.contains("BEHAVIORAL GUIDELINES"));
        assertTrue(result.contains("clarification"));
        assertEquals(1.0, meterRegistry.counter("eddi.counterweight.activation.count", "level", "cautious").count());
    }

    @Test
    void apply_cautiousLevel_prefixPlacement() {
        var config = new CounterweightConfig();
        config.setEnabled(true);
        config.setLevel("cautious");
        config.setPlacement("prefix");

        String result = service.apply("Base prompt", config, null);
        assertTrue(result.endsWith("Base prompt"));
        assertTrue(result.contains("BEHAVIORAL GUIDELINES"));
    }

    @Test
    void apply_strictLevel_containsOneStepAtATime() {
        var config = new CounterweightConfig();
        config.setEnabled(true);
        config.setLevel("strict");

        String result = service.apply("Base prompt", config, null);
        assertTrue(result.contains("STRICT MODE"));
        assertTrue(result.contains("one step at a time"));
        assertEquals(1.0, meterRegistry.counter("eddi.counterweight.activation.count", "level", "strict").count());
    }

    @Test
    void apply_strictLevel_scheduledChannel_downgradesCautious() {
        var config = new CounterweightConfig();
        config.setEnabled(true);
        config.setLevel("strict");

        String result = service.apply("Base prompt", config, "scheduled");
        assertFalse(result.contains("STRICT MODE"), "Strict should be downgraded for scheduled agents");
        assertTrue(result.contains("BEHAVIORAL GUIDELINES"));
        assertFalse(result.contains("one step at a time"));
        assertEquals(1.0, meterRegistry.counter("eddi.counterweight.strict.downgraded").count());
        assertEquals(1.0, meterRegistry.counter("eddi.counterweight.activation.count", "level", "cautious").count());
    }

    @Test
    void apply_customInstructions_overridePreset() {
        var config = new CounterweightConfig();
        config.setEnabled(true);
        config.setLevel("cautious");
        config.setCustomInstructions(List.of(
                "Always be polite.",
                "Never swear."));

        String result = service.apply("Base prompt", config, null);
        assertTrue(result.contains("Always be polite."));
        assertTrue(result.contains("Never swear."));
        // Custom instructions should NOT contain the preset text
        assertFalse(result.contains("Verify assumptions"));
    }

    @Test
    void apply_defaultPlacement_isSuffix() {
        var config = new CounterweightConfig();
        config.setEnabled(true);
        config.setLevel("cautious");
        // placement not set — should default to suffix

        String result = service.apply("Base prompt", config, null);
        assertTrue(result.startsWith("Base prompt"));
    }

    @Test
    void apply_emptySystemMessage_counterweightStillInjected() {
        var config = new CounterweightConfig();
        config.setEnabled(true);
        config.setLevel("cautious");

        String result = service.apply("", config, null);
        assertTrue(result.contains("BEHAVIORAL GUIDELINES"));
    }

    @Test
    void apply_nonScheduledChannel_noDowngrade() {
        var config = new CounterweightConfig();
        config.setEnabled(true);
        config.setLevel("strict");

        String result = service.apply("Base prompt", config, "web");
        assertTrue(result.contains("STRICT MODE"));
        assertEquals(0.0, meterRegistry.counter("eddi.counterweight.strict.downgraded").count());
    }

    @Test
    void apply_metrics_normalNotIncrementedForCautious() {
        var config = new CounterweightConfig();
        config.setEnabled(true);
        config.setLevel("cautious");

        service.apply("Base prompt", config, null);
        assertEquals(0.0, meterRegistry.counter("eddi.counterweight.activation.count", "level", "normal").count());
        assertEquals(1.0, meterRegistry.counter("eddi.counterweight.activation.count", "level", "cautious").count());
    }

    @Test
    void apply_caseInsensitiveLevel() {
        var config = new CounterweightConfig();
        config.setEnabled(true);
        config.setLevel("CAUTIOUS");

        String result = service.apply("Base prompt", config, null);
        assertTrue(result.contains("BEHAVIORAL GUIDELINES"));
    }

    @Test
    void apply_scheduledChannelTag_caseInsensitive() {
        var config = new CounterweightConfig();
        config.setEnabled(true);
        config.setLevel("strict");

        String result = service.apply("Base prompt", config, "SCHEDULED");
        assertFalse(result.contains("STRICT MODE"));
        assertEquals(1.0, meterRegistry.counter("eddi.counterweight.strict.downgraded").count());
    }

    @Test
    void apply_cautiousLevel_resolvesFromSnippetWhenConfigured() {
        when(promptSnippetService.getAll()).thenReturn(
                Map.of("counterweight-cautious", "## CUSTOM CAUTIOUS FROM SNIPPET\n- Be very careful."));

        var config = new CounterweightConfig();
        config.setEnabled(true);
        config.setLevel("cautious");

        String result = service.apply("Base prompt", config, null);
        assertTrue(result.contains("CUSTOM CAUTIOUS FROM SNIPPET"));
        assertTrue(result.contains("Be very careful."));
        // Should NOT contain the fallback text
        assertFalse(result.contains("Verify assumptions"));
    }

    @Test
    void apply_strictLevel_fallsBackWhenSnippetMissing() {
        // No snippets configured — should use fallback
        when(promptSnippetService.getAll()).thenReturn(Collections.emptyMap());

        var config = new CounterweightConfig();
        config.setEnabled(true);
        config.setLevel("strict");

        String result = service.apply("Base prompt", config, null);
        assertTrue(result.contains("STRICT MODE"));
        assertTrue(result.contains("one step at a time"));
    }
}
