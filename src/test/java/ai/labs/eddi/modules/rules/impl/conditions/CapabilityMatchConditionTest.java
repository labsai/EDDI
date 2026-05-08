/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rules.impl.conditions;

import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.agents.CapabilityRegistryService.CapabilityMatch;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static ai.labs.eddi.modules.rules.impl.conditions.IRuleCondition.ExecutionState.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CapabilityMatchConditionTest {

    private CapabilityRegistryService registryService;
    private IMemoryItemConverter memoryItemConverter;
    private ITemplatingEngine templatingEngine;
    private CapabilityMatchCondition condition;
    private IConversationMemory memory;
    private IConversationMemory.IWritableConversationStep currentStep;

    @BeforeEach
    void setUp() {
        registryService = mock(CapabilityRegistryService.class);
        memoryItemConverter = mock(IMemoryItemConverter.class);
        templatingEngine = mock(ITemplatingEngine.class);
        condition = new CapabilityMatchCondition(registryService, memoryItemConverter, templatingEngine);
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
            @SuppressWarnings("unchecked")
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
    void execute_resolvesTemplateVariablesInSkill() throws ITemplatingEngine.TemplateEngineException {
        condition.setConfigs(Map.of("skill", "{{properties.requiredSkill.valueString}}"));

        Map<String, Object> templateData = Map.of("properties", Map.of("requiredSkill", Map.of("valueString", "translation")));
        when(memoryItemConverter.convert(memory)).thenReturn(templateData);
        when(templatingEngine.processTemplate(eq("{{properties.requiredSkill.valueString}}"), eq(templateData)))
                .thenReturn("translation");

        when(registryService.findBySkill("translation", "highest_confidence"))
                .thenReturn(List.of(new CapabilityMatch("agent-1", "translation", "high", Map.of())));

        assertEquals(SUCCESS, condition.execute(memory, List.of()));
        verify(templatingEngine).processTemplate(eq("{{properties.requiredSkill.valueString}}"), eq(templateData));
    }

    @Test
    void execute_resolvesTemplateVariablesInStrategy() throws ITemplatingEngine.TemplateEngineException {
        condition.setConfigs(Map.of("skill", "coding", "strategy", "{{context.routingStrategy}}"));

        Map<String, Object> templateData = Map.of("context", Map.of("routingStrategy", "round_robin"));
        when(memoryItemConverter.convert(memory)).thenReturn(templateData);
        when(templatingEngine.processTemplate(eq("{{context.routingStrategy}}"), eq(templateData)))
                .thenReturn("round_robin");

        when(registryService.findBySkill("coding", "round_robin"))
                .thenReturn(List.of(new CapabilityMatch("agent-1", "coding", "high", Map.of())));

        assertEquals(SUCCESS, condition.execute(memory, List.of()));
    }

    @Test
    void execute_fallsBackToRawValueOnTemplateError() throws ITemplatingEngine.TemplateEngineException {
        condition.setConfigs(Map.of("skill", "{{invalid.template}}"));

        Map<String, Object> templateData = Map.of();
        when(memoryItemConverter.convert(memory)).thenReturn(templateData);
        when(templatingEngine.processTemplate(eq("{{invalid.template}}"), eq(templateData)))
                .thenThrow(new ITemplatingEngine.TemplateEngineException("bad template", new RuntimeException()));

        // Falls back to raw "{{invalid.template}}" which won't match any skill
        when(registryService.findBySkill("{{invalid.template}}", "highest_confidence"))
                .thenReturn(List.of());

        assertEquals(FAIL, condition.execute(memory, List.of()));
    }

    @Test
    void execute_skipsTemplateResolutionWhenNoMarkers() {
        condition.setConfigs(Map.of("skill", "plain-skill"));

        when(registryService.findBySkill("plain-skill", "highest_confidence"))
                .thenReturn(List.of(new CapabilityMatch("a1", "plain-skill", "high", Map.of())));

        assertEquals(SUCCESS, condition.execute(memory, List.of()));

        // Template engine should NOT be called for plain values
        verifyNoInteractions(memoryItemConverter);
        verifyNoInteractions(templatingEngine);
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

    // --- Wave 3: audit event emission ---

    @Test
    void execute_emitsAuditEventOnSuccess() {
        condition.setConfigs(Map.of("skill", "coding", "strategy", "all"));

        var auditCollector = mock(ai.labs.eddi.engine.audit.IAuditEntryCollector.class);
        when(memory.getAuditCollector()).thenReturn(auditCollector);
        when(memory.getConversationId()).thenReturn("conv-123");
        when(memory.getAgentId()).thenReturn("agent-owner");
        when(memory.getAgentVersion()).thenReturn(2);
        when(memory.getUserId()).thenReturn("user-456");

        var allSteps = mock(IConversationMemory.IConversationStepStack.class);
        when(allSteps.size()).thenReturn(3);
        when(memory.getAllSteps()).thenReturn(allSteps);

        when(registryService.findBySkill("coding", "all"))
                .thenReturn(List.of(new CapabilityMatch("agent-1", "coding", "high", Map.of())));

        assertEquals(SUCCESS, condition.execute(memory, List.of()));

        // Verify audit event was emitted
        verify(auditCollector).collect(argThat(entry -> {
            assertEquals("conv-123", entry.conversationId());
            assertEquals("agent-owner", entry.agentId());
            assertEquals(2, entry.agentVersion());
            assertEquals("user-456", entry.userId());
            assertEquals("capabilityMatch", entry.taskId());
            assertEquals("CAPABILITY_SELECTION", entry.taskType());
            assertEquals(2, entry.stepIndex()); // 0-based: allSteps.size() - 1

            // Check input contains skill and strategy
            assertNotNull(entry.input());
            assertEquals("coding", entry.input().get("skill"));
            assertEquals("all", entry.input().get("strategy"));

            // Check output contains selectedAgentId
            assertNotNull(entry.output());
            assertEquals("agent-1", entry.output().get("selectedAgentId"));

            return true;
        }));
    }

    @Test
    void execute_noAuditWhenCollectorIsNull() {
        condition.setConfigs(Map.of("skill", "coding"));

        when(memory.getAuditCollector()).thenReturn(null);

        when(registryService.findBySkill("coding", "highest_confidence"))
                .thenReturn(List.of(new CapabilityMatch("agent-1", "coding", "high", Map.of())));

        // Should still succeed — just skip audit
        assertEquals(SUCCESS, condition.execute(memory, List.of()));
    }

    @Test
    void execute_auditFailureDoesNotBreakExecution() {
        condition.setConfigs(Map.of("skill", "coding"));

        var auditCollector = mock(ai.labs.eddi.engine.audit.IAuditEntryCollector.class);
        when(memory.getAuditCollector()).thenReturn(auditCollector);
        when(memory.getConversationId()).thenReturn("conv-123");
        when(memory.getAgentId()).thenReturn("agent-owner");
        when(memory.getAgentVersion()).thenReturn(1);
        when(memory.getUserId()).thenReturn("user-456");

        var allSteps = mock(IConversationMemory.IConversationStepStack.class);
        when(allSteps.size()).thenReturn(1);
        when(memory.getAllSteps()).thenReturn(allSteps);

        // Audit collector throws — should not affect condition result
        doThrow(new RuntimeException("audit storage failed"))
                .when(auditCollector).collect(any());

        when(registryService.findBySkill("coding", "highest_confidence"))
                .thenReturn(List.of(new CapabilityMatch("agent-1", "coding", "high", Map.of())));

        // Should still return SUCCESS despite audit failure
        assertEquals(SUCCESS, condition.execute(memory, List.of()));
    }

    @Test
    void execute_noAuditEventOnFailure() {
        condition.setConfigs(Map.of("skill", "nonexistent"));

        var auditCollector = mock(ai.labs.eddi.engine.audit.IAuditEntryCollector.class);
        when(memory.getAuditCollector()).thenReturn(auditCollector);

        when(registryService.findBySkill("nonexistent", "highest_confidence"))
                .thenReturn(List.of());

        assertEquals(FAIL, condition.execute(memory, List.of()));

        // Audit should NOT be emitted on failure
        verifyNoInteractions(auditCollector);
    }
}
