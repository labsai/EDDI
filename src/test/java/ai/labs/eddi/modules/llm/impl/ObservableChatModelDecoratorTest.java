/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Grades the decorator contract of {@link ObservableChatModel} — the part that
 * changed when it stopped overriding {@code chat} and started overriding
 * {@code doChat}.
 *
 * <h2>Why these use a hand-written fake rather than Mockito</h2>
 *
 * The sibling {@code ObservableChatModelTest} mocks {@link ChatModel} and stubs
 * {@code chat(ChatRequest)}. That was fine while the decorator called
 * {@code delegate.chat(...)}, but it does not resemble a real provider: every
 * langchain4j binding implements {@code doChat} and inherits {@code chat} from
 * the interface, which is precisely where the parameter merge and the listener
 * dispatch live. A mock that stubs {@code chat} has neither, so it cannot
 * observe the behaviour this class exists to pin — and it reports a delegate
 * with no {@code defaultRequestParameters} as an NPE rather than as a
 * configuration problem.
 *
 * <p>
 * The fake below has the real shape: {@code doChat} is the extension point,
 * everything else is inherited or explicitly supplied.
 */
@DisplayName("ObservableChatModel decorator contract")
class ObservableChatModelDecoratorTest {

    private static final ChatResponse RESPONSE = ChatResponse.builder().aiMessage(AiMessage.from("Hello!")).build();

    private static ChatRequest request() {
        return ChatRequest.builder().messages(UserMessage.from("hi")).build();
    }

    /**
     * A delegate shaped like a real provider binding: it implements {@code doChat}
     * and inherits {@code chat}.
     */
    private static class FakeChatModel implements ChatModel {
        final AtomicInteger doChatCalls = new AtomicInteger();
        final AtomicInteger chatCalls = new AtomicInteger();
        private final List<ChatModelListener> listeners;

        FakeChatModel(ChatModelListener... listeners) {
            this.listeners = List.of(listeners);
        }

        @Override
        public ChatResponse doChat(ChatRequest chatRequest) {
            doChatCalls.incrementAndGet();
            return RESPONSE;
        }

        @Override
        public ChatResponse chat(ChatRequest chatRequest) {
            chatCalls.incrementAndGet();
            return ChatModel.super.chat(chatRequest);
        }

        @Override
        public ChatRequestParameters defaultRequestParameters() {
            return ChatRequestParameters.builder().modelName("fake-model").build();
        }

        @Override
        public List<ChatModelListener> listeners() {
            return listeners;
        }

        @Override
        public ModelProvider provider() {
            return ModelProvider.OPEN_AI;
        }

