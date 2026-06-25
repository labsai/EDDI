/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.agents.CapabilityRegistryService.CapabilityMatch;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DynamicAgentConfig;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResult;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.setup.AgentSetupService;
import ai.labs.eddi.engine.setup.AgentSetupService.AgentSetupException;
import ai.labs.eddi.engine.setup.SetupAgentRequest;
import ai.labs.eddi.engine.setup.SetupResult;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the Dynamic Agent LLM tools: CreateSubAgentTool,
 * ConverseWithAgentTool, FindAgentsByCapabilityTool, and TeardownAgentTool.
 *
 * @since 6.0.0
 */
class DynamicAgentToolsTest {

    // === CreateSubAgentTool ===

    @Nested
    class CreateSubAgentToolTest {

        private AgentSetupService agentSetupService;
        private TenantQuotaService tenantQuotaService;
        private IConversationService conversationService;
        private DynamicAgentConfig config;
        private List<String> createdAgentIds;
        private CreateSubAgentTool tool;

        @BeforeEach
        void setUp() {
            agentSetupService = mock(AgentSetupService.class);
            tenantQuotaService = mock(TenantQuotaService.class);
            conversationService = mock(IConversationService.class);
            config = new DynamicAgentConfig();
            config.setEnabled(true);
            config.setAllowCreation(true);
            config.setMaxCreatedAgentsPerDiscussion(5);
            createdAgentIds = new CopyOnWriteArrayList<>();
            tool = new CreateSubAgentTool(agentSetupService, tenantQuotaService,
                    conversationService, "parent-agent-1", "user-1", config, createdAgentIds);
        }

        @Test
        void createSubAgent_success() throws Exception {
            when(tenantQuotaService.acquireConversationSlot())
                    .thenReturn(new QuotaCheckResult(true, null));
            when(agentSetupService.setupAgent(any(SetupAgentRequest.class)))
                    .thenReturn(new SetupResult("created", "sub-agent-1", "parent-agent-1/DataAnalyst",
                            "anthropic", "claude-sonnet-4-6", true, "ready", null, null, null, null, null));

            String result = tool.createSubAgent("DataAnalyst", "You analyze data", "anthropic", "claude-sonnet-4-6", null, null);

            assertTrue(result.contains("✅"));
            assertTrue(result.contains("sub-agent-1"));
            assertEquals(1, createdAgentIds.size());
            assertEquals("sub-agent-1", createdAgentIds.get(0));
        }

        @Test
        void createSubAgent_creationDisabled() {
            config.setAllowCreation(false);

            String result = tool.createSubAgent("Test", "prompt", null, null, null, null);

            assertTrue(result.contains("⚠️"));
            assertTrue(result.contains("not enabled"));
            assertTrue(createdAgentIds.isEmpty());
        }

        @Test
        void createSubAgent_maxReached() throws Exception {
            config.setMaxCreatedAgentsPerDiscussion(2);
            createdAgentIds.add("agent-1");
            createdAgentIds.add("agent-2");

            String result = tool.createSubAgent("Test", "prompt", null, null, null, null);

            assertTrue(result.contains("⚠️"));
            assertTrue(result.contains("Maximum created agents"));
            verify(agentSetupService, never()).setupAgent(any());
        }

        @Test
        void createSubAgent_providerNotAllowed() {
            config.setAllowedProviders(List.of("openai"));

            when(tenantQuotaService.acquireConversationSlot())
                    .thenReturn(new QuotaCheckResult(true, null));

            String result = tool.createSubAgent("Test", "prompt", "anthropic", null, null, null);

            assertTrue(result.contains("⚠️"));
            assertTrue(result.contains("not allowed"));
        }

        @Test
        void createSubAgent_modelNotAllowed() {
            config.setAllowedProviders(List.of("openai"));
            config.setAllowedModels(Map.of("openai", List.of("gpt-4o-mini")));

            when(tenantQuotaService.acquireConversationSlot())
                    .thenReturn(new QuotaCheckResult(true, null));

            String result = tool.createSubAgent("Test", "prompt", "openai", "gpt-4o", null, null);

            assertTrue(result.contains("⚠️"));
            assertTrue(result.contains("not allowed"));
        }

        @Test
        void createSubAgent_quotaDenied() {
            when(tenantQuotaService.acquireConversationSlot())
                    .thenReturn(new QuotaCheckResult(false, "Rate limit exceeded"));

            String result = tool.createSubAgent("Test", "prompt", null, null, null, null);

            assertTrue(result.contains("⚠️"));
            assertTrue(result.contains("quota"));
        }

        @Test
        void createSubAgent_nameRequired() {
            String result = tool.createSubAgent(null, "prompt", null, null, null, null);
            assertTrue(result.contains("⚠️"));
            assertTrue(result.contains("name is required"));
        }

        @Test
        void createSubAgent_promptRequired() {
            String result = tool.createSubAgent("Test", null, null, null, null, null);
            assertTrue(result.contains("⚠️"));
            assertTrue(result.contains("prompt is required"));
        }

