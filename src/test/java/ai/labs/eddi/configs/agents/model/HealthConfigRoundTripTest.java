/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents.model;

import ai.labs.eddi.secrets.sanitize.SecretScrubber;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code healthConfig} travels with the agent: export runs the agent JSON
 * through {@link SecretScrubber} and import (and agent sync) reads it back with
 * Jackson — so the field must survive both untouched, with nothing in it that
 * looks like a credential.
 */
@DisplayName("healthConfig — export (SecretScrubber) and import (Jackson) round trip")
class HealthConfigRoundTripTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    @DisplayName("every field survives the scrubber and comes back as it was")
    void roundTrip() throws Exception {
        HealthConfig health = new HealthConfig();
        Map<String, String> outcomes = new LinkedHashMap<>();
        outcomes.put("order_confirmed", "RESOLVED");
        outcomes.put("handoff_human", "ESCALATED");
        health.setOutcomes(outcomes);
        health.setAssumeResolvedOnSilence(false);
        health.setInactivityWindowHours(new LinkedHashMap<>(Map.of("email", 96)));
        health.setSelfReportTool(true);
        AgentConfiguration agent = new AgentConfiguration();
        agent.setHealthConfig(health);

        String json = MAPPER.writeValueAsString(agent);
        SecretScrubber scrubber = new SecretScrubber(MAPPER);
        assertFalse(scrubber.containsCredential(json));
        String exported = scrubber.scrubJson(json);
        AgentConfiguration imported = MAPPER.readValue(exported, AgentConfiguration.class);

        HealthConfig back = imported.getHealthConfig();
        assertEquals(outcomes, back.getOutcomes());
        assertFalse(back.isAssumeResolvedOnSilence());
        assertEquals(Map.of("email", 96), back.getInactivityWindowHours());
        assertTrue(back.isSelfReportTool());
    }

    @Test
    @DisplayName("an agent exported before healthConfig existed imports without one — every default applies")
    void absent() throws Exception {
        AgentConfiguration imported = MAPPER.readValue("{\"workflows\":[]}", AgentConfiguration.class);
        assertNull(imported.getHealthConfig());
        HealthConfig partial = MAPPER.readValue("{\"outcomes\":{\"done\":\"RESOLVED\"}}", HealthConfig.class);
        assertTrue(partial.isAssumeResolvedOnSilence(), "absent means the default, true");
        assertFalse(partial.isSelfReportTool());
    }
}
