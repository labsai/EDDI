package ai.labs.eddi.modules.langchain.impl.builder;

import dev.langchain4j.model.chat.ChatLanguageModel;

import java.util.Map;

public interface ILanguageModelBuilder {
    ChatLanguageModel build(Map<String, String> parameters);
}
