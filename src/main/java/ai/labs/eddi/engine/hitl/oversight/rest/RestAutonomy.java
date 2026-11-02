/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.rest;

import ai.labs.eddi.engine.hitl.oversight.OversightErrors;
import ai.labs.eddi.engine.hitl.oversight.autonomy.AutonomyService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * @see IRestAutonomy
 */
@ApplicationScoped
public class RestAutonomy implements IRestAutonomy {

    static final int MAX_STATES = 1000;

    @Inject
    AutonomyService autonomy;

    @Inject
    OversightAccess access;

    @Override
    public CategoryList listStates(String agentId) {
        return new CategoryList(autonomy.list(access.requireReadScope(agentId), MAX_STATES));
    }

    @Override
    public AutonomyService.CategoryView readCategory(String agentId, String category) {
        access.requireReviewer(agentId);
        return autonomy.get(agentId, requireCategory(category));
    }

    @Override
    public AutonomyService.CategoryView apply(String agentId, String category, ApplyRequest request) {
        access.requireEditor(agentId);
        String c = requireCategory(category);
        if (request == null || request.recommendationId() == null) {
            throw OversightErrors.badRequest(OversightErrors.INVALID_PARAMETER, "recommendationId and expectedAgentVersion are required");
        }
        autonomy.apply(agentId, c, request.recommendationId(), request.expectedAgentVersion(), access.actor());
        return autonomy.get(agentId, c);
    }

    @Override
    public AutonomyService.CategoryView dismiss(String agentId, String category, DismissRequest request) {
        access.requireEditor(agentId);
        String c = requireCategory(category);
        autonomy.dismiss(agentId, c, request != null ? request.recommendationId() : null, request != null ? request.note() : null,
                access.actor());
        return autonomy.get(agentId, c);
    }

    @Override
    public AutonomyService.CategoryView suspend(String agentId, String category, NoteRequest request) {
        // Suspending only ever adds a gate, so a reviewer may do it.
        access.requireReviewer(agentId);
        String c = requireCategory(category);
        autonomy.suspendManually(agentId, c, request != null ? request.note() : null, access.actor());
        return autonomy.get(agentId, c);
    }

    @Override
    public AutonomyService.CategoryView lift(String agentId, String category, NoteRequest request) {
        access.requireEditor(agentId);
        String c = requireCategory(category);
        autonomy.lift(agentId, c, request != null ? request.note() : null, access.actor());
        return autonomy.get(agentId, c);
    }

    private static String requireCategory(String category) {
        String c = OversightAccess.blankToNull(category);
        if (c == null) {
            throw OversightErrors.badRequest(OversightErrors.INVALID_PARAMETER, "category is required");
        }
        return c;
    }
}
