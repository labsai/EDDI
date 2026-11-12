/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.hitl.model.OversightConfig;
import ai.labs.eddi.configs.hitl.model.SampledAuditConfig;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.ForbiddenException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An agent's own call (marked {@code X-EDDI-Agent-Call}) reaching {@code PUT
 * /agentstore/agents/{id}} may not change that agent's
 * {@code hitlConfig.oversight}; an unmarked request, and a marked one that
 * leaves the oversight block as stored, pass.
 */
@DisplayName("AgentCallOversightGuard — agent-originated writes cannot change oversight")
class AgentCallOversightGuardTest {

    private static AgentConfiguration agentWithSampleRate(Double rate) {
        var agent = new AgentConfiguration();
        if (rate == null) {
            return agent;
        }
        var rule = new SampledAuditConfig.Rule();
        rule.setMatch("*");
        rule.setRate(rate);
        var audit = new SampledAuditConfig();
        audit.setRules(List.of(rule));
        var oversight = new OversightConfig();
        oversight.setSampledAudit(audit);
        var hitl = new AgentConfiguration.HitlConfig();
        hitl.setOversight(oversight);
        agent.setHitlConfig(hitl);
        return agent;
    }

    private static AgentCallOversightGuard guard(String marker) {
        CurrentVertxRequest current = mock(CurrentVertxRequest.class);
        RoutingContext context = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(current.getCurrent()).thenReturn(context);
        when(context.request()).thenReturn(request);
        when(request.getHeader(AgentCallMarker.HEADER)).thenReturn(marker);
        return new AgentCallOversightGuard(current);
    }

    @Test
    @DisplayName("a marked write that lowers the sample rate is refused with 403")
    void loweringTheSampleRateIsRefused() {
        var stored = agentWithSampleRate(0.5);
        var submitted = agentWithSampleRate(0.0);

        var refused = assertThrows(ForbiddenException.class,
                () -> guard(AgentCallMarker.VALUE).requireNoOversightChange("agent-1", submitted, () -> stored));
        assertEquals(403, refused.getResponse().getStatus());
    }

    @Test
    @DisplayName("a marked write that drops the oversight block is a change too")
    void droppingOversightIsRefused() {
        assertEquals(OversightWriteGuard.OVERSIGHT_CONFIG_REASON,
                AgentCallOversightGuard.refusal("agent-1", agentWithSampleRate(null), () -> agentWithSampleRate(0.5)));
    }

    @Test
    @DisplayName("a marked write that keeps oversight as stored passes")
    void unchangedOversightPasses() {
        assertDoesNotThrow(() -> guard(AgentCallMarker.VALUE).requireNoOversightChange("agent-1", agentWithSampleRate(0.5),
                () -> agentWithSampleRate(0.5)));
        assertNull(AgentCallOversightGuard.refusal("agent-1", agentWithSampleRate(null), () -> agentWithSampleRate(null)));
    }

    @Test
    @DisplayName("a marked write adding oversight to an agent that had none is refused")
    void addingOversightIsRefused() {
        assertEquals(OversightWriteGuard.OVERSIGHT_CONFIG_REASON,
                AgentCallOversightGuard.refusal("agent-1", agentWithSampleRate(1.0), () -> agentWithSampleRate(null)));
    }

    @Test
    @DisplayName("an unreadable stored agent refuses a write that names oversight")
    void unreadableStoredFailsClosed() {
        assertEquals(OversightWriteGuard.OVERSIGHT_CONFIG_REASON, AgentCallOversightGuard.refusal("agent-1", agentWithSampleRate(0.0), () -> {
            throw new IllegalStateException("store down");
        }));
    }

    @Test
    @DisplayName("an unmarked request — a human's edit — changes oversight freely")
    void unmarkedPasses() {
        var guard = guard(null);
        assertFalse(guard.isAgentOriginated());
        assertDoesNotThrow(() -> guard.requireNoOversightChange("agent-1", agentWithSampleRate(0.0), () -> agentWithSampleRate(0.5)));
    }

    @Test
    @DisplayName("no active request is not an agent call")
    void noActiveRequest() {
        CurrentVertxRequest current = mock(CurrentVertxRequest.class);
        when(current.getCurrent()).thenThrow(new IllegalStateException("no request context"));
        assertFalse(new AgentCallOversightGuard(current).isAgentOriginated());
        assertTrue(guard(AgentCallMarker.VALUE).isAgentOriginated());
    }
}
