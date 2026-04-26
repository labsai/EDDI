/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating.rest;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.modules.llm.impl.PromptSnippetService;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestTemplatePreview}.
 * <p>
 * Covers: null/blank input handling, default sample data path, conversation
 * memory loading, prompt snippet injection, variable flattening, template
 * resolution errors, and conversation not-found handling.
 */
class RestTemplatePreviewTest {

    private ITemplatingEngine templatingEngine;
    private IConversationMemoryStore conversationMemoryStore;
    private IMemoryItemConverter memoryItemConverter;
    private PromptSnippetService promptSnippetService;
    private RestTemplatePreview restTemplatePreview;

    @BeforeEach
    void setUp() {
        templatingEngine = mock(ITemplatingEngine.class);
        conversationMemoryStore = mock(IConversationMemoryStore.class);
        memoryItemConverter = mock(IMemoryItemConverter.class);
        promptSnippetService = mock(PromptSnippetService.class);
        when(promptSnippetService.getAll()).thenReturn(Map.of());

        restTemplatePreview = new RestTemplatePreview(
                templatingEngine, conversationMemoryStore,
                memoryItemConverter, promptSnippetService);
    }

    // ==================== Null / Blank Input ====================

    @Nested
    class NullAndBlankInput {

        @Test
        void shouldReturnEmptyResponseForNullRequest() {
            TemplatePreviewResponse response = restTemplatePreview.previewTemplate(null);

            assertEquals("", response.resolved());
            assertTrue(response.availableVariables().isEmpty());
            assertTrue(response.variableValues().isEmpty());
            assertNull(response.error());
        }

        @Test
        void shouldReturnEmptyResponseForNullTemplate() {
            TemplatePreviewResponse response = restTemplatePreview.previewTemplate(new TemplatePreviewRequest(null, null));

            assertEquals("", response.resolved());
            assertTrue(response.availableVariables().isEmpty());
            assertNull(response.error());
        }

        @Test
        void shouldReturnEmptyResponseForBlankTemplate() {
            TemplatePreviewResponse response = restTemplatePreview.previewTemplate(new TemplatePreviewRequest("   ", null));

            assertEquals("", response.resolved());
            assertTrue(response.availableVariables().isEmpty());
            assertNull(response.error());
        }

        @Test
        void shouldReturnEmptyResponseForEmptyTemplate() {
            TemplatePreviewResponse response = restTemplatePreview.previewTemplate(new TemplatePreviewRequest("", null));

            assertEquals("", response.resolved());
            assertNull(response.error());
        }

        @Test
        void shouldNotCallTemplatingEngineForEmptyInput() throws Exception {
            restTemplatePreview.previewTemplate(new TemplatePreviewRequest("", null));

            verifyNoInteractions(templatingEngine);
        }

        @Test
        void shouldNotCallTemplatingEngineForWhitespaceInput() throws Exception {
            restTemplatePreview.previewTemplate(new TemplatePreviewRequest("   ", null));

            verifyNoInteractions(templatingEngine);
        }
    }

    // ==================== Default Sample Data ====================

    @Nested
    class DefaultSampleData {

        @Test
        void shouldUseDefaultDataWhenNoConversationId() throws Exception {
            when(templatingEngine.processTemplate(anyString(), anyMap()))
                    .thenReturn("resolved output");

            TemplatePreviewResponse response = restTemplatePreview.previewTemplate(new TemplatePreviewRequest("Hello {name}", null));

            assertEquals("resolved output", response.resolved());
            assertNull(response.error());
            // Should never touch the memory store
            verifyNoInteractions(conversationMemoryStore);
        }

        @Test
        void shouldUseDefaultDataWhenConversationIdIsBlank() throws Exception {
            when(templatingEngine.processTemplate(anyString(), anyMap()))
                    .thenReturn("resolved");

            TemplatePreviewResponse response = restTemplatePreview.previewTemplate(new TemplatePreviewRequest("Hello", "   "));

            assertEquals("resolved", response.resolved());
            verifyNoInteractions(conversationMemoryStore);
        }

