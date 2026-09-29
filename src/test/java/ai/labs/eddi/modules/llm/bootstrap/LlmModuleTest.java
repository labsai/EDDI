/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.bootstrap;

import ai.labs.eddi.modules.llm.impl.builder.ILanguageModelBuilder;
import ai.labs.eddi.modules.llm.impl.builder.OpenAILanguageModelBuilder;
import ai.labs.eddi.modules.llm.impl.builder.OpenAiCompatibleLanguageModelBuilder;
import ai.labs.eddi.modules.llm.impl.builder.OpenAiCompatibleProvider;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Provider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("LlmModule")
class LlmModuleTest {

    private static final Set<String> COMPATIBLE_IDS = Set.of("xai", "deepseek", "moonshot", "qwen", "zhipu", "minimax", "openrouter", "groq");

    @SuppressWarnings("unchecked")
    private static LlmModule module(Map<String, Provider<ILanguageModelBuilder>> builders) {
        Instance<ILanguageModelBuilder> instance = mock(Instance.class);
        Instance<ILanguageModelBuilder> selected = mock(Instance.class);
        when(instance.select(eq(OpenAILanguageModelBuilder.class))).thenReturn((Instance) selected);
        when(selected.get()).thenReturn(new OpenAILanguageModelBuilder());
        var module = new LlmModule(new HashMap<>(), mock(Instance.class), instance);
        module.configure();
        builders.putAll(module.getLanguageModelApiConnectorBuilders());
        return module;
    }

    @Test
    @DisplayName("registers a builder for every built-in type and all eight named compatible providers")
    void registersCompatibleProviders() {
        Map<String, Provider<ILanguageModelBuilder>> builders = new HashMap<>();
        module(builders);

        assertTrue(builders.keySet().containsAll(COMPATIBLE_IDS), builders.keySet().toString());
        assertTrue(builders.containsKey(LlmModule.LLM_TYPE_OPENAI));
        for (String id : COMPATIBLE_IDS) {
            assertInstanceOf(OpenAiCompatibleLanguageModelBuilder.class, builders.get(id).get(), id);
        }
    }

    @Test
    @DisplayName("configure() survives running twice, as CDI does for a @PostConstruct @Inject method")
    void configureIsIdempotent() {
        Map<String, Provider<ILanguageModelBuilder>> builders = new HashMap<>();
        var module = module(builders);

        assertDoesNotThrow(module::configure);
        assertTrue(module.getLanguageModelApiConnectorBuilders().keySet().containsAll(COMPATIBLE_IDS));
    }

    @Test
    @DisplayName("a provider id colliding with a built-in type is refused")
    void collisionThrows() {
        Map<String, Provider<ILanguageModelBuilder>> builders = new HashMap<>();
        var clash = provider("openai");
        var e = assertThrows(IllegalStateException.class, () -> LlmModule.registerCompatibleProviders(List.of(clash), builders, null));
        assertTrue(e.getMessage().contains("'openai'") && e.getMessage().contains("collides"), e.getMessage());
    }

    @Test
    @DisplayName("a provider id declared twice in the catalog is refused")
    void duplicateThrows() {
        Map<String, Provider<ILanguageModelBuilder>> builders = new HashMap<>();
        var e = assertThrows(IllegalStateException.class,
                () -> LlmModule.registerCompatibleProviders(List.of(provider("dup"), provider("dup")), builders, null));
        assertTrue(e.getMessage().contains("'dup'") && e.getMessage().contains("twice"), e.getMessage());
    }

    private static OpenAiCompatibleProvider provider(String id) {
        return new OpenAiCompatibleProvider(id, "Test", "https://a.example", List.of(), "m", List.of("m"), null, null, null, null, null);
    }
}
