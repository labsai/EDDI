/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResult;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter;
import ai.labs.eddi.integrations.channels.ObserveGate;
import ai.labs.eddi.integrations.slack.SlackEventHandler.SlackUser;
import ai.labs.eddi.integrations.slack.SlackEventHandler.ThreadConversation;
import ai.labs.eddi.integrations.slack.hitl.InMemorySlackApprovalRecordStore;
import ai.labs.eddi.modules.llm.tools.ToolCostTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A Slack thread whose conversation has ended gets a fresh one instead of being
 * refused on every further message.
 */
class SlackThreadConversationTest {

    private static final String AGENT_ID = "agent-1";
    private static final String CHANNEL = "C1";
    private static final String THREAD = "1700000000.000100";
    private static final String INTENT = "channel:slack:C1:agent-1:1700000000.000100";
    private static final String TOKEN = "xoxb-test";
    private static final SlackUser USER = new SlackUser("U0ALICE", "slack:T1:U0ALICE");

    private IConversationService conversationService;
    private IUserConversationStore userConversationStore;
    private SlackWebApiClient slackApi;
    private SlackEventHandler handler;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() throws Exception {
        conversationService = mock(IConversationService.class);
        userConversationStore = mock(IUserConversationStore.class);
        slackApi = mock(SlackWebApiClient.class);
        var cacheFactory = mock(ICacheFactory.class);
        doReturn(mock(ICache.class)).when(cacheFactory).getCache(anyString(), any(Duration.class));
        handler = new SlackEventHandler(mock(ChannelTargetRouter.class), mock(ObserveGate.class),
                mock(ToolCostTracker.class), slackApi, conversationService, mock(IGroupConversationService.class),
                userConversationStore, cacheFactory,
                // A short request timeout: a regression that leaves a turn unanswered fails in
                // seconds instead of waiting out the production minute.
                new SlackConfig(2,
                        SlackConfig.DEFAULT_GROUP_COMPLETION_TIMEOUT_SECONDS,
                        1, SlackConfig.DEFAULT_API_RETRY_BASE_MS),
                new InMemorySlackApprovalRecordStore(), mock(IUserMemoryStore.class));
        when(conversationService.startConversation(any(), anyString(), anyString(), any()))
                .thenReturn(new ConversationResult("conv-new", URI.create("eddi://conv-new")));
    }

    private void mapped(String conversationId) throws Exception {
        when(userConversationStore.readUserConversation(INTENT, USER.eddiUserId())).thenReturn(mapping(conversationId));
    }

    private static UserConversation mapping(String conversationId) {
        return new UserConversation(INTENT, USER.eddiUserId(), Deployment.Environment.production, AGENT_ID, conversationId);
    }

