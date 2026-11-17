/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.webhook;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.channels.model.WebhookChannelSettings;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.caching.CacheImpl;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.health.HealthEventIngest;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter.ResolvedTarget;
import ai.labs.eddi.integrations.webhook.WebhookEventHandler.Dispatch;
import ai.labs.eddi.integrations.webhook.WebhookEventParser.InboundEvent;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("Webhook — the health-outcomes target kind (H5)")
class WebhookHealthOutcomesTargetTest {

    private static final String INTEGRATION_ID = "0123456789abcdef01234567";

    private ChannelTargetRouter router;
    private IConversationService conversationService;
    private HealthEventIngest ingest;
    private WebhookEventHandler handler;
    private ChannelIntegrationConfiguration integration;
    private ChannelTarget healthTarget;
    private final WebhookEventParser parser = new WebhookEventParser(new ObjectMapper());

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        router = mock(ChannelTargetRouter.class);
        conversationService = mock(IConversationService.class);
        ingest = mock(HealthEventIngest.class);
        Instance<HealthEventIngest> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(ingest);
        ICacheFactory cacheFactory = mock(ICacheFactory.class);
        when(cacheFactory.getCache(anyString(), any(Duration.class))).thenAnswer(inv -> new CacheImpl<>("dedup", Caffeine.newBuilder().build()));
        handler = new WebhookEventHandler(router, conversationService, mock(IGroupConversationService.class), mock(IUserConversationStore.class),
                mock(ITemplatingEngine.class), cacheFactory, new SimpleMeterRegistry(), instance);

        healthTarget = new ChannelTarget();
        healthTarget.setName("crm-outcomes");
        healthTarget.setKind(ChannelTarget.KIND_HEALTH_OUTCOMES);
        healthTarget.setAgentIds(List.of("agent-1"));
        integration = new ChannelIntegrationConfiguration();
        integration.setName("crm");
        integration.setChannelType("webhook");
        integration.setTargets(List.of(healthTarget));
        integration.setDefaultTargetName("crm-outcomes");
        when(router.resolveEventTarget(any(), anyString())).thenAnswer(inv -> new ResolvedTarget(healthTarget, null, integration, null, null));
        when(ingest.ingest(anyString(), any(), anyString(), any()))
                .thenReturn(new HealthEventIngest.Result(202, 1, 0, List.of(), null));
    }

    private InboundEvent cloudEvent(String id, String data) {
        String body = "{\"specversion\":\"1.0\",\"id\":\"" + id + "\",\"type\":\"order.placed\",\"source\":\"crm\",\"data\":" + data + "}";
        return parser.parse(settings(), INTEGRATION_ID, "application/cloudevents+json", Map.of(), body.getBytes(StandardCharsets.UTF_8));
    }

    private static WebhookChannelSettings settings() {
        var pc = new HashMap<String, String>();
        pc.put("signatureScheme", "github");
        return WebhookChannelSettings.from(pc);
    }

    @Test
    @DisplayName("a health-outcomes target hands the CloudEvent's data to the ingest — bound agents, the event type — and starts no conversation")
    @SuppressWarnings("unchecked")
    void routesToIngest() {
        Dispatch dispatch = handler.dispatch(INTEGRATION_ID, integration, settings(),
                cloudEvent("ce-1", "{\"ref\":{\"orderId\":\"A-1\"},\"agentId\":\"agent-1\"}"), "sig-1");
        assertEquals(202, dispatch.status());
        assertNull(dispatch.conversationId());
        assertEquals(1, dispatch.details().get("accepted"));
        ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);
        verify(ingest).ingest(eq(INTEGRATION_ID), eq(List.of("agent-1")), eq("order.placed"), data.capture());
        assertEquals("A-1", ((Map<String, Object>) ((Map<String, Object>) data.getValue()).get("ref")).get("orderId"));
        verifyNoInteractions(conversationService);
    }

    @Test
    @DisplayName("the replay cache applies: a repeat delivery is a 200 duplicate and is not ingested twice")
    void replayIsDuplicate() {
        handler.dispatch(INTEGRATION_ID, integration, settings(), cloudEvent("ce-1", "{}"), "sig-1");
        Dispatch second = handler.dispatch(INTEGRATION_ID, integration, settings(), cloudEvent("ce-1", "{}"), "sig-1");
        assertEquals(200, second.status());
        assertTrue(second.duplicate());
        assertNull(second.conversationId());
        verify(ingest, times(1)).ingest(anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("a refused ingest is not remembered — the sender's retry is processed afresh")
    void refusalIsRetryable() {
        when(ingest.ingest(anyString(), any(), anyString(), any())).thenReturn(new HealthEventIngest.Result(429, 0, 0,
                List.of(Map.of("index", 0, "reason", HealthEventIngest.REASON_BACKLOG_FULL)), "No event was accepted."));
        Dispatch first = handler.dispatch(INTEGRATION_ID, integration, settings(), cloudEvent("ce-1", "{}"), "sig-1");
        assertEquals(429, first.status());
        assertEquals("No event was accepted.", first.error());
        handler.dispatch(INTEGRATION_ID, integration, settings(), cloudEvent("ce-1", "{}"), "sig-1");
        verify(ingest, times(2)).ingest(anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("without the health ingest on the node the delivery is a retryable 503")
    void noIngest() {
        WebhookEventHandler bare = new WebhookEventHandler(router, conversationService, mock(IGroupConversationService.class),
                mock(IUserConversationStore.class), mock(ITemplatingEngine.class), cacheFactory(), new SimpleMeterRegistry());
        assertEquals(503, bare.dispatch(INTEGRATION_ID, integration, settings(), cloudEvent("ce-2", "{}"), "sig-2").status());
    }

    private static ICacheFactory cacheFactory() {
        ICacheFactory cacheFactory = mock(ICacheFactory.class);
        when(cacheFactory.getCache(anyString(), any(Duration.class))).thenAnswer(inv -> new CacheImpl<>("dedup", Caffeine.newBuilder().build()));
        return cacheFactory;
    }
}
