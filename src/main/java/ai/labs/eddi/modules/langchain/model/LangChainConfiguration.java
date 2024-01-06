package ai.labs.eddi.modules.langchain.model;

public record LangChainConfiguration(String systemMessage, String authKey, Integer logSizeLimit,
                                     Boolean convertToObject) {
}
