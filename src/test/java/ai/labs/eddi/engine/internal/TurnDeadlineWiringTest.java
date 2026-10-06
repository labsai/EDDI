/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.shared.MutableTestClock;
import ai.labs.eddi.configs.shared.TurnDeadline;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.runtime.IAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Modifier;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * How a turn's deadline gets from the agent config and the request header onto
 * the conversation memory — and that it never goes any further.
 */
@DisplayName("Turn deadline wiring")
class TurnDeadlineWiringTest {

    private final MutableTestClock clock = new MutableTestClock();

    private static IAgent agent(Long deadlineMs, Long reserveMs) {
        IAgent agent = mock(IAgent.class);
        when(agent.getTurnDeadlineMs()).thenReturn(deadlineMs);
        when(agent.getTurnDeadlineReserveMs()).thenReturn(reserveMs);
        return agent;
    }

    private static InputData input(Long requestedMs) {
        var input = new InputData("hi", new HashMap<>());
        input.setRequestedTurnDeadlineMs(requestedMs);
        return input;
    }

    // ─── ConversationService.applyTurnDeadline ──────────────────────

    @Test
    @DisplayName("agent config alone sets the deadline, counted from the request's arrival")
    void configAlone() {
        var memory = new ConversationMemory("a", 1, "u");

        ConversationService.applyTurnDeadline(memory, agent(55_000L, 1_000L), input(null), clock, clock.millis() - 5_000);

        assertNotNull(memory.getTurnDeadline());
        assertEquals(50_000, memory.getTurnDeadline().remainingMs());
        assertEquals(1_000, memory.getTurnDeadline().reserveMs());
    }

    @Test
    @DisplayName("a header alone enables a deadline when the agent configures none")
    void headerAlone() {
        var memory = new ConversationMemory("a", 1, "u");

        ConversationService.applyTurnDeadline(memory, agent(null, null), input(20_000L), clock, clock.millis());

        assertEquals(20_000, memory.getTurnDeadline().remainingMs());
        assertEquals(TurnDeadline.DEFAULT_RESERVE_MS, memory.getTurnDeadline().reserveMs());
    }

    @Test
    @DisplayName("the header can shorten the configured deadline but never extend it")
    void headerIsCappedByConfig() {
        var memory = new ConversationMemory("a", 1, "u");

        ConversationService.applyTurnDeadline(memory, agent(55_000L, null), input(20_000L), clock, clock.millis());
        assertEquals(20_000, memory.getTurnDeadline().remainingMs());

        ConversationService.applyTurnDeadline(memory, agent(55_000L, null), input(120_000L), clock, clock.millis());
        assertEquals(55_000, memory.getTurnDeadline().remainingMs());
    }

    @Test
    @DisplayName("no config and no header: no deadline, and a previous turn's deadline is cleared")
    void neitherClearsTheOldOne() {
        var memory = new ConversationMemory("a", 1, "u");
        ConversationService.applyTurnDeadline(memory, agent(55_000L, null), input(null), clock, clock.millis());
        assertNotNull(memory.getTurnDeadline());

        ConversationService.applyTurnDeadline(memory, agent(null, null), input(null), clock, clock.millis());

        assertNull(memory.getTurnDeadline());
    }

    @Test
    @DisplayName("a null input (internal caller) is tolerated")
    void nullInput() {
        var memory = new ConversationMemory("a", 1, "u");
        ConversationService.applyTurnDeadline(memory, agent(30_000L, null), null, clock, clock.millis());
        assertEquals(30_000, memory.getTurnDeadline().remainingMs());
    }

    // ─── the header ─────────────────────────────────────────────────

    private static TurnDeadlineHeaderReader readerWithHeader(String value) {
        var routing = mock(RoutingContext.class);
        var request = mock(HttpServerRequest.class);
        when(routing.request()).thenReturn(request);
        when(request.getHeader(TurnDeadline.HEADER)).thenReturn(value);
        var current = mock(CurrentVertxRequest.class);
        when(current.getCurrent()).thenReturn(routing);
        return new TurnDeadlineHeaderReader(current);
    }

    @Test
    @DisplayName("the reader stamps a valid header on the input")
    void readerStampsValidHeader() {
        var input = new InputData();
        readerWithHeader("45000").apply(input);
        assertEquals(45_000L, input.getRequestedTurnDeadlineMs());
    }

    @Test
    @DisplayName("junk, negative and missing headers are ignored")
    void readerIgnoresBadValues() {
        for (String bad : new String[]{"soon", "-5", "0", "", null}) {
            var input = new InputData();
            readerWithHeader(bad).apply(input);
            assertNull(input.getRequestedTurnDeadlineMs(), "value: " + bad);
        }
    }

    @Test
    @DisplayName("no active request (internal caller) means no header, not an error")
    void readerWithoutRequest() {
        var current = mock(CurrentVertxRequest.class);
        when(current.getCurrent()).thenThrow(new IllegalStateException("no request scope"));
        var input = new InputData();
        assertDoesNotThrow(() -> new TurnDeadlineHeaderReader(current).apply(input));
        assertNull(input.getRequestedTurnDeadlineMs());
    }

    @Test
    @DisplayName("a request body cannot set the requested deadline")
    void bodyCannotSetIt() throws Exception {
        var mapper = new ObjectMapper();
        var parsed = mapper.readValue("{\"input\":\"hi\",\"requestedTurnDeadlineMs\":1}", InputData.class);
        assertNull(parsed.getRequestedTurnDeadlineMs());

        var withHeader = input(7_000L);
        assertFalse(mapper.writeValueAsString(withHeader).contains("requestedTurnDeadlineMs"));
    }

    // ─── persistence ────────────────────────────────────────────────

    @Test
    @DisplayName("the deadline on the memory is transient: it does not survive serialization")
    void deadlineIsNotPersisted() throws Exception {
        var field = ConversationMemory.class.getDeclaredField("turnDeadline");
        assertTrue(Modifier.isTransient(field.getModifiers()));

        var memory = new ConversationMemory("a", 1, "u");
        memory.setTurnDeadline(TurnDeadline.of(clock, clock.millis(), 10_000, null));

        var bytes = new ByteArrayOutputStream();
        try (var out = new ObjectOutputStream(bytes)) {
            out.writeObject(memory);
        }
        try (var in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            var restored = (ConversationMemory) in.readObject();
            assertNull(restored.getTurnDeadline());
        }
    }

    @Test
    @DisplayName("the agent config reads turnDeadlineMs and turnDeadlineReserveMs from JSON; both default to unset")
    void agentConfigFields() throws Exception {
        var mapper = new ObjectMapper();
        var cfg = mapper.readValue("{\"turnDeadlineMs\":55000,\"turnDeadlineReserveMs\":2000}", AgentConfiguration.class);
        assertEquals(55_000L, cfg.getTurnDeadlineMs());
        assertEquals(2_000L, cfg.getTurnDeadlineReserveMs());

        var empty = mapper.readValue("{}", AgentConfiguration.class);
        assertNull(empty.getTurnDeadlineMs());
        assertNull(empty.getTurnDeadlineReserveMs());
    }
}
