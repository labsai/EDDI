/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents.rest;

import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.agents.CapabilityRegistryService.CapabilityMatch;
import ai.labs.eddi.configs.agents.IRestCapabilityRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Set;

/**
 * REST implementation for the A2A capability registry.
 *
 * @since 6.0.0
 */
@ApplicationScoped
public class RestCapabilityRegistry implements IRestCapabilityRegistry {

    private final CapabilityRegistryService registryService;

    @Inject
    public RestCapabilityRegistry(CapabilityRegistryService registryService) {
        this.registryService = registryService;
    }

    @Override
    public List<CapabilityMatch> searchBySkill(String skill, String strategy) {
        return registryService.findBySkill(skill, strategy);
    }

    @Override
    public Set<String> listSkills() {
        return registryService.getAllSkills();
    }
}