    private void ended(String conversationId, String endReason) throws Exception {
        when(conversationService.getConversationState(conversationId)).thenReturn(ConversationState.ENDED);
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.ENDED);
        snapshot.setEndReason(endReason);
        when(conversationService.getConversationMemorySnapshot(conversationId)).thenReturn(snapshot);
    }

    private static SimpleConversationMemorySnapshot answer(String text) {
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.READY);
        var output = new ConversationOutput();
        output.put("output", List.of(text));
        snapshot.getConversationOutputs().add(output);
        return snapshot;
    }

    /** say() on {@code conversationId} completes with an answer. */
    private void answers(String conversationId, String text) throws Exception {
        doAnswer(inv -> {
            ((IConversationService.ConversationResponseHandler) inv.getArgument(6)).onComplete(answer(text));
            return null;
        }).when(conversationService).say(eq(conversationId), any(), any(), any(), any(), anyBoolean(), any());
    }

    private List<String> postedTexts() throws Exception {
        ArgumentCaptor<String> texts = ArgumentCaptor.forClass(String.class);
        verify(slackApi, atLeastOnce()).postMessage(anyString(), eq(CHANNEL), eq(THREAD), texts.capture());
        return texts.getAllValues();
    }

    @Nested
    @DisplayName("opening a thread's conversation")
    class Open {

        @Test
        @DisplayName("a live mapped conversation is used as it is")
        void liveConversationIsKept() throws Exception {
            mapped("conv-live");
            when(conversationService.getConversationState("conv-live")).thenReturn(ConversationState.READY);

            ThreadConversation thread = handler.openThreadConversation(AGENT_ID, USER, INTENT);

            assertEquals("conv-live", thread.conversationId());
            assertFalse(thread.replaced());
            verify(conversationService, never()).startConversation(any(), any(), any(), any());
            verify(userConversationStore, never()).deleteUserConversationIfMatches(any(), any(), any());
        }

        @Test
        @DisplayName("an ended mapped conversation is replaced, removing only the mapping that still names it")
        void endedConversationIsReplaced() throws Exception {
            mapped("conv-ended");
            ended("conv-ended", IConversationService.END_REASON_AGENT_VERSION_RETIRED);

            ThreadConversation thread = handler.openThreadConversation(AGENT_ID, USER, INTENT);

            assertEquals("conv-new", thread.conversationId());
            assertTrue(thread.replaced());
            assertEquals(IConversationService.END_REASON_AGENT_VERSION_RETIRED, thread.replacedEndReason());
            verify(userConversationStore).deleteUserConversationIfMatches(INTENT, USER.eddiUserId(), "conv-ended");
            verify(userConversationStore, never()).deleteUserConversation(any(), any());
            ArgumentCaptor<UserConversation> created = ArgumentCaptor.forClass(UserConversation.class);
            verify(userConversationStore).createUserConversation(created.capture());
            assertEquals("conv-new", created.getValue().getConversationId());
        }

        @Test
        @DisplayName("a mapped conversation that no longer exists is replaced too")
        void vanishedConversationIsReplaced() throws Exception {
            mapped("conv-gone");
            when(conversationService.getConversationState("conv-gone"))
                    .thenThrow(new IConversationService.ConversationNotFoundException("gone"));

            ThreadConversation thread = handler.openThreadConversation(AGENT_ID, USER, INTENT);

            assertEquals("conv-new", thread.conversationId());
            assertTrue(thread.replaced());
            assertNull(thread.replacedEndReason());
        }

        @Test
        @DisplayName("a state that cannot be read keeps the conversation — a store hiccup must not cost the thread")
        void unreadableStateKeepsConversation() throws Exception {
            mapped("conv-live");
            when(conversationService.getConversationState("conv-live")).thenThrow(new IllegalStateException("store down"));

            ThreadConversation thread = handler.openThreadConversation(AGENT_ID, USER, INTENT);

            assertEquals("conv-live", thread.conversationId());
            assertFalse(thread.replaced());
            verify(conversationService, never()).startConversation(any(), any(), any(), any());
        }

        /**
         * Two messages found the same ended conversation. This one's conditional delete
         * matched nothing and its create collided with the other's mapping: it must use
         * that one — and end its own orphan — rather than start a second conversation
         * for the same thread.
         */
        @Test
        @DisplayName("losing the replacement race converges on the winner's conversation")
        void lostReplacementRaceConverges() throws Exception {
            when(userConversationStore.readUserConversation(INTENT, USER.eddiUserId()))
                    .thenReturn(mapping("conv-ended"), mapping("conv-winner"));
            ended("conv-ended", IConversationService.END_REASON_AGENT_VERSION_RETIRED);
            doThrow(new IResourceStore.ResourceAlreadyExistsException("taken"))
                    .when(userConversationStore).createUserConversation(any());

            ThreadConversation thread = handler.openThreadConversation(AGENT_ID, USER, INTENT);

            assertEquals("conv-winner", thread.conversationId());
            assertFalse(thread.replaced(), "only the winner announces the replacement");
            verify(conversationService).endConversation("conv-new", "system:lost-create-race");
        }
    }

    @Nested
    @DisplayName("sending in a thread")
    class Send {

        @Test
        @DisplayName("a conversation that ends between opening and sending is replaced and the message is delivered once")
        void endedAtSendIsReplacedOnce() throws Exception {
            mapped("conv-a");
            ended("conv-a", null);
            doThrow(new IConversationService.ConversationEndedException("ended"))
                    .when(conversationService).say(eq("conv-a"), any(), any(), any(), any(), anyBoolean(), any());
            answers("conv-new", "Hello again");

            String deliveredTo = handler.sendInThread(null, AGENT_ID, USER, INTENT, ThreadConversation.existing("conv-a"),
                    CHANNEL, THREAD, "hi", TOKEN);

            assertEquals("conv-new", deliveredTo);
            verify(conversationService).say(eq("conv-new"), any(), any(), any(), any(), anyBoolean(), any());
            verify(userConversationStore).deleteUserConversationIfMatches(INTENT, USER.eddiUserId(), "conv-a");
            assertEquals(List.of("Hello again"), postedTexts(), "no agent-updated notice for an end without that reason");
        }

        @Test
        @DisplayName("a turn dropped because the conversation ended while queued is recovered the same way")
        void skippedAsEndedIsReplaced() throws Exception {
            ended("conv-a", null);
            doAnswer(inv -> {
                var endedSnapshot = new SimpleConversationMemorySnapshot();
                endedSnapshot.setConversationState(ConversationState.ENDED);
                ((IConversationService.ConversationResponseHandler) inv.getArgument(6)).onSkipped(endedSnapshot);
                return null;
            }).when(conversationService).say(eq("conv-a"), any(), any(), any(), any(), anyBoolean(), any());
            answers("conv-new", "Hello again");

            String deliveredTo = handler.sendInThread(null, AGENT_ID, USER, INTENT, ThreadConversation.existing("conv-a"),
                    CHANNEL, THREAD, "hi", TOKEN);

            assertEquals("conv-new", deliveredTo);
            assertEquals(List.of("Hello again"), postedTexts(), "an ended skip is not reported as 'busy'");
        }

        @Test
        @DisplayName("the agent-updated notice is posted before the answer when the old version was retired")
        void noticeBeforeAnswerWhenRetired() throws Exception {
            mapped("conv-a");
            ended("conv-a", IConversationService.END_REASON_AGENT_VERSION_RETIRED);
            answers("conv-new", "Hello again");

            ThreadConversation thread = handler.openThreadConversation(AGENT_ID, USER, INTENT);
            handler.sendInThread(null, AGENT_ID, USER, INTENT, thread, CHANNEL, THREAD, "hi", TOKEN);

            InOrder order = inOrder(slackApi);
            order.verify(slackApi).postMessage(anyString(), eq(CHANNEL), eq(THREAD), eq(SlackEventHandler.AGENT_UPDATED_NOTICE));
            order.verify(slackApi).postMessage(anyString(), eq(CHANNEL), eq(THREAD), eq("Hello again"));
        }

        @Test
        @DisplayName("a second end in a row propagates instead of looping")
        void secondEndPropagates() throws Exception {
            ended("conv-a", null);
            doThrow(new IConversationService.ConversationEndedException("ended"))
                    .when(conversationService).say(anyString(), any(), any(), any(), any(), anyBoolean(), any());

            assertThrows(IConversationService.ConversationEndedException.class,
                    () -> handler.sendInThread(null, AGENT_ID, USER, INTENT, ThreadConversation.existing("conv-a"),
                            CHANNEL, THREAD, "hi", TOKEN));

            verify(conversationService, times(1)).startConversation(any(), any(), any(), any());
            verify(conversationService, times(2)).say(anyString(), any(), any(), any(), any(), anyBoolean(), any());
        }

        @Test
        @DisplayName("a live conversation is sent to once, with nothing replaced")
        void liveConversationIsSentTo() throws Exception {
            answers("conv-live", "Hi");

            String deliveredTo = handler.sendInThread(null, AGENT_ID, USER, INTENT,
                    ThreadConversation.existing("conv-live"), CHANNEL, THREAD, "hi", TOKEN);

            assertEquals("conv-live", deliveredTo);
            verify(conversationService, never()).startConversation(any(), any(), any(), any());
            assertEquals(List.of("Hi"), postedTexts());
        }
    }
}
