/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

import java.util.Map;
import java.util.Set;

public interface ILanguageModelBuilder {
    /**
     * The {@code maxRetries} every synchronous provider client is built with.
     * <p>
     * langchain4j clients retry on their own by default (two retries), which
     * stacked underneath EDDI's {@code RetryConfiguration.executeWithRetry}: three
     * configured attempts became up to nine provider calls, each of them billed and
     * rate-limited, with backoff the agent designer never configured.
     * {@code executeWithRetry} is the single retry owner, so the clients must not
     * retry. Streaming clients expose no such setting; their retries live in
     * {@code StreamingLegacyChatExecutor}.
     */
    int NO_LIBRARY_RETRIES = 0;

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

    /**
     * The parameter keys this builder actually reads.
     * <p>
     * Declared so that {@code ChatModelRegistry} can tell an agent designer that a
     * configured parameter is being dropped — the failure mode is otherwise
     * completely silent (the model builds, the turn succeeds, the setting simply
     * never reaches the provider).
     * <p>
     * The empty default means "this builder does not declare its parameters"; the
     * check is then skipped rather than reporting every key as unrecognised.
     */
    default Set<String> recognisedParameters() {
        return Set.of();
    }

    /**
     * Log a warning for every configured parameter this builder does not read. Call
     * immediately before {@link #build} / {@link #buildStreaming}, i.e. on the
     * cache miss only, so the warning does not repeat on every conversation turn.
     */
    default void warnAboutUnrecognisedParameters(String provider, Map<String, String> parameters) {
        ModelParameterValues.warnAboutUnrecognisedKeys(provider, parameters, recognisedParameters());
    }
}
