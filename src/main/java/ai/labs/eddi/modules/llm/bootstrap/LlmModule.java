/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.bootstrap;

import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.bootstrap.LifecycleExtensions;
import ai.labs.eddi.modules.llm.impl.LlmTask;
import ai.labs.eddi.modules.llm.impl.builder.*;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;

@Startup(1000)
@ApplicationScoped
public class LlmModule {
    private static final Logger LOGGER = Logger.getLogger("Startup");

    public static final String LLM_TYPE_OPENAI = "openai";
    public static final String LLM_TYPE_HUGGING_FACE = "huggingface";
    public static final String LLM_TYPE_ANTHROPIC = "anthropic";
    public static final String LLM_TYPE_GEMINI_VERTEX = "gemini-vertex";
    public static final String LLM_TYPE_GEMINI = "gemini";
    public static final String LLM_TYPE_OLLAMA = "ollama";
    public static final String LLM_TYPE_JLAMA = "jlama";
    public static final String LLM_TYPE_MISTRAL = "mistral";
    public static final String LLM_TYPE_AZURE_OPENAI = "azure-openai";
    public static final String LLM_TYPE_BEDROCK = "bedrock";
    public static final String LLM_TYPE_ORACLE_GENAI = "oracle-genai";

    private final Map<String, Provider<ILifecycleTask>> lifecycleTaskProviders;
    private final Instance<ILifecycleTask> lifecycleTaskInstance;
    private final Instance<ILanguageModelBuilder> langModelBuilderInstance;

    private final Map<String, Provider<ILanguageModelBuilder>> languageModelApiConnectorBuilders = new HashMap<>();

    @ApplicationScoped
    public Map<String, Provider<ILanguageModelBuilder>> getLanguageModelApiConnectorBuilders() {
        return languageModelApiConnectorBuilders;
    }

    public LlmModule(@LifecycleExtensions Map<String, Provider<ILifecycleTask>> lifecycleTaskProviders,
            Instance<ILifecycleTask> lifecycleTaskInstance, Instance<ILanguageModelBuilder> langModelBuilderInstance) {

        this.lifecycleTaskProviders = lifecycleTaskProviders;
        this.lifecycleTaskInstance = lifecycleTaskInstance;
        this.langModelBuilderInstance = langModelBuilderInstance;
    }

    @PostConstruct
    @Inject
    protected void configure() {
        languageModelApiConnectorBuilders.put(LLM_TYPE_OPENAI, () -> langModelBuilderInstance.select(OpenAILanguageModelBuilder.class).get());
        languageModelApiConnectorBuilders.put(LLM_TYPE_HUGGING_FACE,
                () -> langModelBuilderInstance.select(HuggingFaceLanguageModelBuilder.class).get());
        languageModelApiConnectorBuilders.put(LLM_TYPE_ANTHROPIC, () -> langModelBuilderInstance.select(AnthropicLanguageModelBuilder.class).get());
        languageModelApiConnectorBuilders.put(LLM_TYPE_GEMINI_VERTEX,
                () -> langModelBuilderInstance.select(VertexGeminiLanguageModelBuilder.class).get());
        languageModelApiConnectorBuilders.put(LLM_TYPE_GEMINI, () -> langModelBuilderInstance.select(GeminiLanguageModelBuilder.class).get());
        languageModelApiConnectorBuilders.put(LLM_TYPE_OLLAMA, () -> langModelBuilderInstance.select(OllamaLanguageModelBuilder.class).get());
        languageModelApiConnectorBuilders.put(LLM_TYPE_JLAMA, () -> langModelBuilderInstance.select(JlamaLanguageModelBuilder.class).get());
        languageModelApiConnectorBuilders.put(LLM_TYPE_MISTRAL, () -> langModelBuilderInstance.select(MistralAiLanguageModelBuilder.class).get());
        languageModelApiConnectorBuilders.put(LLM_TYPE_AZURE_OPENAI,
                () -> langModelBuilderInstance.select(AzureOpenAiLanguageModelBuilder.class).get());
        languageModelApiConnectorBuilders.put(LLM_TYPE_BEDROCK, () -> langModelBuilderInstance.select(BedrockLanguageModelBuilder.class).get());
        languageModelApiConnectorBuilders.put(LLM_TYPE_ORACLE_GENAI,
                () -> langModelBuilderInstance.select(OracleGenAiLanguageModelBuilder.class).get());

        lifecycleTaskProviders.put(LlmTask.ID, () -> lifecycleTaskInstance.select(LlmTask.class).get());
        LOGGER.debug("Added LLM Module, current size of lifecycle modules " + lifecycleTaskProviders.size());
    }
}
