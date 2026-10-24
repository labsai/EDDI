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
import java.util.Locale;
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
    private static final String SECRET = "test-key-test-key";
    private static final String PUSH = "{\"ref\":\"refs/heads/main\",\"repository\":{\"full_name\":\"labsai/EDDI\"}}";

    private ChannelTargetRouter router;
    private IConversationService conversationService;
    private RestWebhookChannel endpoint;
    private SimpleMeterRegistry meters;
    private ChannelIntegrationConfiguration integration;

    @BeforeEach
    void setUp() throws Exception {
        router = mock(ChannelTargetRouter.class);
        conversationService = mock(IConversationService.class);
        ICacheFactory cacheFactory = mock(ICacheFactory.class);
        when(cacheFactory.getCache(anyString(), any(Duration.class)))
                .thenAnswer(inv -> new CacheImpl<>("dedup", Caffeine.newBuilder().build()));
        var handler = new WebhookEventHandler(router, conversationService, mock(IGroupConversationService.class),
                mock(IUserConversationStore.class), mock(ITemplatingEngine.class), cacheFactory, meters = new SimpleMeterRegistry());
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
        assertTrue(repeat[0].getEntity().toString().contains("\"reason\":\"in_progress\""));
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
    void unknownIntegrationAnswersExactlyLikeABadSignature() {
        // A probe must not be able to tell a real id from a made-up one.
        Map<String, String> unsigned = githubHeaders(PUSH, "d-5");
        unsigned.remove("x-hub-signature-256");
        Response badSignature = endpoint.receive(INTEGRATION_ID, unsigned, PUSH.getBytes(StandardCharsets.UTF_8));
        Response unknown = endpoint.receive("ffffffffffffffffffffffff", githubHeaders(PUSH, "d-5"),
                PUSH.getBytes(StandardCharsets.UTF_8));
        Response malformedId = endpoint.receive("../etc", githubHeaders(PUSH, "d-5"), PUSH.getBytes(StandardCharsets.UTF_8));

        assertEquals(401, unknown.getStatus());
        assertEquals(401, malformedId.getStatus());
        assertEquals(badSignature.getEntity(), unknown.getEntity());
        assertEquals(badSignature.getEntity(), malformedId.getEntity());
        verifyNoInteractions(conversationService);
    }

    @Test
    void replayUnderADifferentRoutingHeaderIsStillADuplicate() throws Exception {
        // The event-type header is outside the MAC (github signs the body only), so
        // a captured body can be resent with another one. The replay key is derived
        // from the MAC, so no header variation reaches a second turn.
        Map<String, String> first = githubHeaders(PUSH, "unused");
        first.remove("x-github-delivery");
        Map<String, String> rerouted = new HashMap<>(first);
        rerouted.put("x-github-event", "ping");

        assertEquals(202, endpoint.receive(INTEGRATION_ID, first, PUSH.getBytes(StandardCharsets.UTF_8)).getStatus());
        assertEquals(200, endpoint.receive(INTEGRATION_ID, rerouted, PUSH.getBytes(StandardCharsets.UTF_8)).getStatus());
        verify(conversationService, times(1)).say(anyString(), any(), any(), any(), any(), anyBoolean(), any());
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
    void eddiV1ReplayWithAnExtraInvalidCandidateIsStillADuplicate() throws Exception {
        // An ID-less plain-JSON event. The verifier accepts a header whose ANY v1=
        // matches, so appending a junk candidate keeps the signature valid — the
        // dedup key must not change with it.
        integration.setPlatformConfig(Map.of("signingSecret", SECRET));
        long t = Instant.now().getEpochSecond();
        String header = "t=" + t + ",v1=" + HmacSignatures.hmacSha256Hex(SECRET, (t + ".").getBytes(StandardCharsets.UTF_8),
                PUSH.getBytes(StandardCharsets.UTF_8));
        Map<String, String> first = new HashMap<>(Map.of("content-type", "application/json", "x-eddi-signature", header));
        Map<String, String> replay = new HashMap<>(Map.of("content-type", "application/json",
                "x-eddi-signature", header + ",v1=" + "ab".repeat(32)));

        assertEquals(202, endpoint.receive(INTEGRATION_ID, first, PUSH.getBytes(StandardCharsets.UTF_8)).getStatus());
        assertEquals(200, endpoint.receive(INTEGRATION_ID, replay, PUSH.getBytes(StandardCharsets.UTF_8)).getStatus());
        verify(conversationService, times(1)).say(anyString(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void githubReplayUnderANewDeliveryIdIsStillADuplicate() throws Exception {
        // X-GitHub-Delivery is not covered by GitHub's signature, so a captured
        // request resent with a fresh delivery id (and upper-cased hex, which the
        // verifier also accepts) must not start another turn.
        assertEquals(202, endpoint.receive(INTEGRATION_ID, githubHeaders(PUSH, "d-40"), PUSH.getBytes(StandardCharsets.UTF_8)).getStatus());
        Map<String, String> substituted = githubHeaders(PUSH, "d-41");
        substituted.put("x-hub-signature-256",
                "sha256=" + HmacSignatures.hmacSha256Hex(SECRET, PUSH.getBytes(StandardCharsets.UTF_8)).toUpperCase(Locale.ROOT));

        assertEquals(200, endpoint.receive(INTEGRATION_ID, substituted, PUSH.getBytes(StandardCharsets.UTF_8)).getStatus());
        verify(conversationService, times(1)).say(anyString(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void distinctDeliveryIdsWithDifferentBodiesAreBothProcessed() throws Exception {
        String other = PUSH.replace("main", "release");
        assertEquals(202, endpoint.receive(INTEGRATION_ID, githubHeaders(PUSH, "d-50"), PUSH.getBytes(StandardCharsets.UTF_8)).getStatus());
        assertEquals(202, endpoint.receive(INTEGRATION_ID, githubHeaders(other, "d-51"), other.getBytes(StandardCharsets.UTF_8)).getStatus());
        verify(conversationService, times(2)).say(anyString(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void replayWithAPrependedTimestampIsRefused() throws Exception {
        integration.setPlatformConfig(Map.of("signingSecret", SECRET));
        long t = Instant.now().getEpochSecond();
        String header = "t=" + t + ",v1=" + HmacSignatures.hmacSha256Hex(SECRET, (t + ".").getBytes(StandardCharsets.UTF_8),
                PUSH.getBytes(StandardCharsets.UTF_8));
        assertEquals(202, endpoint.receive(INTEGRATION_ID, new HashMap<>(Map.of("content-type", "application/json", "x-eddi-signature", header)),
                PUSH.getBytes(StandardCharsets.UTF_8)).getStatus());

        for (int x = 1; x <= 3; x++) {
            var replay = new HashMap<>(Map.of("content-type", "application/json", "x-eddi-signature", "t=" + x + "," + header));
            assertEquals(401, endpoint.receive(INTEGRATION_ID, replay, PUSH.getBytes(StandardCharsets.UTF_8)).getStatus());
        }
        verify(conversationService, times(1)).say(anyString(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void refusalsBeforeDispatchAreCounted() {
        Map<String, String> unsigned = githubHeaders(PUSH, "d-60");
        unsigned.remove("x-hub-signature-256");
        endpoint.receive(INTEGRATION_ID, unsigned, PUSH.getBytes(StandardCharsets.UTF_8));
        endpoint.receive("ffffffffffffffffffffffff", githubHeaders(PUSH, "d-61"), PUSH.getBytes(StandardCharsets.UTF_8));

        assertEquals(2.0, meters.counter("eddi.webhook.events", "outcome", "refused_401").count());
    }

    @Test
    void quotaExceededIs429WithRetryAfter() throws Exception {
        when(conversationService.startConversation(any(), anyString(), anyString(), anyMap()))
                .thenThrow(new QuotaExceededException("over"));
        Response response = endpoint.receive(INTEGRATION_ID, githubHeaders(PUSH, "d-8"), PUSH.getBytes(StandardCharsets.UTF_8));
        assertEquals(429, response.getStatus());
        assertEquals("60", response.getHeaderString("Retry-After"));
    }

    @Test
    void awaitingApprovalIs409WithReasonAndNoRetryAfter() throws Exception {
        doThrow(new IConversationService.ConversationAwaitingApprovalException("waiting")).when(conversationService)
                .say(anyString(), any(), any(), any(), any(), anyBoolean(), any());
        Response response = endpoint.receive(INTEGRATION_ID, githubHeaders(PUSH, "d-90"), PUSH.getBytes(StandardCharsets.UTF_8));

        assertEquals(409, response.getStatus());
        assertTrue(response.getEntity().toString().contains("\"reason\":\"awaiting_approval\""));
        assertNull(response.getHeaderString("Retry-After"), "a human decision does not clear by retrying");
    }
}
