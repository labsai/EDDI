/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.webhook.rest;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.caching.CacheImpl;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.tenancy.QuotaExceededException;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter;
import ai.labs.eddi.integrations.channels.HmacSignatures;
import ai.labs.eddi.integrations.webhook.WebhookEventHandler;
import ai.labs.eddi.integrations.webhook.WebhookSignatureVerifier;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The endpoint end to end, with a REAL verifier, parser and event handler over
 * a mocked conversation service — so "an unsigned request never reaches the
 * conversation service" is asserted against the code that would otherwise call
 * it, not against a mocked handler.
 */
class RestWebhookChannelTest {

    private static final String INTEGRATION_ID = "0123456789abcdef01234567";
    private static final String AGENT_ID = "abcdefabcdefabcdefabcdef";
    private static final String SECRET = "resolved-signing-secret";
    private static final String PUSH = "{\"ref\":\"refs/heads/main\",\"repository\":{\"full_name\":\"labsai/EDDI\"}}";

    private ChannelTargetRouter router;
    private IConversationService conversationService;
    private RestWebhookChannel endpoint;
    private ChannelIntegrationConfiguration integration;

    @BeforeEach
    void setUp() throws Exception {
        router = mock(ChannelTargetRouter.class);
        conversationService = mock(IConversationService.class);
        ICacheFactory cacheFactory = mock(ICacheFactory.class);
        when(cacheFactory.getCache(anyString(), any(Duration.class)))
                .thenAnswer(inv -> new CacheImpl<>("dedup", Caffeine.newBuilder().build()));
        var handler = new WebhookEventHandler(router, conversationService, mock(IGroupConversationService.class),
                mock(IUserConversationStore.class), mock(ITemplatingEngine.class), cacheFactory, new SimpleMeterRegistry());
        endpoint = new RestWebhookChannel(router, new WebhookSignatureVerifier(), handler, new ObjectMapper(), 1024);

        var target = new ChannelTarget();
        target.setName("default");
        target.setTargetId(AGENT_ID);
        integration = new ChannelIntegrationConfiguration();
        integration.setName("github");
        integration.setChannelType("webhook");
        integration.setTargets(List.of(target));
        integration.setDefaultTargetName("default");
        // As the router returns it: secret already resolved from the vault.
        integration.setPlatformConfig(Map.of("signingSecret", SECRET, "signatureScheme", "github"));
        when(router.getWebhookIntegration(INTEGRATION_ID)).thenReturn(Optional.of(integration));
        when(router.resolveEventTarget(any(), anyString())).thenCallRealMethod();
        when(conversationService.startConversation(any(), anyString(), anyString(), anyMap()))
                .thenReturn(new IConversationService.ConversationResult("111111111111111111111111", URI.create("eddi://c/1")));
    }