        @Test
        void createSubAgent_setupFailure() throws Exception {
            when(tenantQuotaService.acquireConversationSlot())
                    .thenReturn(new QuotaCheckResult(true, null));
            when(agentSetupService.setupAgent(any(SetupAgentRequest.class)))
                    .thenThrow(new AgentSetupException("DB error"));

            String result = tool.createSubAgent("Test", "prompt", null, null, null, null);

            assertTrue(result.contains("❌"));
            assertTrue(result.contains("DB error"));
            assertTrue(createdAgentIds.isEmpty());
        }
    }

    // === FindAgentsByCapabilityTool ===

    @Nested
    class FindAgentsByCapabilityToolTest {

        private CapabilityRegistryService registryService;
        private FindAgentsByCapabilityTool tool;

        @BeforeEach
        void setUp() {
            registryService = mock(CapabilityRegistryService.class);
            tool = new FindAgentsByCapabilityTool(registryService);
        }

        @Test
        void findAgentsByCapability_found() {
            when(registryService.findBySkill("translation", "highest_confidence"))
                    .thenReturn(List.of(
                            new CapabilityMatch("agent-1", "translation", "high", Map.of("languages", "de,en")),
                            new CapabilityMatch("agent-2", "translation", "medium", Map.of())));

            String result = tool.findAgentsByCapability("translation", null);

            assertTrue(result.contains("🔍"));
            assertTrue(result.contains("2 agent(s)"));
            assertTrue(result.contains("agent-1"));
            assertTrue(result.contains("agent-2"));
            assertTrue(result.contains("languages=de,en"));
        }

        @Test
        void findAgentsByCapability_noneFound() {
            when(registryService.findBySkill("nonexistent", "highest_confidence"))
                    .thenReturn(List.of());

            String result = tool.findAgentsByCapability("nonexistent", null);

            assertTrue(result.contains("No agents found"));
        }

        @Test
        void findAgentsByCapability_skillRequired() {
            String result = tool.findAgentsByCapability(null, null);
            assertTrue(result.contains("⚠️"));
        }

        @Test
        void findAgentsByCapability_customStrategy() {
            when(registryService.findBySkill("code-review", "round_robin"))
                    .thenReturn(List.of(new CapabilityMatch("agent-1", "code-review", "high", Map.of())));

            String result = tool.findAgentsByCapability("code-review", "round_robin");

            assertTrue(result.contains("round_robin"));
            assertTrue(result.contains("agent-1"));
        }
    }

    // === TeardownAgentTool ===

    @Nested
    class TeardownAgentToolTest {

        private IAgentFactory agentFactory;
        private IAgentStore agentStore;
        private List<String> createdAgentIds;
        private Set<String> retainedAgentIds;
        private TeardownAgentTool tool;

        @BeforeEach
        void setUp() {
            agentFactory = mock(IAgentFactory.class);
            agentStore = mock(IAgentStore.class);
            createdAgentIds = new CopyOnWriteArrayList<>(List.of("created-1", "created-2"));
            retainedAgentIds = ConcurrentHashMap.newKeySet();
            tool = new TeardownAgentTool(agentFactory, agentStore, createdAgentIds, retainedAgentIds);
        }

        @Test
        void teardownAgent_undeploy() throws Exception {
            String result = tool.teardownAgent("created-1", false);

            assertTrue(result.contains("✅"));
            assertTrue(result.contains("undeployed"));
            verify(agentFactory).undeployAgent(any(Environment.class), eq("created-1"), isNull());
            verify(agentStore, never()).deleteAllPermanently(any());
        }

        @Test
        void teardownAgent_undeployAndDelete() throws Exception {
            String result = tool.teardownAgent("created-1", true);

            assertTrue(result.contains("✅"));
            assertTrue(result.contains("deleted"));
            verify(agentFactory).undeployAgent(any(Environment.class), eq("created-1"), isNull());
            verify(agentStore).deleteAllPermanently("created-1");
        }

        @Test
        void teardownAgent_notCreatedByUs() {
            String result = tool.teardownAgent("external-agent", false);

            assertTrue(result.contains("⚠️"));
            assertTrue(result.contains("not created"));
            verifyNoInteractions(agentFactory);
        }

        @Test
        void teardownAgent_retained() {
            retainedAgentIds.add("created-1");

            String result = tool.teardownAgent("created-1", false);

            assertTrue(result.contains("⚠️"));
            assertTrue(result.contains("retained"));
            verifyNoInteractions(agentFactory);
        }

        @Test
        void retainAgent_success() {
            String result = tool.retainAgent("created-1");

            assertTrue(result.contains("✅"));
            assertTrue(result.contains("retained"));
            assertTrue(retainedAgentIds.contains("created-1"));
        }

