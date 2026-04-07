package ai.labs.eddi.modules.rules.impl.conditions;

import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.agents.CapabilityRegistryService.CapabilityMatch;
import ai.labs.eddi.engine.memory.IConversationMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static ai.labs.eddi.modules.rules.impl.conditions.IRuleCondition.ExecutionState.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CapabilityMatchConditionTest {

    private CapabilityRegistryService registryService;
    private CapabilityMatchCondition condition;
    private IConversationMemory memory;
    private IConversationMemory.IWritableConversationStep currentStep;

    @BeforeEach
    void setUp() {
        registryService = mock(CapabilityRegistryService.class);
        condition = new CapabilityMatchCondition(registryService);
        memory = mock(IConversationMemory.class);
        currentStep = mock(IConversationMemory.IWritableConversationStep.class);
        when(memory.getCurrentStep()).thenReturn(currentStep);
    }

    @Test
    void execute_successWhenRegistryReturnsMatches() {
        condition.setConfigs(Map.of("skill", "translation", "strategy", "all"));

        when(registryService.findBySkill("translation", "all"))
                .thenReturn(List.of(
                        new CapabilityMatch("agent-1", "translation", "high", Map.of()),
                        new CapabilityMatch("agent-2", "translation", "medium", Map.of())));

        assertEquals(SUCCESS, condition.execute(memory, List.of()));

        // Verify agent IDs were stored in memory
        verify(currentStep).storeData(argThat(data -> {
            List<String> ids = (List<String>) data.getResult();
            return ids.contains("agent-1") && ids.contains("agent-2");
        }));
    }

    @Test
    void execute_failWhenNoMatches() {
        condition.setConfigs(Map.of("skill", "nonexistent"));

        when(registryService.findBySkill("nonexistent", "highest_confidence"))
                .thenReturn(List.of());

        assertEquals(FAIL, condition.execute(memory, List.of()));
        verify(currentStep, never()).storeData(any());
    }

    @Test
    void execute_failWhenNullSkill() {
        // No skill configured
        assertEquals(FAIL, condition.execute(memory, List.of()));
        verifyNoInteractions(registryService);
    }

    @Test
    void execute_failWhenBlankSkill() {
        condition.setConfigs(Map.of("skill", "  "));

        assertEquals(FAIL, condition.execute(memory, List.of()));
        verifyNoInteractions(registryService);
    }

    @Test
    void execute_failWhenBelowMinResults() {
        condition.setConfigs(Map.of("skill", "analysis", "minResults", "3"));

        when(registryService.findBySkill("analysis", "highest_confidence"))
                .thenReturn(List.of(
                        new CapabilityMatch("a1", "analysis", "high", Map.of()),
                        new CapabilityMatch("a2", "analysis", "medium", Map.of())));

        // Only 2 matches, need 3
        assertEquals(FAIL, condition.execute(memory, List.of()));
    }

    @Test
    void execute_successWhenMeetsMinResults() {
        condition.setConfigs(Map.of("skill", "analysis", "minResults", "2"));

        when(registryService.findBySkill("analysis", "highest_confidence"))
                .thenReturn(List.of(
                        new CapabilityMatch("a1", "analysis", "high", Map.of()),
                        new CapabilityMatch("a2", "analysis", "medium", Map.of())));

        assertEquals(SUCCESS, condition.execute(memory, List.of()));
    }

    @Test
    void getConfigs_roundTripsCorrectly() {
        condition.setConfigs(Map.of("skill", "coding", "strategy", "round_robin", "minResults", "5"));

        Map<String, String> configs = condition.getConfigs();
        assertEquals("coding", configs.get("skill"));
        assertEquals("round_robin", configs.get("strategy"));
        assertEquals("5", configs.get("minResults"));
    }

    @Test
    void clone_preservesConfigAndService() throws CloneNotSupportedException {
        condition.setConfigs(Map.of("skill", "translation", "minResults", "2"));

        CapabilityMatchCondition cloned = (CapabilityMatchCondition) condition.clone();
        assertEquals("translation", cloned.getConfigs().get("skill"));
        assertEquals("2", cloned.getConfigs().get("minResults"));
    }

    @Test
    void setConfigs_invalidMinResults_defaultsToOne() {
        condition.setConfigs(Map.of("skill", "test", "minResults", "abc"));
        assertEquals("1", condition.getConfigs().get("minResults"));
    }
}