        @Override
        public Set<Capability> supportedCapabilities() {
            return Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA);
        }
    }

    /** Records which callbacks fired, and how many times. */
    private static class RecordingListener implements ChatModelListener {
        final List<String> events = new ArrayList<>();

        @Override
        public void onRequest(ChatModelRequestContext context) {
            events.add("request");
        }

        @Override
        public void onResponse(ChatModelResponseContext context) {
            events.add("response");
        }

        @Override
        public void onError(ChatModelErrorContext context) {
            events.add("error");
        }
    }

    // ────────────────────────── always wraps ──────────────────────────

    /**
     * The behaviour change this PR turns on. It used to return the bare model
     * whenever no {@code timeout}/{@code logRequests}/{@code logResponses} was
     * configured — the default for essentially every agent — which is exactly why
     * the default path had no telemetry: there was no decorator to hang a listener
     * on.
     */
    @Test
    @DisplayName("wrap always wraps, including when nothing is configured")
    void wrapAlwaysWraps() {
        var raw = new FakeChatModel();

        assertInstanceOf(ObservableChatModel.class, ObservableChatModel.wrap(raw, "openai", null, null, null, null));
        assertInstanceOf(ObservableChatModel.class, ObservableChatModel.wrap(raw, "openai", "", "", "", null));
        assertInstanceOf(ObservableChatModel.class, ObservableChatModel.wrap(raw, "openai", "   ", "false", "false", null));
        assertInstanceOf(ObservableChatModel.class,
                ObservableChatModel.wrap(raw, "openai", "not-a-number", null, null, null));
    }

    @Test
    @DisplayName("wrapping with nothing configured is behaviour-preserving")
    void wrappingWithNothingConfiguredIsBehaviourPreserving() {
        var raw = new FakeChatModel();
        var wrapped = ObservableChatModel.wrap(raw, "openai", null, null, null, null);

        assertSame(RESPONSE, wrapped.chat(request()));
        assertEquals(1, raw.chatCalls.get());
        assertEquals(1, raw.doChatCalls.get());
        assertEquals(raw.defaultRequestParameters().modelName(), wrapped.defaultRequestParameters().modelName());
        assertEquals(raw.provider(), wrapped.provider());
        assertEquals(raw.supportedCapabilities(), wrapped.supportedCapabilities());
    }

    // ───────────────────── dispatch, once each ─────────────────────

    /**
     * The hazard that decided this decorator's shape, and the reason it does the
     * apparently-clumsy thing.
     * <p>
     * Forwarding to {@code delegate.doChat} would be tidier — one dispatch, no
     * double merge. It is also wrong: a {@link ChatModel} may implement
     * {@code chat(ChatRequest)} instead of {@code doChat}, and EDDI ships one that
     * does ({@code JlamaChatModel}). This fake models that provider, so a decorator
     * calling {@code delegate.doChat} fails here with "Not implemented" rather than
     * in production on every Jlama turn.
     */
    @Test
    @DisplayName("a delegate that implements chat() rather than doChat() still works")
    void delegateImplementingChatOnlyStillWorks() {
        var chatOnly = new ChatModel() {
            final AtomicInteger calls = new AtomicInteger();

            @Override
            public ChatResponse chat(ChatRequest chatRequest) {
                calls.incrementAndGet();
                return RESPONSE;
            }

            @Override
            public ChatRequestParameters defaultRequestParameters() {
                return ChatRequestParameters.builder().modelName("chat-only").build();
            }
        };

        var wrapped = ObservableChatModel.wrap(chatOnly, "jlama", null, null, null, null);

        assertSame(RESPONSE, wrapped.chat(request()),
                "JlamaChatModel overrides chat() and leaves doChat() unimplemented; forwarding to"
                        + " delegate.doChat() would throw \"Not implemented\" on every turn");
        assertEquals(1, chatOnly.calls.get());
    }

    /**
     * EDDI's listener fires once, and so does the provider's — but they are
     * dispatched at different levels. EDDI's comes from this decorator's
     * {@code listeners()}; the provider's comes from the delegate's own
     * {@code chat}. Combining the two lists on the decorator, which is the obvious
     * implementation, would fire the provider's twice.
     */
    @Test
    @DisplayName("EDDI's listener and the delegate's each fire exactly once")
    void eachListenerFiresExactlyOnce() {
        var providerListener = new RecordingListener();
        var eddiListener = new RecordingListener();
        var raw = new FakeChatModel(providerListener);
        var wrapped = ObservableChatModel.wrap(raw, "openai", null, null, null, eddiListener);

        wrapped.chat(request());

        assertEquals(List.of("request", "response"), eddiListener.events,
                "EDDI's telemetry listener fires once, dispatched by this decorator's inherited chat()");
        assertEquals(List.of("request", "response"), providerListener.events,
                "the provider's own listener fires once, dispatched inside the delegate's chat() —"
                        + " if the decorator also carried it, it would appear twice here");
        assertEquals(1, raw.doChatCalls.get(), "and the provider is invoked once");
    }

    /**
     * The delegate's listeners must not be copied onto the decorator. Stated
     * directly, because the combining version passes
     * {@link #eachListenerFiresExactlyOnce()} only by accident of list ordering if
     * someone later "fixes" the dispatch as well.
     */
    @Test
    @DisplayName("the decorator carries only EDDI's listener, never the delegate's")
    void decoratorCarriesOnlyEddisListener() {
        var providerListener = new RecordingListener();
        var eddiListener = new RecordingListener();
        var raw = new FakeChatModel(providerListener);

        assertEquals(List.of(eddiListener),
                ObservableChatModel.wrap(raw, "openai", null, null, null, eddiListener).listeners());
        assertEquals(List.of(),
                ObservableChatModel.wrap(raw, "openai", null, null, null, null).listeners(),
                "with no telemetry listener the decorator adds no dispatch of its own");
    }

    /**
     * {@code onError} is the callback that matters most for an alert, and it only
     * fires if the decorator lets the inherited {@code chat} see the exception.
     */
    @Test
    @DisplayName("a failing delegate reaches onError exactly once, and the exception still propagates")
    void failingDelegateReachesOnError() {
        var eddiListener = new RecordingListener();
        var raw = new FakeChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest chatRequest) {
                throw new IllegalStateException("provider exploded");
            }
        };
        var wrapped = ObservableChatModel.wrap(raw, "openai", null, null, null, eddiListener);

        var thrown = assertThrows(RuntimeException.class, () -> wrapped.chat(request()));

        assertTrue(thrown.getMessage().contains("provider exploded")
                || (thrown.getCause() != null
                        && String.valueOf(thrown.getCause().getMessage()).contains("provider exploded")),
                "the provider's failure must still reach the caller: " + thrown);
        assertEquals(List.of("request", "error"), eddiListener.events);
    }

    /**
     * The production shape of the legacy path, which nothing pinned before.
     * <p>
     * {@code AgentExecutionHelper.executeChatWithRetry} calls
     * {@code chatModel.chat(messages)} — the {@code List<ChatMessage>} overload —
     * and that is the default non-JSON route through {@code LegacyChatExecutor}.
     * Since the decorator is now always applied, that call reaches the interface
     * default, which wraps the messages into a {@code ChatRequest} and funnels into
     * {@link ObservableChatModel#doChat}. The point of this test is that telemetry
     * covers that route too: an earlier concern was that always-wrapping might
     * leave the legacy path unobserved.
     * <p>
     * No real binding can tell the difference — {@code chat(List<ChatMessage>)}'s
     * default is a two-line wrapper around {@code chat(ChatRequest)}, and no
     * langchain4j binding overrides it.
     */
    @Test
    @DisplayName("the legacy messages overload is observed too")
    void legacyMessagesOverloadIsObserved() {
        var eddiListener = new RecordingListener();
        var raw = new FakeChatModel();
        var wrapped = ObservableChatModel.wrap(raw, "openai", null, null, null, eddiListener);

        assertSame(RESPONSE, wrapped.chat(List.of(UserMessage.from("hi"))));

        assertEquals(List.of("request", "response"), eddiListener.events,
                "AgentExecutionHelper calls chat(messages); if that route skipped the listener, the default"
                        + " non-JSON legacy path would produce no telemetry at all");
        assertEquals(1, raw.doChatCalls.get());
    }

    // ────────────────────────── delegation ──────────────────────────

    /**
     * {@code defaultRequestParameters()} has to delegate, not return a fresh empty
     * object: the inherited {@code chat} merges it into every request, so a
     * decorator that supplied its own would silently discard the provider's
     * configured model name, temperature and token limits.
     */
    @Test
    @DisplayName("defaultRequestParameters delegates, so the inherited chat merges the provider's own")
    void defaultRequestParametersDelegates() {
        var raw = new FakeChatModel();
        var wrapped = ObservableChatModel.wrap(raw, "openai", null, null, null, null);

        assertNotNull(wrapped.defaultRequestParameters());
        assertEquals("fake-model", wrapped.defaultRequestParameters().modelName(),
                "a decorator returning its own empty parameters would drop the provider's configuration");
    }

    @Test
    @DisplayName("the timeout path forwards the same way the untimed one does")
    void timeoutPathForwardsTheSameWay() {
        var raw = new FakeChatModel();
        var wrapped = ObservableChatModel.wrap(raw, "openai", "5000", null, null, null);

        assertSame(RESPONSE, wrapped.chat(request()));
        assertEquals(1, raw.chatCalls.get(), "the timed path must forward to delegate.chat() too, or a"
                + " chat()-implementing provider would work untimed and fail with a timeout configured");
        assertEquals(1, raw.doChatCalls.get());
    }
}
