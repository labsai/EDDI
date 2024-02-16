package ai.labs.eddi.modules.langchain.model;

import dev.ai4j.openai4j.OpenAiClient;

public record OpenAIWrapper(LangChainConfiguration langChainConfiguration, OpenAiClient openAiClient) {
}