    private static Map<String, String> githubHeaders(String body, String delivery) {
        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "application/json");
        headers.put("x-github-event", "push");
        headers.put("x-github-delivery", delivery);
        headers.put("x-hub-signature-256", "sha256=" + HmacSignatures.hmacSha256Hex(SECRET, body.getBytes(StandardCharsets.UTF_8)));
        return headers;
    }

    @Test
    void signedGithubPushStartsAConversationAndReturns202() throws Exception {
        Response response = endpoint.receive(INTEGRATION_ID, githubHeaders(PUSH, "d-1"), PUSH.getBytes(StandardCharsets.UTF_8));

        assertEquals(202, response.getStatus());
        assertTrue(response.getEntity().toString().contains("111111111111111111111111"));
        verify(conversationService).startConversation(any(), eq(AGENT_ID), eq("webhook:" + INTEGRATION_ID), anyMap());
        verify(conversationService).say(anyString(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void replayedDeliveryIs200DuplicateWithoutASecondTurn() throws Exception {
        endpoint.receive(INTEGRATION_ID, githubHeaders(PUSH, "d-1"), PUSH.getBytes(StandardCharsets.UTF_8));
        Response replay = endpoint.receive(INTEGRATION_ID, githubHeaders(PUSH, "d-1"), PUSH.getBytes(StandardCharsets.UTF_8));

        assertEquals(200, replay.getStatus());
        assertTrue(replay.getEntity().toString().contains("\"duplicate\":true"));
        verify(conversationService, times(1)).say(anyString(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void githubReplayWithoutDeliveryIdIsDeduplicatedOnTheSignature() throws Exception {
        Map<String, String> headers = githubHeaders(PUSH, "unused");
        headers.remove("x-github-delivery");

        assertEquals(202, endpoint.receive(INTEGRATION_ID, headers, PUSH.getBytes(StandardCharsets.UTF_8)).getStatus());
        Response replay = endpoint.receive(INTEGRATION_ID, new HashMap<>(headers), PUSH.getBytes(StandardCharsets.UTF_8));

        assertEquals(200, replay.getStatus());
        verify(conversationService, times(1)).say(anyString(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void genericHmacReplayWithoutEventIdIsDeduplicatedOnTheSignature() throws Exception {
        integration.setPlatformConfig(Map.of("signingSecret", SECRET, "signatureScheme", "generic-hmac", "signatureHeader", "X-Acme-Sig"));
        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "application/json");
        headers.put("x-acme-sig", HmacSignatures.hmacSha256Hex(SECRET, PUSH.getBytes(StandardCharsets.UTF_8)));

        assertEquals(202, endpoint.receive(INTEGRATION_ID, headers, PUSH.getBytes(StandardCharsets.UTF_8)).getStatus());
        assertEquals(200, endpoint.receive(INTEGRATION_ID, headers, PUSH.getBytes(StandardCharsets.UTF_8)).getStatus());
        verify(conversationService, times(1)).say(anyString(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void repeatWhileInFlightIs409WithRetryAfter() throws Exception {
        Response[] repeat = new Response[1];
        when(conversationService.startConversation(any(), anyString(), anyString(), anyMap())).thenAnswer(inv -> {
            repeat[0] = endpoint.receive(INTEGRATION_ID, githubHeaders(PUSH, "d-30"), PUSH.getBytes(StandardCharsets.UTF_8));
            return new IConversationService.ConversationResult("111111111111111111111111", URI.create("eddi://c/1"));
        });

        assertEquals(202, endpoint.receive(INTEGRATION_ID, githubHeaders(PUSH, "d-30"), PUSH.getBytes(StandardCharsets.UTF_8)).getStatus());
        assertEquals(409, repeat[0].getStatus());
        assertEquals("5", repeat[0].getHeaderString("Retry-After"));
        // Once the first is recorded, a further repeat is a plain duplicate.
        assertEquals(200, endpoint.receive(INTEGRATION_ID, githubHeaders(PUSH, "d-30"), PUSH.getBytes(StandardCharsets.UTF_8)).getStatus());
    }

    @Test
    void unsignedRequestIs401AndNeverReachesTheConversationService() {
        Map<String, String> headers = githubHeaders(PUSH, "d-2");
        headers.remove("x-hub-signature-256");

        Response response = endpoint.receive(INTEGRATION_ID, headers, PUSH.getBytes(StandardCharsets.UTF_8));

        assertEquals(401, response.getStatus());
        verifyNoInteractions(conversationService);
    }

    @Test
    void tamperedBodyIs401AndNeverReachesTheConversationService() {
        String tampered = PUSH.replace("main", "evil");
        Response response = endpoint.receive(INTEGRATION_ID, githubHeaders(PUSH, "d-3"), tampered.getBytes(StandardCharsets.UTF_8));

        assertEquals(401, response.getStatus());
        verifyNoInteractions(conversationService);
    }

    @Test
    void unresolvedSecretFailsClosed() {
        integration.setPlatformConfig(Map.of("signatureScheme", "github"));
        Response response = endpoint.receive(INTEGRATION_ID, githubHeaders(PUSH, "d-4"), PUSH.getBytes(StandardCharsets.UTF_8));
        assertEquals(401, response.getStatus());
        verifyNoInteractions(conversationService);
    }

    @Test
    void unknownIntegrationIs404() {
        assertEquals(404, endpoint.receive("ffffffffffffffffffffffff", githubHeaders(PUSH, "d-5"),
                PUSH.getBytes(StandardCharsets.UTF_8)).getStatus());
        assertEquals(404, endpoint.receive("../etc", githubHeaders(PUSH, "d-5"), PUSH.getBytes(StandardCharsets.UTF_8)).getStatus());
        verifyNoInteractions(conversationService);
    }

    @Test
    void oversizedBodyIs413BeforeVerification() {
        String big = "{\"x\":\"" + "a".repeat(2000) + "\"}";
        Response response = endpoint.receive(INTEGRATION_ID, githubHeaders(big, "d-6"), big.getBytes(StandardCharsets.UTF_8));
        assertEquals(413, response.getStatus());
        verifyNoInteractions(router, conversationService);
    }

    @Test
    void oversizedDeclaredLengthIs413WithoutReadingTheBody() {
        HttpHeaders httpHeaders = mock(HttpHeaders.class);
        when(httpHeaders.getRequestHeaders()).thenReturn(new MultivaluedHashMap<>());
        when(httpHeaders.getLength()).thenReturn(1_000_000);
        var stream = new ByteArrayInputStream(new byte[0]);

        assertEquals(413, endpoint.receive(INTEGRATION_ID, httpHeaders, stream).getStatus());
        verifyNoInteractions(router, conversationService);
    }

    @Test
    void streamedBodyIsReadAsRawBytesAndVerified() {
        HttpHeaders httpHeaders = mock(HttpHeaders.class);
        var multi = new MultivaluedHashMap<String, String>();
        githubHeaders(PUSH, "d-7").forEach(multi::putSingle);
        when(httpHeaders.getRequestHeaders()).thenReturn(multi);
        when(httpHeaders.getLength()).thenReturn(-1);

        Response response = endpoint.receive(INTEGRATION_ID, httpHeaders, new ByteArrayInputStream(PUSH.getBytes(StandardCharsets.UTF_8)));
        assertEquals(202, response.getStatus());
    }

    @Test
    void eddiV1CloudEventStructuredMode() throws Exception {
        integration.setPlatformConfig(Map.of("signingSecret", SECRET));
        String body = "{\"specversion\":\"1.0\",\"id\":\"e-1\",\"type\":\"com.example.alert\",\"source\":\"/mon\",\"data\":{}}";
        long t = Instant.now().getEpochSecond();
        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "application/cloudevents+json");
        headers.put("x-eddi-signature", "t=" + t + ",v1=" + HmacSignatures.hmacSha256Hex(SECRET, (t + ".").getBytes(StandardCharsets.UTF_8),
                body.getBytes(StandardCharsets.UTF_8)));

        assertEquals(202, endpoint.receive(INTEGRATION_ID, headers, body.getBytes(StandardCharsets.UTF_8)).getStatus());
    }

    @Test
    void quotaExceededIs429WithRetryAfter() throws Exception {
        when(conversationService.startConversation(any(), anyString(), anyString(), anyMap()))
                .thenThrow(new QuotaExceededException("over"));
        Response response = endpoint.receive(INTEGRATION_ID, githubHeaders(PUSH, "d-8"), PUSH.getBytes(StandardCharsets.UTF_8));
        assertEquals(429, response.getStatus());
        assertEquals("60", response.getHeaderString("Retry-After"));
    }
}
