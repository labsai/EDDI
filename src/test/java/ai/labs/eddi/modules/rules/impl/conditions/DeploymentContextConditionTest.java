/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rules.impl.conditions;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.model.Data;
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
    void execute_tagMatchesPresent_success() {
        doReturn("production").when(condition).resolveDeploymentEnv();
        condition.setConfigs(Map.of("when", "production", "tagMatches", "high-risk"));

        IData<List<String>> tagsData = new Data<>("agent:tags", List.of("high-risk", "internal"));
        doReturn(tagsData).when(currentStep).getLatestData("agent:tags");

        assertEquals(SUCCESS, condition.execute(memory, Collections.emptyList()));
    }

    @Test
    void execute_tagMatchesMissing_fail() {
        doReturn("production").when(condition).resolveDeploymentEnv();
        condition.setConfigs(Map.of("when", "production", "tagMatches", "high-risk"));

        IData<List<String>> tagsData = new Data<>("agent:tags", List.of("low-risk", "internal"));
        doReturn(tagsData).when(currentStep).getLatestData("agent:tags");

        assertEquals(FAIL, condition.execute(memory, Collections.emptyList()));
    }

    @Test
    void execute_tagMatchesNoTagsData_fail() {
        doReturn("production").when(condition).resolveDeploymentEnv();
        condition.setConfigs(Map.of("when", "production", "tagMatches", "high-risk"));

        when(currentStep.getLatestData("agent:tags")).thenReturn(null);

        assertEquals(FAIL, condition.execute(memory, Collections.emptyList()));
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

    @Test
    void execute_tagMatchesCaseInsensitive() {
        doReturn("production").when(condition).resolveDeploymentEnv();
        condition.setConfigs(Map.of("when", "production", "tagMatches", "HIGH-RISK"));

        IData<List<String>> tagsData = new Data<>("agent:tags", List.of("high-risk"));
        doReturn(tagsData).when(currentStep).getLatestData("agent:tags");

        assertEquals(SUCCESS, condition.execute(memory, Collections.emptyList()));
    }
}
