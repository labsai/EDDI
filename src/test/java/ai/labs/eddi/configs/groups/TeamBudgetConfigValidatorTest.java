/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.CostPolicy;
import ai.labs.eddi.configs.groups.model.TeamBudgetConfig;
import ai.labs.eddi.configs.groups.model.TeamBudgetConfig.Oversight;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TeamBudgetConfigValidatorTest {

    private static AgentGroupConfiguration with(TeamBudgetConfig budget) {
        AgentGroupConfiguration config = new AgentGroupConfiguration();
        config.setTeamBudget(budget);
        return config;
    }

    @Test
    void absentBudgetIsValid() {
        assertDoesNotThrow(() -> TeamBudgetConfigValidator.validate("g1", with(null)));
        assertDoesNotThrow(() -> TeamBudgetConfigValidator.validate("g1", null));
    }

    @Test
    void validBudgetPasses() {
        assertDoesNotThrow(() -> TeamBudgetConfigValidator.validate("g1",
                with(new TeamBudgetConfig(35.0, CostPolicy.ABORT, new Oversight(List.of("a", "b"))))));
    }

    @Test
    void nonPositiveOrNonFiniteCapIsRejectedNotCoalesced() {
        for (Double bad : new Double[]{null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            var e = assertThrows(IllegalArgumentException.class,
                    () -> TeamBudgetConfigValidator.validate("g1", with(new TeamBudgetConfig(bad, null, null))), "cap " + bad);
            assertTrue(e.getMessage().contains("monthlyBudgetUsd"));
        }
    }

    @Test
    void oversightMustBeUniqueBoundedAndNotSelf() {
        assertThrows(IllegalArgumentException.class, () -> TeamBudgetConfigValidator.validate("g1",
                with(new TeamBudgetConfig(1.0, null, new Oversight(List.of("a", "a"))))));
        assertThrows(IllegalArgumentException.class, () -> TeamBudgetConfigValidator.validate("g1",
                with(new TeamBudgetConfig(1.0, null, new Oversight(List.of("g1"))))));
        List<String> many = new ArrayList<>();
        for (int i = 0; i <= TeamBudgetConfig.MAX_OVERSIGHT_TEAMS; i++) {
            many.add("t" + i);
        }
        assertThrows(IllegalArgumentException.class,
                () -> TeamBudgetConfigValidator.validate("g1", with(new TeamBudgetConfig(1.0, null, new Oversight(many)))));
        assertThrows(IllegalArgumentException.class, () -> TeamBudgetConfigValidator.validate("g1",
                with(new TeamBudgetConfig(1.0, null, new Oversight(java.util.Arrays.asList("a", " "))))));
    }

    @Test
    void createHasNoSelfIdYetSoOnlyDuplicatesAndBlanksApply() {
        assertDoesNotThrow(() -> TeamBudgetConfigValidator.validate(null,
                with(new TeamBudgetConfig(1.0, null, new Oversight(List.of("g1"))))));
    }

    @Test
    void roundTripsThroughJacksonAndOldDocumentsReadAsAbsent() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AgentGroupConfiguration config = with(new TeamBudgetConfig(35.0, CostPolicy.SYNTHESIZE_NOW, new Oversight(List.of("a"))));
        AgentGroupConfiguration back = mapper.readValue(mapper.writeValueAsString(config), AgentGroupConfiguration.class);
        assertEquals(35.0, back.getTeamBudget().monthlyBudgetUsd());
        assertEquals(CostPolicy.SYNTHESIZE_NOW, back.getTeamBudget().onExhausted());
        assertEquals(List.of("a"), back.getTeamBudget().oversight().teams());

        AgentGroupConfiguration old = mapper.readValue("{\"name\":\"old\"}", AgentGroupConfiguration.class);
        assertNull(old.getTeamBudget());
    }
}
