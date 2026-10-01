/*
 * Copyright EDDI contributors
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** The types backed by a dedicated builder; catalog ids may not reuse them. */
    static final Set<String> BUILT_IN_TYPES = Set.of(LLM_TYPE_OPENAI, LLM_TYPE_HUGGING_FACE, LLM_TYPE_ANTHROPIC, LLM_TYPE_GEMINI_VERTEX,
            LLM_TYPE_GEMINI, LLM_TYPE_OLLAMA, LLM_TYPE_JLAMA, LLM_TYPE_MISTRAL, LLM_TYPE_AZURE_OPENAI, LLM_TYPE_BEDROCK, LLM_TYPE_ORACLE_GENAI);

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

        // Named OpenAI-compatible providers (xAI, DeepSeek, ...) share one builder
        // class, parameterised by their catalog preset.
        registerCompatibleProviders(OpenAiCompatibleProviders.all(), languageModelApiConnectorBuilders, langModelBuilderInstance);

        lifecycleTaskProviders.put(LlmTask.ID, () -> lifecycleTaskInstance.select(LlmTask.class).get());
        // V5 alias: ai.labs.langchain -> ai.labs.llm. The rename migration rewrites the
        // step type, but only on the boot that runs it: a database migrated by 6.0-6.4
        // recorded the migration complete with the old type still in its workflows,
        // and a v5 ZIP imports it verbatim. Both then failed to deploy with
        // "Extension 'ai.labs.langchain' not found".
        lifecycleTaskProviders.put("ai.labs.langchain", lifecycleTaskProviders.get(LlmTask.ID));
        LOGGER.debug("Added LLM Module, current size of lifecycle modules " + lifecycleTaskProviders.size());
    }

    /**
     * Registers one builder per catalog provider, refusing an id that is taken by a
     * built-in type or repeated within the catalog.
     * <p>
     * The guard checks {@link #BUILT_IN_TYPES}, not the map's current keys:
     * {@link #configure()} is both {@code @PostConstruct} and {@code @Inject}, so
     * CDI runs it twice, and a key check would reject the providers this method
     * registered on the first pass — which aborted startup.
     */
    static void registerCompatibleProviders(List<OpenAiCompatibleProvider> providers,
                                            Map<String, Provider<ILanguageModelBuilder>> builders,
                                            Instance<ILanguageModelBuilder> builderInstance) {
        Set<String> seen = new HashSet<>();
        for (OpenAiCompatibleProvider provider : providers) {
            if (BUILT_IN_TYPES.contains(provider.id())) {
                throw new IllegalStateException("OpenAI-compatible provider id '" + provider.id() + "' collides with a built-in LLM type");
            }
            if (!seen.add(provider.id())) {
                throw new IllegalStateException("OpenAI-compatible provider id '" + provider.id() + "' is declared twice");
            }
            builders.put(provider.id(), () -> new OpenAiCompatibleLanguageModelBuilder(provider,
                    builderInstance.select(OpenAILanguageModelBuilder.class).get()));
        }
    }
}
