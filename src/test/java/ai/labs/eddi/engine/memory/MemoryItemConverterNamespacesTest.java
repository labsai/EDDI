/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.modules.llm.impl.PromptSnippetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * I6 — {@code snippets} and {@code vars} are part of the documented template
 * data model produced by {@code MemoryItemConverter.convert(memory)}, so they
 * must resolve in EVERY template context (output sets, httpCall bodies,
 * property instructions) — not only inside {@code LlmTask}.
 */
class MemoryItemConverterNamespacesTest {

    private MemoryItemConverter converter;
    private PromptSnippetService promptSnippetService;
    private GlobalVariableResolver globalVariableResolver;

    @BeforeEach
    void setUp() {
        converter = new MemoryItemConverter();
        promptSnippetService = mock(PromptSnippetService.class);
        globalVariableResolver = mock(GlobalVariableResolver.class);
        converter.promptSnippetService = promptSnippetService;
        converter.globalVariableResolver = globalVariableResolver;
    }

    @Test
    @DisplayName("I6 — snippets and vars are part of the template data model")
    void snippetsAndVarsAreInjected() {
        when(promptSnippetService.getAll()).thenReturn(Map.of("cautious_mode", "Be careful."));
        when(globalVariableResolver.getTemplateData()).thenReturn(Map.of("default-model", "gpt-5"));

        var memory = new ConversationMemory("conv-1", "agent-1", 1, "user-1");
        Map<String, Object> result = converter.convert(memory);

        assertEquals(Map.of("cautious_mode", "Be careful."), result.get("snippets"),
                "{snippets.x} must resolve outside LlmTask too");
        assertEquals(Map.of("default-model", "gpt-5"), result.get("vars"),
                "{vars.x} must resolve outside LlmTask too");
    }

    @Test
    @DisplayName("I6 — empty namespaces are omitted rather than rendered as empty maps")
    void emptyNamespacesAreOmitted() {
        when(promptSnippetService.getAll()).thenReturn(Map.of());
        when(globalVariableResolver.getTemplateData()).thenReturn(Map.of());

        var memory = new ConversationMemory("conv-1", "agent-1", 1, "user-1");
        Map<String, Object> result = converter.convert(memory);

        assertFalse(result.containsKey("snippets"));
        assertFalse(result.containsKey("vars"));
    }

    @Test
    @DisplayName("a client-supplied context variable named 'vars'/'snippets' is NOT clobbered by the namespaces")
    void contextVariablesKeepPrecedenceOverTheNamespaces() {
        when(promptSnippetService.getAll()).thenReturn(Map.of("cautious_mode", "Be careful."));
        when(globalVariableResolver.getTemplateData()).thenReturn(Map.of("default-model", "gpt-5"));

        var memory = new ConversationMemory("conv-1", "agent-1", 1, "user-1");
        // The context map is flattened onto the top level BEFORE the namespaces are
        // injected, so a plain put() silently replaced the caller's data.
        var callerVars = new Context(Context.ContextType.object, Map.of("region", "eu-central-1"));
        var callerSnippets = new Context(Context.ContextType.object, Map.of("tone", "formal"));
        memory.getCurrentStep().storeData(new Data<>("context:vars", callerVars));
        memory.getCurrentStep().storeData(new Data<>("context:snippets", callerSnippets));

        Map<String, Object> result = converter.convert(memory);

        assertEquals(Map.of("region", "eu-central-1"), result.get("vars"),
                "{vars.region} resolved to the caller's context before the namespace was injected here");
        assertEquals(Map.of("tone", "formal"), result.get("snippets"),
                "{snippets.tone} resolved to the caller's context before the namespace was injected here");
        // The namespaces are still reachable through the nested context view.
        assertEquals(callerVars.getValue(), ((Map<?, ?>) result.get("context")).get("vars"));
    }

    @Test
    @DisplayName("I6 — a failing snippet/variable lookup must not break the turn")
    void resolutionFailureDoesNotBreakTheTurn() {
        when(promptSnippetService.getAll()).thenThrow(new IllegalStateException("store down"));
        when(globalVariableResolver.getTemplateData()).thenThrow(new IllegalStateException("store down"));

        var memory = new ConversationMemory("conv-1", "agent-1", 1, "user-1");
        Map<String, Object> result = converter.convert(memory);

        assertFalse(result.containsKey("snippets"));
        assertFalse(result.containsKey("vars"));
        assertTrue(result.containsKey("conversationLog"), "the rest of the template model is unaffected");
    }
}
