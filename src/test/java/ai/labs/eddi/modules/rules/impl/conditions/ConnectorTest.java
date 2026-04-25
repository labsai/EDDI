package ai.labs.eddi.modules.rules.impl.conditions;

import ai.labs.eddi.engine.memory.IConversationMemory;
import org.junit.jupiter.api.Test;

import java.util.*;

import static ai.labs.eddi.modules.rules.impl.conditions.IRuleCondition.ExecutionState;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConnectorTest {

    @Test
    void id() {
        assertEquals("connector", new Connector().getId());
    }

    @Test
    void setConfigs_and() {
        var conn = new Connector();
        conn.setConfigs(Map.of("operator", "AND"));
        assertEquals("AND", conn.getConfigs().get("operator"));
    }

    @Test
    void setConfigs_or() {
        var conn = new Connector();
        conn.setConfigs(Map.of("operator", "OR"));
        assertEquals("OR", conn.getConfigs().get("operator"));
    }

    @Test
    void setConfigs_null_noOp() {
        assertDoesNotThrow(() -> new Connector().setConfigs(null));
    }

    @Test
    void isEmpty_noConditions_true() {
        assertTrue(new Connector().isEmpty());
    }

    @Test
    void isEmpty_withConditions_false() {
        var conn = new Connector();
        var mockCondition = mock(IRuleCondition.class);
        conn.setConditions(List.of(mockCondition));
        assertFalse(conn.isEmpty());
    }

    @Test
    void execute_or_firstSuccess_returnsSuccess() throws Exception {
        var conn = new Connector();
        conn.setConfigs(Map.of("operator", "OR"));

        var c1 = mock(IRuleCondition.class);
        when(c1.execute(any(), any())).thenReturn(ExecutionState.SUCCESS);
        var c2 = mock(IRuleCondition.class);

        conn.setConditions(List.of(c1, c2));

        var result = conn.execute(mock(IConversationMemory.class), new ArrayList<>());
        assertEquals(ExecutionState.SUCCESS, result);
        verify(c2, never()).execute(any(), any()); // short-circuited
    }

    @Test
    void execute_or_allFail_returnsFail() throws Exception {
        var conn = new Connector();
        conn.setConfigs(Map.of("operator", "OR"));

        var c1 = mock(IRuleCondition.class);
        when(c1.execute(any(), any())).thenReturn(ExecutionState.FAIL);
        var c2 = mock(IRuleCondition.class);
        when(c2.execute(any(), any())).thenReturn(ExecutionState.FAIL);

        conn.setConditions(List.of(c1, c2));

        var result = conn.execute(mock(IConversationMemory.class), new ArrayList<>());
        assertEquals(ExecutionState.FAIL, result);
    }

    @Test
    void execute_and_allSuccess_returnsSuccess() throws Exception {
        var conn = new Connector();
        conn.setConfigs(Map.of("operator", "AND"));

        var c1 = mock(IRuleCondition.class);
        when(c1.execute(any(), any())).thenReturn(ExecutionState.SUCCESS);
        var c2 = mock(IRuleCondition.class);
        when(c2.execute(any(), any())).thenReturn(ExecutionState.SUCCESS);

        conn.setConditions(List.of(c1, c2));

        var result = conn.execute(mock(IConversationMemory.class), new ArrayList<>());
        assertEquals(ExecutionState.SUCCESS, result);
    }

    @Test
    void execute_and_firstFail_returnsFail() throws Exception {
        var conn = new Connector();
        conn.setConfigs(Map.of("operator", "AND"));

        var c1 = mock(IRuleCondition.class);
        when(c1.execute(any(), any())).thenReturn(ExecutionState.FAIL);
        var c2 = mock(IRuleCondition.class);

        conn.setConditions(List.of(c1, c2));

        var result = conn.execute(mock(IConversationMemory.class), new ArrayList<>());
        assertEquals(ExecutionState.FAIL, result);
        verify(c2, never()).execute(any(), any()); // short-circuited
    }

    @Test
    void clone_preservesOperatorAndConditions() throws Exception {
        var conn = new Connector();
        conn.setConfigs(Map.of("operator", "AND"));

        var c1 = mock(IRuleCondition.class);
        when(c1.clone()).thenReturn(c1);
        conn.setConditions(List.of(c1));

        var cloned = (Connector) conn.clone();
        assertNotSame(conn, cloned);
        assertEquals("AND", cloned.getConfigs().get("operator"));
        assertEquals(1, cloned.getConditions().size());
    }
}
