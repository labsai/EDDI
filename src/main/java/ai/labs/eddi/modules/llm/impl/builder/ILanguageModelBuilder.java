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

    /**
     * The parameter keys that carry a credential, and so are the only ones a
     * {@code ${vault:…}} reference may sit in.
     * <p>
     * {@code ChatModelRegistry} refuses a configuration with a vault reference in
     * any other key before it resolves anything. A secret resolved into
     * {@code modelName} is not used as a credential — it is sent as data, and comes
     * back in the provider's "model X does not exist" error, in the client-visible
     * {@code taskErrors} and in the {@code gen_ai.request.model} span attribute. An
     * editor with their own working key could otherwise read any secret of the
     * vault back that way.
     * <p>
     * The empty default fails closed: a builder that does not declare its
     * credential keys accepts no vault reference at all.
     */
    default Set<String> secretParameters() {
        return Set.of();
    }

    /**
     * The endpoint this builder will send to when the configuration names none of
     * the endpoint keys it reads — or {@code null} when there is no such default.
     * <p>
     * Every builder with a default destination names it: the vendor's fixed host
     * ({@code api.openai.com}), the host a parameter selects (an OpenAI-compatible
     * preset's {@code region}, Bedrock's {@code region}, Vertex's
     * {@code location}). The registry checks it against every referenced secret's
     * {@code allowedHosts} before resolving them. A fixed vendor host is not
     * exempt: the editor chooses the provider {@code type}, so a secret restricted
     * to one vendor would otherwise be sent to any other vendor's host by choosing
     * that vendor's type and leaving the endpoint out.
     * <p>
     * A builder reports no default once the endpoint key it reads is set.
     *
     * @param parameters
     *            the builder parameters with global variables resolved and vault
     *            references still in place
     */
    default String defaultEndpoint(Map<String, String> parameters) {
        return null;
    }

    /**
     * {@code value} when {@code key} is not set to a non-blank value in
     * {@code parameters}, else {@code null} — the shape of most
     * {@link #defaultEndpoint} implementations.
     */
    static String unlessSet(Map<String, String> parameters, String key, String value) {
        String configured = parameters == null ? null : parameters.get(key);
        return configured == null || configured.isBlank() ? value : null;
    }
}
