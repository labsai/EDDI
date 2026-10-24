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
import ai.labs.eddi.engine.caching.CacheFactory;
import ai.labs.eddi.engine.caching.CacheImpl;
import ai.labs.eddi.engine.caching.ICache;
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

    private static final String OTHER_ID = "76543210fedcba9876543210";

    private WebhookEventHandler handlerWithRefuseAt(long refuseAt, SimpleMeterRegistry meters) throws Exception {
        ICacheFactory cacheFactory = mock(ICacheFactory.class);
        when(cacheFactory.getCache(anyString(), any(Duration.class)))
                .thenReturn(new CacheImpl<>("dedup", Caffeine.newBuilder().build()));
        when(conversationService.startConversation(any(), anyString(), anyString(), anyMap()))
                .thenAnswer(inv -> new IConversationService.ConversationResult(CONV_1, URI.create("eddi://c/" + CONV_1)));
        return new WebhookEventHandler(router, conversationService, groupService, userConversationStore, templating, cacheFactory,
                meters, refuseAt);
    }

    @Test
    void integrationOverItsOwnBudgetIsRefusedWhileOthersAreStillAccepted() throws Exception {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        handler = handlerWithRefuseAt(1_000_000, meters);
        WebhookChannelSettings budget = settings(Map.of("maxDeliveriesPerWindow", "2"));
        assertEquals(202, handler.dispatch(INTEGRATION_ID, integration, budget, event("d-40", "{}"), "b:1").status());
        assertEquals(202, handler.dispatch(INTEGRATION_ID, integration, budget, event("d-41", "{}"), "b:2").status());

        Dispatch over = handler.dispatch(INTEGRATION_ID, integration, budget, event("d-42", "{}"), "b:3");
        Dispatch other = handler.dispatch(OTHER_ID, integration, budget, event("d-43", "{}"), "b:4");

        assertEquals(503, over.status());
        assertEquals(202, other.status(), "one integration at its cap must not refuse another");
        assertEquals(1.0, meters.counter("eddi.webhook.events", "outcome", "refused_capacity").count());
        assertEquals(0.0, meters.counter("eddi.webhook.events", "outcome", "refused_503").count());
        // A repeat of something already remembered is still answered, not refused.
        Dispatch repeat = handler.dispatch(INTEGRATION_ID, integration, budget, event("d-40", "{}"), "b:1");
        assertEquals(200, repeat.status());
        assertTrue(repeat.duplicate());
    }

    @Test
    void budgetWindowRollsOverAfterTenMinutes() throws Exception {
        handler = handlerWithRefuseAt(1_000_000, new SimpleMeterRegistry());
        long[] now = {1_000_000_000L};
        handler.setClock(() -> now[0]);
        WebhookChannelSettings budget = settings(Map.of("maxDeliveriesPerWindow", "1"));
        assertEquals(202, handler.dispatch(INTEGRATION_ID, integration, budget, event("d-50", "{}"), "b:1").status());
        assertEquals(503, handler.dispatch(INTEGRATION_ID, integration, budget, event("d-51", "{}"), "b:2").status());

        now[0] += 11 * 60_000L;

        assertEquals(202, handler.dispatch(INTEGRATION_ID, integration, budget, event("d-52", "{}"), "b:3").status());
    }

    @Test
    void sharedCachePastTheBackstopRefusesOnlyTheIntegrationCrowdingItOut() throws Exception {
        // Backstop at 4 keys: with two active integrations each one's fair share is 1.
        handler = handlerWithRefuseAt(4, new SimpleMeterRegistry());
        handler.setActiveFloor(1);
        WebhookChannelSettings roomy = settings(Map.of());
        assertEquals(202, handler.dispatch(INTEGRATION_ID, integration, roomy, event("d-60", "{}"), "b:1").status());
        assertEquals(202, handler.dispatch(INTEGRATION_ID, integration, roomy, event("d-61", "{}"), "b:2").status());
        assertEquals(202, handler.dispatch(OTHER_ID, integration, roomy, event("d-62", "{}"), "b:3").status());
        // The cache now holds 6 keys, past the backstop.

        assertEquals(503, handler.dispatch(INTEGRATION_ID, integration, roomy, event("d-63", "{}"), "b:4").status(),
                "the integration holding two deliveries is above its share");
        assertEquals(202, handler.dispatch("00000000000000000000000a", integration, roomy, event("d-64", "{}"), "b:5").status(),
                "an integration with no deliveries is not refused for someone else's volume");
    }

    @Test
    void refusalStormWarnsOncePerMinute() throws Exception {
        handler = handlerWithRefuseAt(1_000_000, new SimpleMeterRegistry());
        long[] now = {5_000_000_000L};
        handler.setClock(() -> now[0]);
        WebhookChannelSettings budget = settings(Map.of("maxDeliveriesPerWindow", "1"));
        handler.dispatch(INTEGRATION_ID, integration, budget, event("d-70", "{}"), "b:1");
        for (int i = 0; i < 5; i++) {
            assertEquals(503, handler.dispatch(INTEGRATION_ID, integration, budget, event("d-7" + (i + 1), "{}"), "b:x" + i).status());
        }
        assertEquals(1, handler.capacityWarningsLogged(), "five refusals inside a minute are one log line");
        now[0] += 61_000L;
        // The window (10 min) still holds the first delivery, so this is refused and
        // warns again.
        handler.dispatch(INTEGRATION_ID, integration, budget, event("d-79", "{}"), "b:y");
        assertEquals(2, handler.capacityWarningsLogged());
    }

    @Test
    void refusalThresholdLeavesHeadroomInTheCache() {
        assertTrue(WebhookEventHandler.DEDUP_REFUSE_AT * 2 <= CacheFactory.maximumSizeFor(WebhookEventHandler.DEDUP_CACHE_NAME,
                WebhookEventHandler.DEDUP_TTL), "the backstop must sit at or below half the capacity");
        assertTrue(WebhookEventHandler.DEDUP_REFUSE_AT > 0);
    }

    @Test
    void orphanedConversationIsEndedWithItsOwnReason() throws Exception {
        doThrow(new QuotaExceededException("api calls")).when(conversationService)
                .say(anyString(), any(), any(), any(), any(), anyBoolean(), any());
        handler.dispatch(INTEGRATION_ID, integration, settings(Map.of()), event("d-80", "{}"), null);
        verify(conversationService).endConversation(CONV_1, "system:webhook-turn-refused");
    }

    @Test
    void dedupWindowIsTheSharedBucketTtlOfSlack() {
        assertEquals(Duration.ofMinutes(10), WebhookEventHandler.DEDUP_TTL);
        assertEquals("webhook-event-dedup", WebhookEventHandler.DEDUP_CACHE_NAME);
    }

    private static String id(int n) {
        return String.format("%024x", n);
    }

    @Test
    void nearlyFullCacheRefusesEveryNewDeliveryWhateverItsIntegrationHolds() throws Exception {
        // Backstop 4, hard stop 7. Integrations that each hold little keep being
        // admitted by the fair share, until the cache itself is nearly full.
        handler = handlerWithRefuseAt(4, new SimpleMeterRegistry());
        handler.setActiveFloor(1);
        WebhookChannelSettings roomy = settings(Map.of());
        for (int i = 1; i <= 4; i++) {
            assertEquals(202, handler.dispatch(id(i), integration, roomy, event("h-" + i, "{}"), "b:h" + i).status(), "delivery " + i);
        }
        // 8 keys held, at or past the hard stop (7): even an idle integration waits.
        Dispatch idle = handler.dispatch(id(99), integration, roomy, event("h-99", "{}"), "b:h99");
        assertEquals(503, idle.status());
        // Repeats are still answered.
        assertEquals(200, handler.dispatch(id(1), integration, roomy, event("h-1", "{}"), "b:h1").status());
    }

    @Test
    void deliveryStillCountsUntilItsKeysCouldHaveExpired() throws Exception {
        handler = handlerWithRefuseAt(1_000_000, new SimpleMeterRegistry());
        long[] now = {1_000_000_000L};
        handler.setClock(() -> now[0]);
        WebhookChannelSettings budget = settings(Map.of("maxDeliveriesPerWindow", "1"));
        assertEquals(202, handler.dispatch(INTEGRATION_ID, integration, budget, event("w-1", "{}"), "b:w1").status());

        // Ten minutes on, the first delivery's keys can still be in the cache (they
        // were
        // written up to a minute after the start of its bucket).
        now[0] += 10 * 60_000L;
        assertEquals(503, handler.dispatch(INTEGRATION_ID, integration, budget, event("w-2", "{}"), "b:w2").status());

        now[0] += 60_000L;
        assertEquals(202, handler.dispatch(INTEGRATION_ID, integration, budget, event("w-3", "{}"), "b:w3").status());
    }

    @Test
    void manyIntegrationsWithOneDeliveryEachDoNotShrinkAnotherOnesShare() throws Exception {
        // Backstop 40, hard stop 72, floor 5. A holds 4 deliveries (8 keys) of its
        // share; 20 throwaway integrations each send one delivery.
        handler = handlerWithRefuseAt(40, new SimpleMeterRegistry());
        handler.setActiveFloor(5);
        WebhookChannelSettings roomy = settings(Map.of());
        for (int i = 1; i <= 20; i++) {
            assertEquals(202, handler.dispatch(id(1000 + i), integration, roomy, event("t-" + i, "{}"), "b:t" + i).status());
        }
        // 40 keys: the backstop is reached. A (used 0) delivers; share is 40/2/1 = 20,
        // not 40/2/21.
        for (int i = 1; i <= 8; i++) {
            assertEquals(202, handler.dispatch(INTEGRATION_ID, integration, roomy, event("a-" + i, "{}"), "b:a" + i).status(),
                    "A's delivery " + i + " is within a share the throwaway integrations must not shrink");
        }
    }

    @Test
    void admittedDeliveryDoesNoExtraCacheRead() throws Exception {
        CacheImpl<String, String> spy = spy(new CacheImpl<String, String>("dedup", Caffeine.newBuilder().build()));
        ICacheFactory cacheFactory = mock(ICacheFactory.class);
        when(cacheFactory.getCache(anyString(), any(Duration.class))).thenReturn((ICache) spy);
        when(conversationService.startConversation(any(), anyString(), anyString(), anyMap()))
                .thenReturn(new IConversationService.ConversationResult(CONV_1, URI.create("eddi://c/" + CONV_1)));
        handler = new WebhookEventHandler(router, conversationService, groupService, userConversationStore, templating, cacheFactory,
                new SimpleMeterRegistry(), 1_000_000);

        assertEquals(202, handler.dispatch(INTEGRATION_ID, integration, settings(Map.of()), event("g-1", "{}"), "b:g1").status());

        verify(spy, never()).get(any());
    }

    @Test
    void refusedDeliveriesDoNotSpendTheBudget() throws Exception {
        handler = handlerWithRefuseAt(1_000_000, new SimpleMeterRegistry());
        when(conversationService.startConversation(any(), anyString(), anyString(), anyMap()))
                .thenThrow(new QuotaExceededException("conversations per day"));
        WebhookChannelSettings budget = settings(Map.of("maxDeliveriesPerWindow", "1"));
        for (int i = 0; i < 3; i++) {
            assertEquals(429, handler.dispatch(INTEGRATION_ID, integration, budget, event("r-" + i, "{}"), "b:r" + i).status(),
                    "a sender looping on a quota refusal must keep seeing the real 429, not a capacity 503");
        }
    }
}
