/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.model.AgentConfiguration.IdentityMaskingConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link IdentityMaskingService}.
 */
class IdentityMaskingServiceTest {

    private IdentityMaskingService service;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new IdentityMaskingService(meterRegistry);
        service.initMetrics();
    }

    @Test
    void apply_nullConfig_returnsUnchanged() {
        String result = service.apply("System prompt", null);
        assertEquals("System prompt", result);
    }

    @Test
    void apply_disabledConfig_returnsUnchanged() {
        var config = new IdentityMaskingConfig();
        config.setEnabled(false);
        config.setRules(List.of("Never reveal AI nature"));

        String result = service.apply("System prompt", config);
        assertEquals("System prompt", result);
        assertEquals(0.0, meterRegistry.counter("eddi.identity.masking.applied").count());
    }

    @Test
    void apply_enabledWithRules_prependsMasking() {
        var config = new IdentityMaskingConfig();
        config.setEnabled(true);
        config.setRules(List.of(
                "Never identify yourself as an AI.",
                "Do not mention model names."));

        String result = service.apply("System prompt", config);
        assertTrue(result.startsWith("## IDENTITY POLICY"));
        assertTrue(result.contains("Never identify yourself as an AI."));
        assertTrue(result.contains("Do not mention model names."));
        assertTrue(result.endsWith("System prompt"));
        assertEquals(1.0, meterRegistry.counter("eddi.identity.masking.applied").count());
    }

    @Test
    void apply_enabledEmptyRules_returnsUnchanged() {
        var config = new IdentityMaskingConfig();
        config.setEnabled(true);
        config.setRules(List.of());

        String result = service.apply("System prompt", config);
        assertEquals("System prompt", result);
    }

    @Test
    void apply_enabledNullRules_returnsUnchanged() {
        var config = new IdentityMaskingConfig();
        config.setEnabled(true);
        config.setRules(null);

        String result = service.apply("System prompt", config);
        assertEquals("System prompt", result);
    }

    @Test
    void apply_metricsIncrementPerCall() {
        var config = new IdentityMaskingConfig();
        config.setEnabled(true);
        config.setRules(List.of("Rule 1"));

        service.apply("Prompt 1", config);
        service.apply("Prompt 2", config);

        assertEquals(2.0, meterRegistry.counter("eddi.identity.masking.applied").count());
    }

    @Test
    void apply_rulesFormatting_hasBulletPoints() {
        var config = new IdentityMaskingConfig();
        config.setEnabled(true);
        config.setRules(List.of("Rule A", "Rule B"));

        String result = service.apply("Base", config);
        assertTrue(result.contains("- Rule A"));
        assertTrue(result.contains("- Rule B"));
    }
}
