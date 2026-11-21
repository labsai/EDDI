/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.outbound;

import ai.labs.eddi.engine.httpclient.outbound.OutboundEngine.Hop;
import ai.labs.eddi.engine.httpclient.outbound.OutboundEngine.Received;
import ai.labs.eddi.engine.security.ResolutionPrincipal;
import ai.labs.eddi.engine.security.ResolutionPrincipal.Provenance;
import ai.labs.eddi.engine.security.ResolutionPrincipalContext;
import ai.labs.eddi.engine.security.TurnOrigin;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The escalation guard (email plan v3 §5.3) in {@link OutboundHttp}: a turn a
 * mail started never writes to EDDI's channel store, on the first hop or on a
 * redirect that keeps the method.
 */
@DisplayName("OutboundHttp — the email escalation guard")
class OutboundHttpEscalationGuardTest {

    private final ResolutionPrincipalContext principals = new ResolutionPrincipalContext();

    @AfterEach
    void tearDown() {
        principals.clear();
    }

    /** Replays canned responses and records the hops. */
    private static final class Engine implements OutboundEngine {
        final List<Hop> hops = new ArrayList<>();
        private final Deque<Received> responses = new ArrayDeque<>();

        Engine respond(int status, Map<String, List<String>> headers) {
            responses.add(new Received(status, null, OutboundRequest.caseInsensitiveCopy(headers),
                    new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8))));
            return this;
        }

        @Override
        public Received send(Hop hop) {
            hops.add(hop);
            return responses.poll();
        }
    }

    private static OutboundHttp facade(Engine engine) {
        return new OutboundHttp(OutboundSettings.defaults(Duration.ofSeconds(10)), engine, false, new SimpleMeterRegistry());
    }

    private static OutboundRequest post(String url) {
        return OutboundRequest.builder(Profile.DEFAULT, URI.create(url)).ssrf(SsrfPolicy.NONE).method("POST", RequestBody.ofString("{}"))
                .redirects(RedirectPolicy.follow(3, MethodRule.JDK_SAFE)).build();
    }

    private static BodyMode<String> text() {
        return BodyMode.string(1024, BodyMode.OnOverflow.FAIL);
    }

    @Test
    @DisplayName("a tainted POST to the channel store is refused before it is sent")
    void firstHopRefused() {
        principals.bind(new ResolutionPrincipal("alice", Provenance.VERIFIED, TurnOrigin.EMAIL));
        Engine engine = new Engine();

        assertThrows(OutboundBlockedException.class,
                () -> facade(engine).send(post("https://eddi.example.com/channelstore/channels/abc/email-senders"), text()));
        assertEquals(0, engine.hops.size());
    }

    @Test
    @DisplayName("a 307 that keeps the method cannot carry a tainted POST to the channel store")
    void redirectHopRefused() {
        principals.bind(new ResolutionPrincipal("alice", Provenance.VERIFIED, TurnOrigin.EMAIL));
        Engine engine = new Engine().respond(307, Map.of("Location", List.of("https://eddi.example.com/channelstore/channels/abc")));

        assertThrows(OutboundBlockedException.class, () -> facade(engine).send(post("https://api.example.com/orders"), text()));
        assertEquals(1, engine.hops.size(), "only the first, unguarded hop was sent");
    }

    @Test
    @DisplayName("the same POST is sent from an untainted turn")
    void untaintedSent() throws Exception {
        Engine engine = new Engine().respond(200, Map.of());

        assertEquals(200, facade(engine).send(post("https://eddi.example.com/channelstore/channels/abc/email-senders"), text()).status());
        assertEquals(1, engine.hops.size());
    }

    /**
     * The sending half for every tool on {@code OutboundHttp}: each hop of a
     * tainted turn's request carries {@code X-Eddi-Turn-Origin: email} — a
     * cross-origin redirect hop too, where only the safe headers survive — so the
     * EDDI it reaches taints what it starts.
     */
    @Test
    @DisplayName("every hop of a tainted request carries X-Eddi-Turn-Origin, a cross-origin redirect hop included")
    void taintedHopsCarryTheHeader() throws Exception {
        principals.bind(new ResolutionPrincipal("alice", Provenance.VERIFIED, TurnOrigin.EMAIL));
        Engine engine = new Engine().respond(307, Map.of("Location", List.of("https://eddi.example.com/agents/a1/start")))
                .respond(200, Map.of());
        OutboundRequest start = OutboundRequest.builder(Profile.DEFAULT, URI.create("https://api.example.com/start")).ssrf(SsrfPolicy.NONE)
                .method("POST", RequestBody.ofString("{}")).header(TurnOrigin.HEADER, "none")
                .redirects(RedirectPolicy.follow(3, MethodRule.JDK_SAFE)).build();

        facade(engine).send(start, text());

        assertEquals(2, engine.hops.size());
        for (var hop : engine.hops) {
            assertEquals(List.of(TurnOrigin.EMAIL), OutboundRequest.caseInsensitiveCopy(hop.headers()).get(TurnOrigin.HEADER), hop.uri().toString());
        }
    }

    @Test
    @DisplayName("an untainted request carries no X-Eddi-Turn-Origin")
    void untaintedCarriesNoHeader() throws Exception {
        Engine engine = new Engine().respond(200, Map.of());

        facade(engine).send(post("https://eddi.example.com/agents/a1/start"), text());

        assertFalse(OutboundRequest.caseInsensitiveCopy(engine.hops.get(0).headers()).containsKey(TurnOrigin.HEADER));
    }
}
