/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rules.impl.conditions;

import ai.labs.eddi.engine.memory.IConversationMemory;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

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
