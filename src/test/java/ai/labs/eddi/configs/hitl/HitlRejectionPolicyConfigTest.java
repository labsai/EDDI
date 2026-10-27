/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.hitl;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.engine.lifecycle.model.HitlRevisionPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code hitlConfig.onRejection} / {@code maxRevisionsPerTurn}: defaults that
 * leave a stored agent unchanged, case-insensitive values, save-time bounds.
 */
@DisplayName("hitlConfig.onRejection and maxRevisionsPerTurn")
class HitlRejectionPolicyConfigTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("a config saved before 6.6 loads as SKIP with no revision policy")
    void legacyConfigIsSkip() throws Exception {
        var hitl = mapper.readValue("{\"approvalTimeout\":\"PT5M\",\"timeoutPolicy\":\"AUTO_REJECT\"}", AgentConfiguration.HitlConfig.class);

        assertEquals(HitlRejectionAction.SKIP, hitl.getOnRejection());
        assertNull(hitl.getMaxRevisionsPerTurn());
        assertEquals(AgentConfiguration.HitlConfig.DEFAULT_MAX_REVISIONS_PER_TURN, hitl.effectiveMaxRevisionsPerTurn());
        assertNull(HitlRevisionPolicy.of(hitl), "SKIP carries no policy, so nothing is captured");
        assertNull(HitlRevisionPolicy.of(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"REVISE", "revise", " Revise "})
    @DisplayName("onRejection is case-insensitive")
    void caseInsensitive(String value) throws Exception {
        var hitl = mapper.readValue("{\"onRejection\":\"" + value + "\"}", AgentConfiguration.HitlConfig.class);

        assertEquals(HitlRejectionAction.REVISE, hitl.getOnRejection());
        var policy = HitlRevisionPolicy.of(hitl);
        assertTrue(policy.revise());
        assertEquals(3, policy.maxRevisionsPerTurn());
    }

    @Test
    @DisplayName("onRejection: null keeps the default; an unknown value fails to load")
    void nullAndUnknown() throws Exception {
        assertEquals(HitlRejectionAction.SKIP, mapper.readValue("{\"onRejection\":null}", AgentConfiguration.HitlConfig.class).getOnRejection());
        assertThrows(Exception.class, () -> mapper.readValue("{\"onRejection\":\"redo\"}", AgentConfiguration.HitlConfig.class));
    }

    @Test
    @DisplayName("maxRevisionsPerTurn is the configured bound and is serialized, the derived value is not")
    void maxIsCarried() throws Exception {
        var hitl = mapper.readValue("{\"onRejection\":\"REVISE\",\"maxRevisionsPerTurn\":1}", AgentConfiguration.HitlConfig.class);

        assertEquals(1, HitlRevisionPolicy.of(hitl).maxRevisionsPerTurn());
        String json = mapper.writeValueAsString(hitl);
        assertTrue(json.contains("\"maxRevisionsPerTurn\":1"), json);
        assertTrue(json.contains("\"onRejection\":\"REVISE\""), json);
        assertTrue(!json.contains("effectiveMaxRevisionsPerTurn"), json);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 11, -1})
    @DisplayName("save time refuses a bound outside 1..10")
    void boundsRefused(int value) {
        var hitl = new AgentConfiguration.HitlConfig();
        hitl.setOnRejection(HitlRejectionAction.REVISE);
        hitl.setMaxRevisionsPerTurn(value);

        var e = assertThrows(IllegalArgumentException.class, () -> HitlConfigValidation.validate(hitl));
        assertTrue(e.getMessage().contains("hitlConfig.maxRevisionsPerTurn"), e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10})
    @DisplayName("save time accepts the bounds")
    void boundsAccepted(int value) {
        var hitl = new AgentConfiguration.HitlConfig();
        hitl.setMaxRevisionsPerTurn(value);

        assertDoesNotThrow(() -> HitlConfigValidation.validate(hitl));
    }
}
