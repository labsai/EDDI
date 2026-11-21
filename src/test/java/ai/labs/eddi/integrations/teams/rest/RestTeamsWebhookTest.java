/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.teams.rest;

import ai.labs.eddi.engine.caching.CacheFactory;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.integrations.teams.TeamsActivityHandler;
import ai.labs.eddi.integrations.teams.TeamsTokenVerifier;
import ai.labs.eddi.integrations.teams.TeamsTokenVerifier.Outcome;
import ai.labs.eddi.integrations.teams.TeamsTokenVerifier.Verification;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class RestTeamsWebhookTest {

    static final String APP = "11111111-2222-3333-4444-555555555555";
    static final byte[] BODY = ("{\"type\":\"message\",\"id\":\"a1\",\"channelId\":\"msteams\",\"serviceUrl\":\"https://smba.trafficmanager.net/emea/\","
            + "\"conversation\":{\"id\":\"a:1\"}}").getBytes(StandardCharsets.UTF_8);

    private TeamsTokenVerifier verifier;
    private TeamsActivityHandler handler;
    private RestTeamsWebhook webhook;

    @BeforeEach
    void setUp() {
        verifier = mock(TeamsTokenVerifier.class);
        handler = mock(TeamsActivityHandler.class);
        ICacheFactory cacheFactory = mock(ICacheFactory.class);
        ICache<String, Boolean> cache = mock(ICache.class);
        Map<String, Boolean> seen = new ConcurrentHashMap<>();
        when(cache.putIfAbsent(any(), any())).thenAnswer(inv -> seen.putIfAbsent(inv.getArgument(0), inv.getArgument(1)));
        when(cacheFactory.<String, Boolean>getCache(eq(CacheFactory.TEAMS_ACTIVITY_DEDUP), any(Duration.class))).thenReturn(cache);
        webhook = new RestTeamsWebhook(verifier, handler, new ObjectMapper(), cacheFactory, 1024);
    }

    @Test
    @DisplayName("an unverified request is refused and never reaches the handler")
    void refused() {
        when(verifier.verify(any(), any(), any(), any())).thenReturn(new Verification(Outcome.SIGNATURE, null));
        assertEquals(401, webhook.messages("Bearer x", BODY).getStatus());
        verify(handler, never()).handleAsync(any(), any());
    }

    @Test
    @DisplayName("a key not endorsed for the channel answers 403")
    void endorsement() {
        when(verifier.verify(any(), any(), any(), any())).thenReturn(new Verification(Outcome.ENDORSEMENT, null));
        assertEquals(403, webhook.messages("Bearer x", BODY).getStatus());
    }

    @Test
    @DisplayName("a verified activity is acknowledged, passed on once, and its retry skipped")
    void verifiedAndDeduplicated() {
        when(verifier.verify(eq("Bearer x"), eq("https://smba.trafficmanager.net/emea/"), eq("msteams"), any()))
                .thenReturn(new Verification(Outcome.VALID, APP));
        assertEquals(200, webhook.messages("Bearer x", BODY).getStatus());
        assertEquals(200, webhook.messages("Bearer x", BODY).getStatus());
        verify(handler, times(1)).handleAsync(eq(APP), any());
    }

    @Test
    @DisplayName("an oversized body is refused before verification")
    void oversized() {
        assertEquals(413, webhook.messages("Bearer x", new byte[2048]).getStatus());
        verify(verifier, never()).verify(any(), any(), any(), any());
    }

    @Test
    @DisplayName("an unparseable body is authenticated first, and refused like a missing token")
    void garbage() {
        when(verifier.verify(any(), eq(null), eq(null), any())).thenReturn(new Verification(Outcome.MISSING, null));
        assertEquals(401, webhook.messages(null, "not json".getBytes(StandardCharsets.UTF_8)).getStatus());
    }
}
