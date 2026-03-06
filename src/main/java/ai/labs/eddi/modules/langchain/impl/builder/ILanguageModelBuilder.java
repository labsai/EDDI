package ai.labs.eddi.modules.langchain.impl.builder;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

import java.util.Map;

public interface ILanguageModelBuilder {
    ChatModel build(Map<String, String> parameters);

    /**
     * Build a streaming-capable chat model. Override in builders that support
     * streaming.
     *
     * @throws UnsupportedOperationException if streaming is not supported by this
     *                                       builder
     */
    default StreamingChatModel buildStreaming(Map<String, String> parameters) {
        throw new UnsupportedOperationException(
                "Streaming is not supported by " + getClass().getSimpleName());
    }
}
