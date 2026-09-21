/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rules.impl.conditions;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OccurrenceTest {

    @Test
    void id() {
        assertEquals("occurrence", new Occurrence().getId());
    }

    @Test
    void defaultConfigs() {
        var occ = new Occurrence();
        Map<String, String> configs = occ.getConfigs();
        assertEquals("-1", configs.get("maxTimesOccurred"));
        assertEquals("-1", configs.get("minTimesOccurred"));
        assertNull(configs.get("behaviorRuleName"));
    }

    @Test
    void setConfigs_setsAll() {
        var occ = new Occurrence();
        occ.setConfigs(Map.of(
                "behaviorRuleName", "greet",
                "maxTimesOccurred", "5",
                "minTimesOccurred", "1"));
        Map<String, String> configs = occ.getConfigs();
        assertEquals("greet", configs.get("behaviorRuleName"));
        assertEquals("5", configs.get("maxTimesOccurred"));
        assertEquals("1", configs.get("minTimesOccurred"));
    }

    @Test
    void setConfigs_null_noOp() {
        var occ = new Occurrence();
        assertDoesNotThrow(() -> occ.setConfigs(null));
    }

    @Test
    void setConfigs_empty_noOp() {
        var occ = new Occurrence();
        assertDoesNotThrow(() -> occ.setConfigs(Collections.emptyMap()));
    }

    @Test
    void clone_preservesConfigs() {
        var occ = new Occurrence();
        occ.setConfigs(Map.of(
                "behaviorRuleName", "test",
                "maxTimesOccurred", "3",
                "minTimesOccurred", "0"));

        var cloned = occ.clone();
        assertNotSame(occ, cloned);
        assertEquals("occurrence", cloned.getId());
        assertEquals("test", cloned.getConfigs().get("behaviorRuleName"));
    }

    @Test
    void validateConfiguration_withoutBehaviorRuleName_isRejected() {
        var occ = new Occurrence();
        occ.setConfigs(Map.of("maxTimesOccurred", "3"));

        var thrown = assertThrows(IllegalArgumentException.class, occ::validateConfiguration);
        assertTrue(thrown.getMessage().contains("behaviorRuleName"), thrown.getMessage());
    }

    @Test
    void validateConfiguration_withoutAnyBound_isRejected() {
        var occ = new Occurrence();
        occ.setConfigs(Map.of("behaviorRuleName", "greet"));

        var thrown = assertThrows(IllegalArgumentException.class, occ::validateConfiguration);
        assertTrue(thrown.getMessage().contains("minTimesOccurred"), thrown.getMessage());
    }

    @Test
    void validateConfiguration_withNameAndBound_passes() {
        var occ = new Occurrence();
        occ.setConfigs(Map.of("behaviorRuleName", "greet", "minTimesOccurred", "1"));

        assertDoesNotThrow(occ::validateConfiguration);
    }

    /**
     * Rulesets stored before the validation existed can still carry an unnamed
     * occurrence — counting must yield 0 rather than throwing on the conversation
     * thread.
     */
    @Test
    @SuppressWarnings("unchecked")
    void execute_withoutBehaviorRuleName_countsNothingInsteadOfThrowing() {
        var occ = new Occurrence();
        occ.setConfigs(Map.of("minTimesOccurred", "1"));

        var memory = mock(IConversationMemory.class);
        var allSteps = mock(IConversationMemory.IConversationStepStack.class);
        IData<Object> data = mock(IData.class);
        when(data.getResult()).thenReturn(List.of("greet"));
        when(memory.getAllSteps()).thenReturn(allSteps);
        when(allSteps.<Object>getAllData("behavior_rules:success")).thenReturn(List.of(List.of(data)));

        assertEquals(IRuleCondition.ExecutionState.FAIL, occ.execute(memory, new LinkedList<>()));
    }

    @Test
    void execute_noData_returnsFail() {
        var occ = new Occurrence();
        occ.setConfigs(Map.of("behaviorRuleName", "greet"));

        var memory = mock(IConversationMemory.class);
        var allSteps = mock(IConversationMemory.IConversationStepStack.class);
        org.mockito.Mockito.when(memory.getAllSteps()).thenReturn(allSteps);
        org.mockito.Mockito.when(allSteps.getAllData("behavior_rules:success")).thenReturn(null);

        var result = occ.execute(memory, new LinkedList<>());
        assertEquals(IRuleCondition.ExecutionState.FAIL, result);
    }
}
