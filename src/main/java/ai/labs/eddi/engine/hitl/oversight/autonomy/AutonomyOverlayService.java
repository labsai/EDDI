/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.autonomy;

import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.engine.hitl.oversight.OversightSettings;
import ai.labs.eddi.engine.memory.IConversationMemory;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Puts an agent's suspended earned-autonomy categories on the tool-approval
 * config the gate sees — as a runtime copy, never by rewriting configuration.
 * <p>
 * The list of an agent's suspensions is cached per node for
 * {@code eddi.hitl.autonomy.suspension-cache-ttl} (default 15 s) and dropped
 * locally when this node writes one, so a suspension reaches every pod within
 * the TTL. When the store cannot be read the last known list is used; with none
 * known, no extra gate is added (and the failure is logged) — a database blip
 * must not pause every exempt call of a deployment.
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class AutonomyOverlayService {

    private static final Logger LOGGER = Logger.getLogger(AutonomyOverlayService.class);
    static final int MAX_AGENTS = 10_000;

    private final IAutonomyStateStore stateStore;
    private final OversightSettings settings;
    private Cache<String, Map<String, String>> cache;
    private Cache<String, Map<String, String>> lastKnown;

    @Inject
    public AutonomyOverlayService(IAutonomyStateStore stateStore, OversightSettings settings) {
        this.stateStore = stateStore;
        this.settings = settings;
        init();
    }

    @PostConstruct
    void init() {
        if (cache != null) {
            return;
        }
        Duration ttl = settings != null && settings.suspensionCacheTtl() != null ? settings.suspensionCacheTtl() : Duration.ofSeconds(15);
        cache = Caffeine.newBuilder().maximumSize(MAX_AGENTS).expireAfterWrite(ttl).build();
        lastKnown = Caffeine.newBuilder().maximumSize(MAX_AGENTS).expireAfterWrite(Duration.ofHours(1)).build();
    }

    /**
     * The agent's suspended patterns, each with its category, in a stable order.
     * Empty when none (or when the agent is unknown).
     */
    public Map<String, String> suspendedPatterns(String agentId) {
        if (agentId == null) {
            return Map.of();
        }
        Map<String, String> cached = cache.getIfPresent(agentId);
        if (cached != null) {
            return cached;
        }
        try {
            Map<String, String> loaded = new LinkedHashMap<>();
            for (AutonomyState state : stateStore.listSuspended(agentId)) {
                if (state.suspended() && state.suspension().pattern() != null) {
                    loaded.putIfAbsent(state.suspension().pattern(), state.category());
                }
            }
            Map<String, String> result = loaded.isEmpty() ? Map.of() : Collections.unmodifiableMap(loaded);
            cache.put(agentId, result);
            lastKnown.put(agentId, result);
            return result;
        } catch (RuntimeException e) {
            Map<String, String> fallback = lastKnown.getIfPresent(agentId);
            LOGGER.errorf("Could not read the earned-autonomy suspensions of agent %s (%s) — %s", sanitize(agentId), e.getMessage(),
                    fallback != null ? "using the last known list" : "no suspension is applied until the store answers");
            return fallback != null ? fallback : Map.of();
        }
    }

    /**
     * The config with the agent's suspensions on it (the same instance when none).
     */
    public ToolApprovalsConfig apply(String agentId, ToolApprovalsConfig cfg) {
        if (!SuspendedGate.none(cfg)) {
            return cfg;
        }
        return SuspendedGate.overlay(cfg, suspendedPatterns(agentId));
    }

    /** Drops this node's cached list of the agent (after a write). */
    public void invalidate(String agentId) {
        if (agentId != null) {
            cache.invalidate(agentId);
        }
    }

    /**
     * Null-safe {@link #apply} for the LLM task, where the service is
     * field-injected and absent under direct construction.
     */
    public static ToolApprovalsConfig applyTo(AutonomyOverlayService service, IConversationMemory memory, ToolApprovalsConfig cfg) {
        if (service == null || memory == null) {
            return cfg;
        }
        return service.apply(memory.getAgentId(), cfg);
    }

    /**
     * Puts the overlay on the memory's agent-level tool-approval carrier, so every
     * reader of it this turn — the resume path included — sees the suspensions. The
     * carrier is transient and never persisted.
     */
    public static void applyToCarrier(AutonomyOverlayService service, IConversationMemory memory) {
        if (service == null || memory == null) {
            return;
        }
        ToolApprovalsConfig carrier = memory.getAgentToolApprovalsConfig();
        ToolApprovalsConfig overlaid = service.apply(memory.getAgentId(), carrier);
        if (overlaid != carrier) {
            memory.setAgentToolApprovalsConfig(overlaid);
        }
    }
}
