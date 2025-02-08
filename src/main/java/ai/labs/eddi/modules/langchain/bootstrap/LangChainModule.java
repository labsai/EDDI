package ai.labs.eddi.modules.langchain.bootstrap;


import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.bootstrap.LifecycleExtensions;
import ai.labs.eddi.modules.langchain.impl.LangchainTask;
import ai.labs.eddi.modules.langchain.impl.builder.*;
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
public class LangChainModule {
    private static final Logger LOGGER = Logger.getLogger("Startup");

    public static final String LLM_TYPE_OPENAI = "openai";
    public static final String LLM_TYPE_HUGGING_FACE = "huggingface";
    public static final String LLM_TYPE_ANTHROPIC = "anthropic";
    public static final String LLM_TYPE_GEMINI_VERTEX = "gemini-vertex";
    public static final String LLM_TYPE_GEMINI = "gemini";
    public static final String LLM_TYPE_OLLAMA = "ollama";
    public static final String LLM_TYPE_JLAMA = "jlama";

    private final Map<String, Provider<ILifecycleTask>> lifecycleTaskProviders;
    private final Instance<ILifecycleTask> lifecycleTaskInstance;
    private final Instance<ILanguageModelBuilder> langModelBuilderInstance;

    private final Map<String, Provider<ILanguageModelBuilder>> languageModelApiConnectorBuilders = new HashMap<>();

    @ApplicationScoped
    public Map<String, Provider<ILanguageModelBuilder>> getLanguageModelApiConnectorBuilders() {
        return languageModelApiConnectorBuilders;
    }

    public LangChainModule(@LifecycleExtensions Map<String, Provider<ILifecycleTask>> lifecycleTaskProviders,
                           Instance<ILifecycleTask> lifecycleTaskInstance,
                           Instance<ILanguageModelBuilder> langModelBuilderInstance) {

        this.lifecycleTaskProviders = lifecycleTaskProviders;
        this.lifecycleTaskInstance = lifecycleTaskInstance;
        this.langModelBuilderInstance = langModelBuilderInstance;
    }

    @PostConstruct
    @Inject
    protected void configure() {
        languageModelApiConnectorBuilders.put(LLM_TYPE_OPENAI, () ->
                langModelBuilderInstance.select(OpenAILanguageModelBuilder.class).get());
        languageModelApiConnectorBuilders.put(LLM_TYPE_HUGGING_FACE, () ->
                langModelBuilderInstance.select(HuggingFaceLanguageModelBuilder.class).get());
        languageModelApiConnectorBuilders.put(LLM_TYPE_ANTHROPIC, () ->
                langModelBuilderInstance.select(AnthropicLanguageModelBuilder.class).get());
        languageModelApiConnectorBuilders.put(LLM_TYPE_GEMINI_VERTEX, () ->
                langModelBuilderInstance.select(VertexGeminiLanguageModelBuilder.class).get());
        languageModelApiConnectorBuilders.put(LLM_TYPE_GEMINI, () ->
                langModelBuilderInstance.select(GeminiLanguageModelBuilder.class).get());
        languageModelApiConnectorBuilders.put(LLM_TYPE_OLLAMA, () ->
                langModelBuilderInstance.select(OllamaLanguageModelBuilder.class).get());
        languageModelApiConnectorBuilders.put(LLM_TYPE_JLAMA, () ->
                langModelBuilderInstance.select(JlamaLanguageModelBuilder.class).get());

        lifecycleTaskProviders.put(LangchainTask.ID, () -> lifecycleTaskInstance.select(LangchainTask.class).get());
        LOGGER.debug("Added LangChain Module, current size of lifecycle modules " + lifecycleTaskProviders.size());
    }
}