        @Test
        void shouldProvideKnownSampleVariables() throws Exception {
            when(templatingEngine.processTemplate(anyString(), anyMap()))
                    .thenReturn("ok");

            TemplatePreviewResponse response = restTemplatePreview.previewTemplate(new TemplatePreviewRequest("template", null));

            List<String> vars = response.availableVariables();
            // Verify key sample data paths are flattened
            assertTrue(vars.contains("properties.userName"), "Should have properties.userName");
            assertTrue(vars.contains("properties.language"), "Should have properties.language");
            assertTrue(vars.contains("properties.email"), "Should have properties.email");
            assertTrue(vars.contains("memory.current.input"), "Should have memory.current.input");
            assertTrue(vars.contains("memory.current.actions"), "Should have memory.current.actions");
            assertTrue(vars.contains("memory.last.input"), "Should have memory.last.input");
            assertTrue(vars.contains("memory.last.output"), "Should have memory.last.output");
            assertTrue(vars.contains("memory.past"), "Should have memory.past");
            assertTrue(vars.contains("context.output"), "Should have context.output");
            assertTrue(vars.contains("userInfo.userId"), "Should have userInfo.userId");
            assertTrue(vars.contains("conversationInfo.conversationId"), "Should have conversationInfo.conversationId");
            assertTrue(vars.contains("conversationInfo.agentId"), "Should have conversationInfo.agentId");
            assertTrue(vars.contains("conversationInfo.agentVersion"), "Should have conversationInfo.agentVersion");
            assertTrue(vars.contains("input"), "Should have top-level input");
        }

        @Test
        void shouldPopulateVariableValuesForSampleData() throws Exception {
            when(templatingEngine.processTemplate(anyString(), anyMap()))
                    .thenReturn("ok");

            TemplatePreviewResponse response = restTemplatePreview.previewTemplate(new TemplatePreviewRequest("template", null));

            Map<String, Object> values = response.variableValues();
            assertEquals("Alice", values.get("properties.userName"));
            assertEquals("en", values.get("properties.language"));
            assertEquals("alice@example.com", values.get("properties.email"));
            assertEquals("user-12345", values.get("userInfo.userId"));
            assertEquals("conv-67890", values.get("conversationInfo.conversationId"));
            assertEquals("agent-abc", values.get("conversationInfo.agentId"));
            assertEquals("1", values.get("conversationInfo.agentVersion"));
            assertEquals("What is my order status?", values.get("input"));
        }
    }

    // ==================== Conversation Memory Loading ====================

    @Nested
    class ConversationMemoryLoading {

        @Test
        void shouldLoadRealConversationData() throws Exception {
            var snapshot = new ConversationMemorySnapshot();
            snapshot.setConversationId("conv-123");
            snapshot.setAgentId("agent-1");
            snapshot.setAgentVersion(1);

            when(conversationMemoryStore.loadConversationMemorySnapshot("conv-123"))
                    .thenReturn(snapshot);

            Map<String, Object> mockData = new LinkedHashMap<>();
            mockData.put("properties", Map.of("foo", "bar"));
            mockData.put("input", "hello");
            when(memoryItemConverter.convert(any(IConversationMemory.class)))
                    .thenReturn(mockData);
            when(templatingEngine.processTemplate(anyString(), anyMap()))
                    .thenReturn("resolved from real data");

            TemplatePreviewResponse response = restTemplatePreview.previewTemplate(
                    new TemplatePreviewRequest("{{properties.foo}}", "conv-123"));

            assertEquals("resolved from real data", response.resolved());
            assertNull(response.error());
            verify(conversationMemoryStore).loadConversationMemorySnapshot("conv-123");
        }

        @Test
        void shouldReturnErrorWhenConversationNotFound() throws Exception {
            when(conversationMemoryStore.loadConversationMemorySnapshot("missing-conv"))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("Not found"));

            TemplatePreviewResponse response = restTemplatePreview.previewTemplate(
                    new TemplatePreviewRequest("template", "missing-conv"));

            assertNull(response.resolved());
            assertNotNull(response.error());
            assertTrue(response.error().contains("missing-conv"),
                    "Error should reference the conversation ID");
            assertTrue(response.availableVariables().isEmpty());
        }

