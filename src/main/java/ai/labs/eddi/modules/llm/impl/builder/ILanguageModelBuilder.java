/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

import java.util.Map;

public interface ILanguageModelBuilder {
    ChatModel build(Map<String, String> parameters);

    /**
     * Build a streaming-capable chat model. Override in builders that support
     * streaming.
     *
     * @throws UnsupportedOperationException
     *             if streaming is not supported by this builder
     */
    default StreamingChatModel buildStreaming(Map<String, String> parameters) {
        throw new UnsupportedOperationException("Streaming is not supported by " + getClass().getSimpleName());
    }
}
