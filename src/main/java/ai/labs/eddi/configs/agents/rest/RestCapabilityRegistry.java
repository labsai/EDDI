/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents.rest;

import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.agents.CapabilityRegistryService.CapabilityMatch;
import ai.labs.eddi.configs.agents.IRestCapabilityRegistry;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * REST implementation for the A2A capability registry.
 *
 * @since 6.0.0
 */
@ApplicationScoped
public class RestCapabilityRegistry implements IRestCapabilityRegistry {

    private final CapabilityRegistryService registryService;
    private final ResourceAccessGuard accessGuard;

    @Inject
    public RestCapabilityRegistry(CapabilityRegistryService registryService, ResourceAccessGuard accessGuard) {
        this.registryService = registryService;
        this.accessGuard = accessGuard;
    }

    /**
     * The matches among agents the caller may {@link AccessLevel#USE}. The registry
     * indexes every agent in the deployment, so with workspaces enforced this
     * answered any editor with the ids of other teams' agents. The filter runs
     * before the strategy, so a {@code highest_confidence} pick is the best agent
     * the caller may use, never one they may not. Unfiltered while workspaces are
     * not enforced, and for administrators.
     */
    @Override
    public List<CapabilityMatch> searchBySkill(String skill, String strategy) {
        if (accessGuard.seesEverything()) {
            return registryService.findBySkill(skill, strategy);
        }
        return registryService.findBySkill(skill, strategy, usableByCaller());
    }

    /** The skills of agents the caller may use — see {@link #searchBySkill}. */
    @Override
    public Set<String> listSkills() {
        if (accessGuard.seesEverything()) {
            return registryService.getAllSkills();
        }
        return registryService.getSkills(usableByCaller());
    }

    /** One descriptor read per agent and request, however often it is asked. */
    private Predicate<String> usableByCaller() {
        Map<String, Boolean> usable = new HashMap<>();
        return agentId -> usable.computeIfAbsent(agentId, id -> accessGuard.hasAccess(id, AccessLevel.USE));
    }
}
