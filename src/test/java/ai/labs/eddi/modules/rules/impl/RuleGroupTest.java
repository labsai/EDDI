package ai.labs.eddi.modules.rules.impl;

import org.junit.jupiter.api.Test;

import java.util.LinkedList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RuleGroupTest {

    @Test
    void defaults() {
        var group = new RuleGroup();
        assertNull(group.getName());
        assertEquals(RuleGroup.ExecutionStrategy.executeUntilFirstSuccess, group.getExecutionStrategy());
        assertNotNull(group.getRules());
        assertTrue(group.getRules().isEmpty());
    }

    @Test
    void setters() {
        var group = new RuleGroup();
        group.setName("greeting-rules");
        group.setExecutionStrategy(RuleGroup.ExecutionStrategy.executeAll);

        assertEquals("greeting-rules", group.getName());
        assertEquals(RuleGroup.ExecutionStrategy.executeAll, group.getExecutionStrategy());
    }

    @Test
    void setRules() {
        var group = new RuleGroup();
        var rules = new LinkedList<Rule>();
        rules.add(new Rule("rule1"));
        group.setRules(rules);
        assertEquals(1, group.getRules().size());
    }

    @Test
    void executionStrategy_allValues() {
        assertEquals(2, RuleGroup.ExecutionStrategy.values().length);
        assertNotNull(RuleGroup.ExecutionStrategy.valueOf("executeAll"));
        assertNotNull(RuleGroup.ExecutionStrategy.valueOf("executeUntilFirstSuccess"));
    }
}
