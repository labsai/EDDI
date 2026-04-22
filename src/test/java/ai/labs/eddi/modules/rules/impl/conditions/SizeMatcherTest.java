package ai.labs.eddi.modules.rules.impl.conditions;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.modules.rules.impl.Rule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static ai.labs.eddi.modules.rules.impl.conditions.IRuleCondition.ExecutionState;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SizeMatcherTest {

    private IMemoryItemConverter converter;
    private SizeMatcher matcher;

    @BeforeEach
    void setUp() {
        converter = mock(IMemoryItemConverter.class);
        matcher = new SizeMatcher(converter);
    }

    @Test
    void id() {
        assertEquals("sizematcher", matcher.getId());
    }

    @Test
    void defaultConfigs() {
        Map<String, String> configs = matcher.getConfigs();
        assertEquals("-1", configs.get("min"));
        assertEquals("-1", configs.get("max"));
        assertEquals("-1", configs.get("equal"));
    }

    @Test
    void setConfigs_setsAll() {
        matcher.setConfigs(Map.of(
                "valuePath", "memory.current.count",
                "min", "1",
                "max", "10",
                "equal", "5"));
        Map<String, String> configs = matcher.getConfigs();
        assertEquals("memory.current.count", configs.get("valuePath"));
        assertEquals("1", configs.get("min"));
        assertEquals("10", configs.get("max"));
        assertEquals("5", configs.get("equal"));
    }

    @Test
    void execute_allDefault_returnsNotExecuted() throws Exception {
        // All defaults at -1, should not execute
        var memory = mock(IConversationMemory.class);
        assertEquals(ExecutionState.NOT_EXECUTED, matcher.execute(memory, new ArrayList<>()));
    }

    @Test
    void execute_minMatches_success() throws Exception {
        matcher.setConfigs(Map.of("valuePath", "count", "min", "2"));

        var memory = mock(IConversationMemory.class);
        when(converter.convert(any())).thenReturn(Map.of("count", 5));

        assertEquals(ExecutionState.SUCCESS, matcher.execute(memory, new ArrayList<>()));
    }

    @Test
    void execute_minNotReached_fail() throws Exception {
        matcher.setConfigs(Map.of("valuePath", "count", "min", "10"));

        var memory = mock(IConversationMemory.class);
        when(converter.convert(any())).thenReturn(Map.of("count", 5));

        assertEquals(ExecutionState.FAIL, matcher.execute(memory, new ArrayList<>()));
    }

    @Test
    void execute_maxExceeded_fail() throws Exception {
        matcher.setConfigs(Map.of("valuePath", "count", "max", "3"));

        var memory = mock(IConversationMemory.class);
        when(converter.convert(any())).thenReturn(Map.of("count", 5));

        assertEquals(ExecutionState.FAIL, matcher.execute(memory, new ArrayList<>()));
    }

    @Test
    void execute_equalMatches_success() throws Exception {
        matcher.setConfigs(Map.of("valuePath", "count", "equal", "5"));

        var memory = mock(IConversationMemory.class);
        when(converter.convert(any())).thenReturn(Map.of("count", 5));

        assertEquals(ExecutionState.SUCCESS, matcher.execute(memory, new ArrayList<>()));
    }

    @Test
    void execute_equalNotMatches_fail() throws Exception {
        matcher.setConfigs(Map.of("valuePath", "count", "equal", "3"));

        var memory = mock(IConversationMemory.class);
        when(converter.convert(any())).thenReturn(Map.of("count", 5));

        assertEquals(ExecutionState.FAIL, matcher.execute(memory, new ArrayList<>()));
    }

    @Test
    void execute_pathNotFound_sizeIsZero() throws Exception {
        matcher.setConfigs(Map.of("valuePath", "nonexistent", "min", "1"));

        var memory = mock(IConversationMemory.class);
        when(converter.convert(any())).thenReturn(Map.of("other", 10));

        assertEquals(ExecutionState.FAIL, matcher.execute(memory, new ArrayList<>()));
    }

    @Test
    void clone_preservesConfigs() {
        matcher.setConfigs(Map.of("valuePath", "test.path", "min", "2", "max", "8", "equal", "-1"));

        var cloned = (SizeMatcher) matcher.clone();
        assertNotSame(matcher, cloned);
        assertEquals("test.path", cloned.getConfigs().get("valuePath"));
        assertEquals("2", cloned.getConfigs().get("min"));
    }

    @Test
    void setConfigs_null_noOp() {
        assertDoesNotThrow(() -> matcher.setConfigs(null));
    }
}