        @Test
        void shouldReturnErrorWhenStoreThrowsResourceStoreException() throws Exception {
            when(conversationMemoryStore.loadConversationMemorySnapshot("bad-conv"))
                    .thenThrow(new IResourceStore.ResourceStoreException("DB error"));

            TemplatePreviewResponse response = restTemplatePreview.previewTemplate(
                    new TemplatePreviewRequest("template", "bad-conv"));

            assertNull(response.resolved());
            assertNotNull(response.error());
            assertTrue(response.error().contains("bad-conv"));
        }

        @Test
        void shouldNotCallTemplatingEngineWhenConversationNotFound() throws Exception {
            when(conversationMemoryStore.loadConversationMemorySnapshot("missing"))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("nope"));

            restTemplatePreview.previewTemplate(
                    new TemplatePreviewRequest("template", "missing"));

            verifyNoInteractions(templatingEngine);
        }
    }

    // ==================== Prompt Snippet Injection ====================

    @Nested
    class PromptSnippetInjection {

        @Test
        void shouldInjectSnippetsIntoTemplateData() throws Exception {
            Map<String, Object> snippets = Map.of("safety", "Always verify facts.");
            when(promptSnippetService.getAll()).thenReturn(snippets);
            when(templatingEngine.processTemplate(eq("{{snippets.safety}}"), anyMap()))
                    .thenAnswer(inv -> {
                        Map<String, Object> data = inv.getArgument(1);
                        // Verify snippets are present in the data passed to the engine
                        assertNotNull(data.get("snippets"), "snippets should be injected");
                        return "Always verify facts.";
                    });

            TemplatePreviewResponse response = restTemplatePreview.previewTemplate(
                    new TemplatePreviewRequest("{{snippets.safety}}", null));

            assertEquals("Always verify facts.", response.resolved());
        }

        @Test
        void shouldIncludeSnippetKeysInAvailableVariables() throws Exception {
            Map<String, Object> snippets = Map.of("greeting", "Hello!", "farewell", "Goodbye!");
            when(promptSnippetService.getAll()).thenReturn(snippets);
            when(templatingEngine.processTemplate(anyString(), anyMap()))
                    .thenReturn("ok");

            TemplatePreviewResponse response = restTemplatePreview.previewTemplate(
                    new TemplatePreviewRequest("template", null));

            List<String> vars = response.availableVariables();
            assertTrue(vars.contains("snippets.greeting"), "Should list snippets.greeting");
            assertTrue(vars.contains("snippets.farewell"), "Should list snippets.farewell");
        }

        @Test
        void shouldNotAddSnippetsKeyWhenSnippetsAreEmpty() throws Exception {
            when(promptSnippetService.getAll()).thenReturn(Map.of());
            when(templatingEngine.processTemplate(eq("test"), anyMap()))
                    .thenAnswer(inv -> {
                        Map<String, Object> data = inv.getArgument(1);
                        assertNull(data.get("snippets"), "snippets key should not exist when empty");
                        return "test";
                    });

            restTemplatePreview.previewTemplate(
                    new TemplatePreviewRequest("test", null));
        }
    }

    // ==================== Template Resolution ====================

    @Nested
    class TemplateResolution {

        @Test
        void shouldResolveTemplateWithSampleData() throws Exception {
            when(templatingEngine.processTemplate(eq("Hello {properties.userName}!"), anyMap()))
                    .thenReturn("Hello Alice!");

            TemplatePreviewResponse response = restTemplatePreview.previewTemplate(
                    new TemplatePreviewRequest("Hello {properties.userName}!", null));

            assertEquals("Hello Alice!", response.resolved());
            assertNull(response.error());
        }

        @Test
        void shouldReturnErrorOnTemplateEngineException() throws Exception {
            when(templatingEngine.processTemplate(anyString(), anyMap()))
                    .thenThrow(new ITemplatingEngine.TemplateEngineException(
                            "Rendering error: missing key", null));

            TemplatePreviewResponse response = restTemplatePreview.previewTemplate(
                    new TemplatePreviewRequest("{invalid.template}", null));

            assertNull(response.resolved());
            assertNotNull(response.error());
            assertTrue(response.error().contains("Rendering error"));
        }

        @Test
        void shouldStillReturnVariablesOnTemplateError() throws Exception {
            when(templatingEngine.processTemplate(anyString(), anyMap()))
                    .thenThrow(new ITemplatingEngine.TemplateEngineException("bad template", null));

            TemplatePreviewResponse response = restTemplatePreview.previewTemplate(
                    new TemplatePreviewRequest("{broken}", null));

            assertNotNull(response.error());
            // Variables should still be populated for the reference panel
            assertFalse(response.availableVariables().isEmpty(),
                    "Variables should be returned even on template error");
            assertFalse(response.variableValues().isEmpty(),
                    "Variable values should be returned even on template error");
        }

        @Test
        void shouldPassTemplateStringVerbatimToEngine() throws Exception {
            String verbatimTemplate = "  \n{{complex.template}} with {{nested.values}}\n  ";
            when(templatingEngine.processTemplate(eq(verbatimTemplate), anyMap()))
                    .thenReturn("resolved");

            restTemplatePreview.previewTemplate(
                    new TemplatePreviewRequest(verbatimTemplate, null));

            verify(templatingEngine).processTemplate(eq(verbatimTemplate), anyMap());
        }
    }

    // ==================== Variable Flattening ====================

    @Nested
    class VariableFlattening {

        @Test
        void shouldFlattenNestedMapsIntoDotPaths() throws Exception {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("level1", Map.of("level2", Map.of("leaf", "value")));

            when(conversationMemoryStore.loadConversationMemorySnapshot("conv-flat"))
                    .thenReturn(new ConversationMemorySnapshot());
            when(memoryItemConverter.convert(any(IConversationMemory.class)))
                    .thenReturn(data);
            when(templatingEngine.processTemplate(anyString(), anyMap()))
                    .thenReturn("ok");

            TemplatePreviewResponse response = restTemplatePreview.previewTemplate(
                    new TemplatePreviewRequest("template", "conv-flat"));

            assertTrue(response.availableVariables().contains("level1.level2.leaf"));
            assertEquals("value", response.variableValues().get("level1.level2.leaf"));
        }

        @Test
        void shouldTruncateLongStringValues() throws Exception {
            String longString = "x".repeat(300);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("longField", longString);

            when(conversationMemoryStore.loadConversationMemorySnapshot("conv-long"))
                    .thenReturn(new ConversationMemorySnapshot());
            when(memoryItemConverter.convert(any(IConversationMemory.class)))
                    .thenReturn(data);
            when(templatingEngine.processTemplate(anyString(), anyMap()))
                    .thenReturn("ok");

            TemplatePreviewResponse response = restTemplatePreview.previewTemplate(
                    new TemplatePreviewRequest("template", "conv-long"));

            String truncated = (String) response.variableValues().get("longField");
            assertNotNull(truncated);
            assertEquals(201, truncated.length(), "Should be exactly 200 chars + 1 ellipsis character");
            assertTrue(truncated.startsWith("x".repeat(200)), "First 200 chars should be preserved");
            assertTrue(truncated.endsWith("…"), "Should end with ellipsis");
        }

        @Test
        void shouldShowListSizeInsteadOfContent() throws Exception {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("items", List.of("a", "b", "c"));

            when(conversationMemoryStore.loadConversationMemorySnapshot("conv-list"))
                    .thenReturn(new ConversationMemorySnapshot());
            when(memoryItemConverter.convert(any(IConversationMemory.class)))
                    .thenReturn(data);
            when(templatingEngine.processTemplate(anyString(), anyMap()))
                    .thenReturn("ok");

            TemplatePreviewResponse response = restTemplatePreview.previewTemplate(
                    new TemplatePreviewRequest("template", "conv-list"));

            assertEquals("3 items", response.variableValues().get("items"));
        }

        @Test
        void shouldRespectMaxDepthLimit() throws Exception {
            // Build a linear chain: a.b.c.d.e.f = "deepValue"
            // maxDepth is 4, so flattening should stop at depth 4
            Map<String, Object> level5 = new LinkedHashMap<>();
            level5.put("f", "deepValue");
            Map<String, Object> level4 = new LinkedHashMap<>();
            level4.put("e", level5);
            Map<String, Object> level3 = new LinkedHashMap<>();
            level3.put("d", level4);
            Map<String, Object> level2 = new LinkedHashMap<>();
            level2.put("c", level3);
            Map<String, Object> level1 = new LinkedHashMap<>();
            level1.put("b", level2);
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("a", level1);
            // Also add a shallow key to verify it still works
            root.put("shallow", "yes");

            when(conversationMemoryStore.loadConversationMemorySnapshot("conv-deep"))
                    .thenReturn(new ConversationMemorySnapshot());
            when(memoryItemConverter.convert(any(IConversationMemory.class)))
                    .thenReturn(root);
            when(templatingEngine.processTemplate(anyString(), anyMap()))
                    .thenReturn("ok");

            TemplatePreviewResponse response = restTemplatePreview.previewTemplate(
                    new TemplatePreviewRequest("template", "conv-deep"));

            List<String> vars = response.availableVariables();
            // Shallow key always works
            assertTrue(vars.contains("shallow"), "Shallow key should be present");
            // Depth 4: a.b.c.d is at depth 4, but d holds a map (level4),
            // so it would recurse into d—but that's the 4th recursion which
            // hits maxDepth=0 and stops. So a.b.c.d won't appear as a leaf.
            // Depth 3: a.b.c is at depth 3, d is a non-empty map at depth 4 → stops
            // The 5th and 6th levels (a.b.c.d.e, a.b.c.d.e.f) must NOT appear
            assertFalse(vars.contains("a.b.c.d.e.f"),
                    "6-deep key must not appear (exceeds maxDepth)");
            assertFalse(vars.contains("a.b.c.d.e"),
                    "5-deep key must not appear (exceeds maxDepth)");
        }

        @Test
        void shouldHandleEmptyMapCorrectly() throws Exception {
            Map<String, Object> data = new LinkedHashMap<>();

            when(conversationMemoryStore.loadConversationMemorySnapshot("conv-empty"))
                    .thenReturn(new ConversationMemorySnapshot());
            when(memoryItemConverter.convert(any(IConversationMemory.class)))
                    .thenReturn(data);
            when(templatingEngine.processTemplate(anyString(), anyMap()))
                    .thenReturn("ok");

            TemplatePreviewResponse response = restTemplatePreview.previewTemplate(
                    new TemplatePreviewRequest("template", "conv-empty"));

            assertTrue(response.availableVariables().isEmpty());
            assertTrue(response.variableValues().isEmpty());
        }
    }

    // ==================== Integration-style ====================

    @Nested
    class EndToEnd {

        @Test
        void shouldCombineSnippetsAndConversationData() throws Exception {
            // Real conversation data
            Map<String, Object> convData = new LinkedHashMap<>();
            convData.put("properties", Map.of("name", "Bob"));

            when(conversationMemoryStore.loadConversationMemorySnapshot("conv-e2e"))
                    .thenReturn(new ConversationMemorySnapshot());
            when(memoryItemConverter.convert(any(IConversationMemory.class)))
                    .thenReturn(convData);

            // Snippets
            when(promptSnippetService.getAll()).thenReturn(Map.of("tone", "formal"));

            when(templatingEngine.processTemplate(eq("Hello {properties.name}, tone={snippets.tone}"), anyMap()))
                    .thenReturn("Hello Bob, tone=formal");

            TemplatePreviewResponse response = restTemplatePreview.previewTemplate(
                    new TemplatePreviewRequest("Hello {properties.name}, tone={snippets.tone}", "conv-e2e"));

            assertEquals("Hello Bob, tone=formal", response.resolved());
            assertNull(response.error());

            // Both conversation vars and snippet vars should appear
            assertTrue(response.availableVariables().contains("properties.name"));
            assertTrue(response.availableVariables().contains("snippets.tone"));
        }

        @Test
        void shouldAlwaysCallPromptSnippetService() throws Exception {
            when(templatingEngine.processTemplate(anyString(), anyMap()))
                    .thenReturn("ok");

            restTemplatePreview.previewTemplate(
                    new TemplatePreviewRequest("test", null));

            verify(promptSnippetService).getAll();
        }
    }
}