        @Test
        void retainAgent_notCreatedByUs() {
            String result = tool.retainAgent("external-agent");

            assertTrue(result.contains("⚠️"));
            assertTrue(result.contains("not created"));
            assertFalse(retainedAgentIds.contains("external-agent"));
        }

        @Test
        void retainAgent_alreadyRetained() {
            retainedAgentIds.add("created-1");

            String result = tool.retainAgent("created-1");

            assertTrue(result.contains("already"));
        }

        @Test
        void teardownAgent_agentIdRequired() {
            String result = tool.teardownAgent(null, false);
            assertTrue(result.contains("⚠️"));
        }
    }

    // === DynamicAgentConfig ===

    @Nested
    class DynamicAgentConfigTest {

        @Test
        void defaults() {
            var config = new DynamicAgentConfig();

            assertFalse(config.isEnabled());
            assertFalse(config.isAllowCreation());
            assertFalse(config.isAllowRecruitment());
            assertTrue(config.isAllowDelegation());
            assertEquals(5, config.getMaxCreatedAgentsPerDiscussion());
            assertEquals(10, config.getMaxRecruitedAgentsPerDiscussion());
            assertEquals(3, config.getMaxDelegationsPerTask());
            assertTrue(config.isInheritParentModel());
            assertEquals("ephemeral", config.getLifecyclePolicy());
            assertNull(config.getAllowedProviders());
            assertNull(config.getAllowedModels());
        }

        @Test
        void setAndGet() {
            var config = new DynamicAgentConfig();
            config.setEnabled(true);
            config.setAllowCreation(true);
            config.setAllowRecruitment(true);
            config.setAllowDelegation(false);
            config.setMaxCreatedAgentsPerDiscussion(3);
            config.setMaxRecruitedAgentsPerDiscussion(7);
            config.setMaxDelegationsPerTask(2);
            config.setAllowedProviders(List.of("openai", "anthropic"));
            config.setAllowedModels(Map.of("openai", List.of("gpt-4o")));
            config.setInheritParentModel(false);
            config.setLifecyclePolicy("agent-decides");

            assertTrue(config.isEnabled());
            assertTrue(config.isAllowCreation());
            assertTrue(config.isAllowRecruitment());
            assertFalse(config.isAllowDelegation());
            assertEquals(3, config.getMaxCreatedAgentsPerDiscussion());
            assertEquals(7, config.getMaxRecruitedAgentsPerDiscussion());
            assertEquals(2, config.getMaxDelegationsPerTask());
            assertEquals(List.of("openai", "anthropic"), config.getAllowedProviders());
            assertEquals(Map.of("openai", List.of("gpt-4o")), config.getAllowedModels());
            assertFalse(config.isInheritParentModel());
            assertEquals("agent-decides", config.getLifecyclePolicy());
        }
    }

    // === GroupConversation dynamic member fields ===

    @Nested
    class GroupConversationDynamicFieldsTest {

        @Test
        void addDynamicMember_threadSafe() {
            var gc = new ai.labs.eddi.configs.groups.model.GroupConversation();
            var member = new ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember(
                    "dynamic-1", "Dynamic Agent", 99, "specialist");

            gc.addDynamicMember(member);

            assertEquals(1, gc.getDynamicMembers().size());
            assertEquals("dynamic-1", gc.getDynamicMembers().get(0).agentId());
        }

        @Test
        void createdAgentIds_tracking() {
            var gc = new ai.labs.eddi.configs.groups.model.GroupConversation();

            gc.getCreatedAgentIds().add("agent-a");
            gc.getCreatedAgentIds().add("agent-b");

            assertEquals(2, gc.getCreatedAgentIds().size());
            assertTrue(gc.getCreatedAgentIds().contains("agent-a"));
        }

        @Test
        void retainedAgentIds_tracking() {
            var gc = new ai.labs.eddi.configs.groups.model.GroupConversation();

            gc.getRetainedAgentIds().add("agent-a");

            assertTrue(gc.getRetainedAgentIds().contains("agent-a"));
            assertFalse(gc.getRetainedAgentIds().contains("agent-b"));
        }

        @Test
        void setDynamicMembers_null_safe() {
            var gc = new ai.labs.eddi.configs.groups.model.GroupConversation();
            gc.setDynamicMembers(null);
            assertNotNull(gc.getDynamicMembers());
            assertTrue(gc.getDynamicMembers().isEmpty());
        }

        @Test
        void setCreatedAgentIds_null_safe() {
            var gc = new ai.labs.eddi.configs.groups.model.GroupConversation();
            gc.setCreatedAgentIds(null);
            assertNotNull(gc.getCreatedAgentIds());
            assertTrue(gc.getCreatedAgentIds().isEmpty());
        }

        @Test
        void setRetainedAgentIds_null_safe() {
            var gc = new ai.labs.eddi.configs.groups.model.GroupConversation();
            gc.setRetainedAgentIds(null);
            assertNotNull(gc.getRetainedAgentIds());
            assertTrue(gc.getRetainedAgentIds().isEmpty());
        }
    }
}
