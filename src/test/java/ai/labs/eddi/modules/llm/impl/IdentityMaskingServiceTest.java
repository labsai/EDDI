package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.model.LlmConfiguration.IdentityMaskingConfig;
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
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new IdentityMaskingService(meterRegistry);
    }

    @Test
    void resolveInstructions_nullConfig_returnsEmpty() {
        assertEquals("", service.resolveInstructions(null));
    }

    @Test
    void resolveInstructions_disabledConfig_returnsEmpty() {
        var config = new IdentityMaskingConfig();
        config.setEnabled(false);
        config.setDisplayName("Agent X");
        assertEquals("", service.resolveInstructions(config));
    }

    @Test
    void resolveInstructions_displayNameOnly() {
        var config = new IdentityMaskingConfig();
        config.setEnabled(true);
        config.setDisplayName("EDDI Assistant");
        String result = service.resolveInstructions(config);
        assertTrue(result.contains("EDDI Assistant"));
        assertTrue(result.contains("Your name is"));
    }

    @Test
    void resolveInstructions_concealModelIdentityOnly() {
        var config = new IdentityMaskingConfig();
        config.setEnabled(true);
        config.setConcealModelIdentity(true);
        String result = service.resolveInstructions(config);
        assertTrue(result.contains("Do not reveal"));
    }

    @Test
    void resolveInstructions_customPersonaInstructions() {
        var config = new IdentityMaskingConfig();
        config.setEnabled(true);
        config.setPersonaInstructions(List.of("Speak formally", "Use British English"));
        String result = service.resolveInstructions(config);
        assertTrue(result.contains("Speak formally"));
        assertTrue(result.contains("British English"));
    }

    @Test
    void resolveInstructions_allOptionsEnabled() {
        var config = new IdentityMaskingConfig();
        config.setEnabled(true);
        config.setDisplayName("Jarvis");
        config.setConcealModelIdentity(true);
        config.setPersonaInstructions(List.of("Be concise"));
        String result = service.resolveInstructions(config);
        assertTrue(result.contains("Jarvis"));
        assertTrue(result.contains("Do not reveal"));
        assertTrue(result.contains("Be concise"));
    }

    @Test
    void resolveInstructions_enabledButEmpty_returnsEmpty() {
        var config = new IdentityMaskingConfig();
        config.setEnabled(true);
        // No display name, no concealment, no persona instructions
        assertEquals("", service.resolveInstructions(config));
    }

    @Test
    void resolveInstructions_incrementsMetric() {
        var config = new IdentityMaskingConfig();
        config.setEnabled(true);
        config.setDisplayName("Test Agent");
        service.resolveInstructions(config);

        var counter = meterRegistry.find("eddi.identity.masking.activation.count").counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
    }
}
