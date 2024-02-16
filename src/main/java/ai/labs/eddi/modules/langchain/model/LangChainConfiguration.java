package ai.labs.eddi.modules.langchain.model;

import java.util.List;

public record LangChainConfiguration(List<String> actions, String systemMessage, String authKey, Integer logSizeLimit,
                                     Boolean convertToObject, String objectPath) {
}
