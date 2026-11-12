/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rules.impl.conditions;

import ai.labs.eddi.engine.memory.IConversationMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.modules.rules.impl.conditions.IRuleCondition.ExecutionState.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DeploymentContextCondition}.
 */
class DeploymentContextConditionTest {

    private DeploymentContextCondition condition;
    private IConversationMemory memory;
    private IConversationMemory.IWritableConversationStep currentStep;

    @BeforeEach
    void setUp() {
        condition = Mockito.spy(new DeploymentContextCondition());
        memory = mock(IConversationMemory.class);
        currentStep = mock(IConversationMemory.IWritableConversationStep.class);
        when(memory.getCurrentStep()).thenReturn(currentStep);
    }

    @Test
    void id_isDeploymentContext() {
        assertEquals("deploymentContext", condition.getId());
    }

    @Test
    void execute_whenMatchesEnv_success() {
        doReturn("production").when(condition).resolveDeploymentEnv();
        condition.setConfigs(Map.of("when", "production"));

        assertEquals(SUCCESS, condition.execute(memory, Collections.emptyList()));
    }

    @Test
    void execute_whenDoesNotMatchEnv_fail() {
        doReturn("development").when(condition).resolveDeploymentEnv();
        condition.setConfigs(Map.of("when", "production"));

        assertEquals(FAIL, condition.execute(memory, Collections.emptyList()));
    }

    @Test
    void execute_caseInsensitiveEnvMatch() {
        doReturn("PRODUCTION").when(condition).resolveDeploymentEnv();
        condition.setConfigs(Map.of("when", "production"));

        assertEquals(SUCCESS, condition.execute(memory, Collections.emptyList()));
    }

    @Test
    void execute_noWhenConfigured_matchesAnyEnv() {
        doReturn("production").when(condition).resolveDeploymentEnv();
        condition.setConfigs(Map.of());

        assertEquals(SUCCESS, condition.execute(memory, Collections.emptyList()));
    }

    @Test
    void execute_nullConfigs_success() {
        doReturn("production").when(condition).resolveDeploymentEnv();
        condition.setConfigs(null);

        assertEquals(SUCCESS, condition.execute(memory, Collections.emptyList()));
    }

    @Test
    void clone_preservesConfigs() {
        condition.setConfigs(Map.of("when", "staging", "tagMatches", "beta"));

        IRuleCondition cloned = condition.clone();
        assertInstanceOf(DeploymentContextCondition.class, cloned);
        assertEquals("staging", cloned.getConfigs().get("when"));
        assertEquals("beta", cloned.getConfigs().get("tagMatches"));
    }

    @Test
    void getConfigs_roundTrip() {
        var configs = Map.of("when", "production", "tagMatches", "high-risk");
        condition.setConfigs(configs);

        var result = condition.getConfigs();
        assertEquals("production", result.get("when"));
        assertEquals("high-risk", result.get("tagMatches"));
    }

    /**
     * Agents carry no tags: nothing ever wrote the {@code agent:tags} key the
     * condition read, so a rule with {@code tagMatches} could never fire —
     * including a safety rule meant to force cautious behaviour in production. The
     * ruleset is refused instead of saved as a rule that silently never matches.
     */
    @Test
    void validateConfiguration_tagMatches_isRefused() {
        condition.setConfigs(Map.of("when", "production", "tagMatches", "high-risk"));

        var thrown = assertThrows(IllegalArgumentException.class, () -> condition.validateConfiguration());
        assertTrue(thrown.getMessage().contains("tagMatches"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("contextmatcher"), thrown.getMessage());
    }

    @Test
    void validateConfiguration_blankTagMatches_passes() {
        condition.setConfigs(Map.of("when", "production", "tagMatches", "  "));

        assertDoesNotThrow(() -> condition.validateConfiguration());
    }

    @Test
    void validateConfiguration_whenOnly_passes() {
        condition.setConfigs(Map.of("when", "production"));

        assertDoesNotThrow(() -> condition.validateConfiguration());
    }

    @Test
    void setConditions_noOp() {
        // leaf condition — should accept without error
        condition.setConditions(List.of());
        condition.setConditions(null);
    }

    @Test
    void setContainingRuleSet_noOp() {
        condition.setContainingRuleSet(null);
    }

    @Test
    void getConfigs_emptyWhenNoConfigSet() {
        // Brand-new condition with no setConfigs called
        var fresh = new DeploymentContextCondition();
        var configs = fresh.getConfigs();
        assertNotNull(configs);
        assertTrue(configs.isEmpty());
    }

    @Test
    void execute_blankWhen_matchesAnyEnv() {
        doReturn("production").when(condition).resolveDeploymentEnv();
        condition.setConfigs(Map.of("when", "   "));

        assertEquals(SUCCESS, condition.execute(memory, Collections.emptyList()));
    }
}
