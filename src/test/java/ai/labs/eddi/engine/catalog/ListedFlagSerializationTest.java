/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.catalog;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ZIP export/import and agent sync carry agent and group configurations as
 * their JSON, so the {@code listed} flag survives them exactly when it survives
 * a JSON round trip — and a document written before the flag existed must read
 * as unlisted.
 */
class ListedFlagSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("an agent's listed flag survives a JSON round trip and defaults to false")
    void agentRoundTrip() throws Exception {
        var config = new AgentConfiguration();
        assertFalse(config.getListed());
        config.setListed(true);

        String json = mapper.writeValueAsString(config);
        assertTrue(json.contains("\"listed\":true"));
        assertEquals(Boolean.TRUE, mapper.readValue(json, AgentConfiguration.class).getListed());

        assertFalse(mapper.readValue("{\"workflows\":[]}", AgentConfiguration.class).getListed());
    }

    @Test
    @DisplayName("a group's listed flag survives a JSON round trip and defaults to false")
    void groupRoundTrip() throws Exception {
        var config = new AgentGroupConfiguration();
        assertFalse(config.getListed());
        config.setListed(true);

        String json = mapper.writeValueAsString(config);
        assertEquals(Boolean.TRUE, mapper.readValue(json, AgentGroupConfiguration.class).getListed());

        assertFalse(mapper.readValue("{\"name\":\"team\"}", AgentGroupConfiguration.class).getListed());
    }
}
