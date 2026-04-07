package ai.labs.eddi.modules.llm.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DeploymentContextService}.
 */
class DeploymentContextServiceTest {

    @Test
    void getAutoCounterweightLevel_production_returnsCautious() {
        var service = new DeploymentContextService("production");
        assertEquals("cautious", service.getAutoCounterweightLevel());
    }

    @Test
    void getAutoCounterweightLevel_staging_returnsCautious() {
        var service = new DeploymentContextService("staging");
        assertEquals("cautious", service.getAutoCounterweightLevel());
    }

    @Test
    void getAutoCounterweightLevel_development_returnsNull() {
        var service = new DeploymentContextService("development");
        assertNull(service.getAutoCounterweightLevel());
    }

    @Test
    void getAutoCounterweightLevel_caseInsensitive() {
        var service = new DeploymentContextService("PRODUCTION");
        assertEquals("cautious", service.getAutoCounterweightLevel());
    }

    @Test
    void getAutoCounterweightLevel_unknownEnv_returnsNull() {
        var service = new DeploymentContextService("testing");
        assertNull(service.getAutoCounterweightLevel());
    }

    @Test
    void getEnvironment_returnsConfiguredValue() {
        var service = new DeploymentContextService("production");
        assertEquals("production", service.getEnvironment());
    }
}
