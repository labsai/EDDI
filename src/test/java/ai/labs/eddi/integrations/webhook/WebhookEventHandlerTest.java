/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.webhook;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.channels.model.WebhookChannelSettings;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.caching.CacheImpl;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.tenancy.QuotaExceededException;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter.ResolvedTarget;
import ai.labs.eddi.integrations.webhook.WebhookEventHandler.Dispatch;
import ai.labs.eddi.integrations.webhook.WebhookEventParser.InboundEvent;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebhookEventHandlerTest {

    private static final String INTEGRATION_ID = "0123456789abcdef01234567";
    private static final String AGENT_ID = "abcdefabcdefabcdefabcdef";
    private static final String GROUP_ID = "fedcbafedcbafedcbafedcba";
    private static final String CONV_1 = "111111111111111111111111";
    private static final String CONV_2 = "222222222222222222222222";
    private static final String USER = "webhook:" + INTEGRATION_ID;

    private ChannelTargetRouter router;
    private IConversationService conversationService;
    private IGroupConversationService groupService;
    private IUserConversationStore userConversationStore;
    private ITemplatingEngine templating;
    private WebhookEventHandler handler;
    private ChannelIntegrationConfiguration integration;
    private ChannelTarget agentTarget;
    private final WebhookEventParser parser = new WebhookEventParser(new ObjectMapper());

    @BeforeEach
    void setUp() throws Exception {
        router = mock(ChannelTargetRouter.class);
        conversationService = mock(IConversationService.class);
        groupService = mock(IGroupConversationService.class);
        userConversationStore = mock(IUserConversationStore.class);
        templating = mock(ITemplatingEngine.class);
        ICacheFactory cacheFactory = mock(ICacheFactory.class);
        when(cacheFactory.getCache(anyString(), any(Duration.class)))
                .thenAnswer(inv -> new CacheImpl<>("dedup", Caffeine.newBuilder().build()));
        handler = new WebhookEventHandler(router, conversationService, groupService, userConversationStore, templating,
                cacheFactory, new SimpleMeterRegistry());

        agentTarget = new ChannelTarget();
        agentTarget.setName("default");
        agentTarget.setTargetId(AGENT_ID);
        integration = new ChannelIntegrationConfiguration();
        integration.setName("hooks");
        integration.setChannelType("webhook");
        integration.setTargets(List.of(agentTarget));
        integration.setDefaultTargetName("default");
        when(router.resolveEventTarget(any(), anyString()))
                .thenAnswer(inv -> new ResolvedTarget(agentTarget, null, integration, null, null));
        when(conversationService.startConversation(any(), anyString(), anyString(), anyMap()))
                .thenReturn(new IConversationService.ConversationResult(CONV_1, URI.create("eddi://c/" + CONV_1)),
                        new IConversationService.ConversationResult(CONV_2, URI.create("eddi://c/" + CONV_2)));
    }

    private InboundEvent event(String id, String body) {
        Map<String, String> headers = id == null ? Map.of("x-github-event", "push") : Map.of("x-github-event", "push", "x-github-delivery", id);
        return parser.parse(settings(Map.of()), INTEGRATION_ID, "application/json", headers, body.getBytes(StandardCharsets.UTF_8));
    }

    private static WebhookChannelSettings settings(Map<String, String> extra) {
        var pc = new HashMap<String, String>(extra);
        pc.put("signatureScheme", "github");
        return WebhookChannelSettings.from(pc);
    }

    @Test
    void newStrategyStartsConversationAsWebhookPrincipalAndPassesEventAsContext() throws Exception {
        Dispatch dispatch = handler.dispatch(INTEGRATION_ID, integration, settings(Map.of()), event("d-1", "{\"ref\":\"main\"}"), null);

        assertEquals(202, dispatch.status());
        assertEquals(CONV_1, dispatch.conversationId());
        verify(conversationService).startConversation(eq(Deployment.Environment.production), eq(AGENT_ID), eq(USER), anyMap());
        ArgumentCaptor<InputData> input = ArgumentCaptor.forClass(InputData.class);
        verify(conversationService).say(eq(CONV_1), any(), any(), any(), input.capture(), eq(false), any());
        assertEquals("event:push", input.getValue().getInput());
        Context event = input.getValue().getContext().get("event");
        assertEquals(Context.ContextType.object, event.getType());
        assertEquals("push", ((Map<?, ?>) event.getValue()).get("type"));
        verifyNoInteractions(templating);
    }

    @Test
    void inputTemplateRenderedWithEventAsData() throws Exception {
        when(templating.processTemplate(eq("New {event.type}"), anyMap(), eq(ITemplatingEngine.TemplateMode.TEXT))).thenReturn("New push");
        handler.dispatch(INTEGRATION_ID, integration, settings(Map.of("inputTemplate", "New {event.type}")), event("d-1", "{}"), null);
        ArgumentCaptor<InputData> input = ArgumentCaptor.forClass(InputData.class);
        verify(conversationService).say(eq(CONV_1), any(), any(), any(), input.capture(), eq(false), any());
        assertEquals("New push", input.getValue().getInput());
    }

    @Test
    void duplicateDeliveryReturns200WithOriginalConversationAndRunsNoSecondTurn() throws Exception {
        handler.dispatch(INTEGRATION_ID, integration, settings(Map.of()), event("d-1", "{}"), null);
        Dispatch second = handler.dispatch(INTEGRATION_ID, integration, settings(Map.of()), event("d-1", "{}"), null);

        assertEquals(200, second.status());
        assertTrue(second.duplicate());
        assertEquals(CONV_1, second.conversationId());
        verify(conversationService, times(1)).startConversation(any(), anyString(), anyString(), anyMap());
        verify(conversationService, times(1)).say(anyString(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void signatureFallbackKeyDeduplicatesEventsWithoutId() throws Exception {
        handler.dispatch(INTEGRATION_ID, integration, settings(Map.of()), event(null, "{}"), "t=1,v1=ab");
        Dispatch replay = handler.dispatch(INTEGRATION_ID, integration, settings(Map.of()), event(null, "{}"), "t=1,v1=ab");
        assertEquals(200, replay.status());
        verify(conversationService, times(1)).say(anyString(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void quotaExceededIs429AndReleasesTheDedupReservation() throws Exception {
        when(conversationService.startConversation(any(), anyString(), anyString(), anyMap()))
                .thenThrow(new QuotaExceededException("conversations per day"))
                .thenReturn(new IConversationService.ConversationResult(CONV_1, URI.create("eddi://c/" + CONV_1)));

        Dispatch refused = handler.dispatch(INTEGRATION_ID, integration, settings(Map.of()), event("d-9", "{}"), null);
        assertEquals(429, refused.status());

        Dispatch retried = handler.dispatch(INTEGRATION_ID, integration, settings(Map.of()), event("d-9", "{}"), null);
        assertEquals(202, retried.status());
        assertFalse(retried.duplicate());
    }

    @Test
    void repeatWhileFirstDeliveryIsInFlightIsRetryableNotADuplicate() throws Exception {
        // The repeat arrives while the first delivery is inside startConversation;
        // the first is then refused. Answering the repeat 200 would make its sender
        // believe the event was delivered — it would be lost.
        Dispatch[] repeat = new Dispatch[1];
        when(conversationService.startConversation(any(), anyString(), anyString(), anyMap()))
                .thenAnswer(inv -> {
                    repeat[0] = handler.dispatch(INTEGRATION_ID, integration, settings(Map.of()), event("d-20", "{}"), null);
                    throw new QuotaExceededException("conversations per day");
                })
                .thenReturn(new IConversationService.ConversationResult(CONV_1, URI.create("eddi://c/" + CONV_1)));

        Dispatch first = handler.dispatch(INTEGRATION_ID, integration, settings(Map.of()), event("d-20", "{}"), null);

        assertEquals(429, first.status());
        assertEquals(409, repeat[0].status());
        assertTrue(repeat[0].duplicate());
        assertNull(repeat[0].conversationId());
        // The sender retries: the event is processed, not swallowed.
        Dispatch retried = handler.dispatch(INTEGRATION_ID, integration, settings(Map.of()), event("d-20", "{}"), null);
        assertEquals(202, retried.status());
        assertFalse(retried.duplicate());
    }

    @Test
    void quotaExceededOnTheTurnIs429() throws Exception {
        doThrow(new QuotaExceededException("api calls")).when(conversationService)
                .say(anyString(), any(), any(), any(), any(), anyBoolean(), any());
        assertEquals(429, handler.dispatch(INTEGRATION_ID, integration, settings(Map.of()), event("d-2", "{}"), null).status());
    }

    @Test
    void agentNotDeployedIs503() throws Exception {
        when(conversationService.startConversation(any(), anyString(), anyString(), anyMap()))
                .thenThrow(new IConversationService.AgentNotReadyException("not deployed"));
        assertEquals(503, handler.dispatch(INTEGRATION_ID, integration, settings(Map.of()), event("d-3", "{}"), null).status());
    }

    @Test
    void groupTargetStartsADiscussion() throws Exception {
        var groupTarget = new ChannelTarget();
        groupTarget.setName("panel");
        groupTarget.setType(ChannelTarget.TargetType.GROUP);
        groupTarget.setTargetId(GROUP_ID);
        when(router.resolveEventTarget(any(), anyString())).thenReturn(new ResolvedTarget(groupTarget, null, integration, null, null));
        var gc = new GroupConversation();
        gc.setId("333333333333333333333333");
        when(groupService.startAndDiscussAsync(eq(GROUP_ID), eq("event:push"), eq(USER), isNull())).thenReturn(gc);

        Dispatch dispatch = handler.dispatch(INTEGRATION_ID, integration, settings(Map.of()), event("d-4", "{}"), null);

        assertEquals(202, dispatch.status());
        assertEquals("333333333333333333333333", dispatch.groupConversationId());
        verifyNoInteractions(conversationService);
    }

    @Test
    void noTargetIs422() throws Exception {
        when(router.resolveEventTarget(any(), anyString())).thenReturn(null);
        assertEquals(422, handler.dispatch(INTEGRATION_ID, integration, settings(Map.of()), event("d-5", "{}"), null).status());
        verifyNoInteractions(conversationService);
    }

    @Test
    void correlateReusesTheMappedConversation() throws Exception {
        var correlate = settings(Map.of("conversationStrategy", "correlate", "correlationKeyPath", "/data/number"));
        String intent = WebhookEventHandler.correlationIntent(INTEGRATION_ID, AGENT_ID, "7");
        when(userConversationStore.readUserConversation(intent, USER))
                .thenReturn(null)
                .thenReturn(new UserConversation(intent, USER, Deployment.Environment.production, AGENT_ID, CONV_1));
        when(conversationService.getConversationState(CONV_1)).thenReturn(ConversationState.READY);

        Dispatch first = handler.dispatch(INTEGRATION_ID, integration, correlate, event("d-6", "{\"number\":7}"), null);
        Dispatch second = handler.dispatch(INTEGRATION_ID, integration, correlate, event("d-7", "{\"number\":7}"), null);

        assertEquals(CONV_1, first.conversationId());
        assertEquals(CONV_1, second.conversationId());
        verify(conversationService, times(1)).startConversation(any(), anyString(), anyString(), anyMap());
        verify(userConversationStore).createUserConversation(argThat(m -> m.getConversationId().equals(CONV_1)));
        verify(conversationService, times(2)).say(eq(CONV_1), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void correlateReplacesAnEndedConversation() throws Exception {
        var correlate = settings(Map.of("conversationStrategy", "correlate", "correlationKeyPath", "/data/number"));
        String intent = WebhookEventHandler.correlationIntent(INTEGRATION_ID, AGENT_ID, "7");
        when(userConversationStore.readUserConversation(intent, USER))
                .thenReturn(new UserConversation(intent, USER, Deployment.Environment.production, AGENT_ID, CONV_2));
        when(conversationService.getConversationState(CONV_2)).thenReturn(ConversationState.ENDED);

        Dispatch dispatch = handler.dispatch(INTEGRATION_ID, integration, correlate, event("d-8", "{\"number\":7}"), null);

        assertEquals(CONV_1, dispatch.conversationId());
        verify(userConversationStore).deleteUserConversationIfMatches(intent, USER, CONV_2);
    }

    @Test
    void correlateWithoutKeyValueFallsBackToANewConversation() throws Exception {
        var correlate = settings(Map.of("conversationStrategy", "correlate", "correlationKeyPath", "/data/number"));
        Dispatch dispatch = handler.dispatch(INTEGRATION_ID, integration, correlate, event("d-10", "{}"), null);
        assertEquals(202, dispatch.status());
        verifyNoInteractions(userConversationStore);
    }

    @Test
    void correlateCreateRaceUsesTheStoredWinner() throws Exception {
        var correlate = settings(Map.of("conversationStrategy", "correlate", "correlationKeyPath", "/data/number"));
        String intent = WebhookEventHandler.correlationIntent(INTEGRATION_ID, AGENT_ID, "7");
        when(userConversationStore.readUserConversation(intent, USER))
                .thenReturn(null)
                .thenReturn(new UserConversation(intent, USER, Deployment.Environment.production, AGENT_ID, CONV_2));
        doThrow(new IResourceStore.ResourceAlreadyExistsException("exists")).when(userConversationStore).createUserConversation(any());

        Dispatch dispatch = handler.dispatch(INTEGRATION_ID, integration, correlate, event("d-11", "{\"number\":7}"), null);

        assertEquals(CONV_2, dispatch.conversationId());
        verify(conversationService).endConversation(CONV_1, "system:lost-create-race");
    }

    @Test
    void inFlightRepeatCarriesTheInProgressReason() throws Exception {
        Dispatch[] repeat = new Dispatch[1];
        when(conversationService.startConversation(any(), anyString(), anyString(), anyMap()))
                .thenAnswer(inv -> {
                    repeat[0] = handler.dispatch(INTEGRATION_ID, integration, settings(Map.of()), event("d-21", "{}"), null);
                    return new IConversationService.ConversationResult(CONV_1, URI.create("eddi://c/" + CONV_1));
                });
        handler.dispatch(INTEGRATION_ID, integration, settings(Map.of()), event("d-21", "{}"), null);
        assertEquals(409, repeat[0].status());
        assertEquals(WebhookEventHandler.REASON_IN_PROGRESS, repeat[0].reason());
    }

    @Test
    void approvalWaitIs409WithItsOwnReason() throws Exception {
        doThrow(new IConversationService.ConversationAwaitingApprovalException("waiting")).when(conversationService)
                .say(anyString(), any(), any(), any(), any(), anyBoolean(), any());
        Dispatch dispatch = handler.dispatch(INTEGRATION_ID, integration, settings(Map.of()), event("d-22", "{}"), null);
        assertEquals(409, dispatch.status());
        assertFalse(dispatch.duplicate());
        assertEquals(WebhookEventHandler.REASON_AWAITING_APPROVAL, dispatch.reason());
    }

    @Test
    void refusedTurnEndsTheConversationItJustCreated() throws Exception {
        doThrow(new QuotaExceededException("api calls")).when(conversationService)
                .say(anyString(), any(), any(), any(), any(), anyBoolean(), any());

        assertEquals(429, handler.dispatch(INTEGRATION_ID, integration, settings(Map.of()), event("d-23", "{}"), null).status());

        // One conversation per retry would otherwise be left behind, empty.
        verify(conversationService).endConversation(eq(CONV_1), anyString());
    }

    @Test
    void sameSignedBodyUnderAnotherEventTypeIsADuplicate() throws Exception {
        // The event-type header is not covered by the MAC. Replaying the captured body
        // under a different one must not start a second turn.
        InboundEvent push = event(null, "{}");
        InboundEvent ping = parser.parse(settings(Map.of()), INTEGRATION_ID, "application/json", Map.of("x-github-event", "ping"),
                "{}".getBytes(StandardCharsets.UTF_8));
        assertEquals(202, handler.dispatch(INTEGRATION_ID, integration, settings(Map.of()), push, "b:abc").status());

        Dispatch replay = handler.dispatch(INTEGRATION_ID, integration, settings(Map.of()), ping, "b:abc");

        assertEquals(200, replay.status());
        assertTrue(replay.duplicate());
        verify(conversationService, times(1)).say(anyString(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void fullReplayCacheRefusesNewDeliveriesInsteadOfEvictingLiveKeys() throws Exception {
        ICacheFactory cacheFactory = mock(ICacheFactory.class);
        when(cacheFactory.getCache(anyString(), any(Duration.class)))
                .thenReturn(new CacheImpl<>("dedup", Caffeine.newBuilder().build()));
        // Refuse at 4 keys: two deliveries carrying an id and a signature each.
        handler = new WebhookEventHandler(router, conversationService, groupService, userConversationStore, templating, cacheFactory,
                new SimpleMeterRegistry(), 4);
        assertEquals(202, handler.dispatch(INTEGRATION_ID, integration, settings(Map.of()), event("d-30", "{}"), "b:one").status());
        assertEquals(202, handler.dispatch(INTEGRATION_ID, integration, settings(Map.of()), event("d-31", "{}"), "b:two").status());

        Dispatch overflow = handler.dispatch(INTEGRATION_ID, integration, settings(Map.of()), event("d-32", "{}"), "b:three");
        assertEquals(503, overflow.status());

        // A repeat of something already remembered is still answered, not refused.
        Dispatch repeat = handler.dispatch(INTEGRATION_ID, integration, settings(Map.of()), event("d-30", "{}"), "b:one");
        assertEquals(200, repeat.status());
        assertTrue(repeat.duplicate());
        // And the refusal consumed nothing: no third turn ran.
        verify(conversationService, times(2)).say(anyString(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void dedupWindowIsTheSharedBucketTtlOfSlack() {
        assertEquals(Duration.ofMinutes(10), WebhookEventHandler.DEDUP_TTL);
        assertEquals("webhook-event-dedup", WebhookEventHandler.DEDUP_CACHE_NAME);
    }
}
