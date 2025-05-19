package ai.labs.eddi.modules.langchain.impl.builder;

import dev.langchain4j.model.chat.ChatModel;

import java.util.Map;

public interface ILanguageModelBuilder {
    ChatModel build(Map<String, String> parameters);
}
