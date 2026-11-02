/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.groups.budget.rest;

import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.engine.groups.budget.TeamBudgetAccountingUnavailableException;
import ai.labs.eddi.engine.groups.budget.TeamBudgetMonth;
import ai.labs.eddi.engine.groups.budget.TeamBudgetService;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * One team's budget over {@link TeamBudgetService}: reads are guarded per group
 * (VIEW), moving the cap is {@code eddi-admin} at the interface. An outage
 * answers 503, never an empty budget.
 */

@ApplicationScoped
public class RestGroupBudget implements IRestGroupBudget {

    private final TeamBudgetService service;
    private final ResourceAccessGuard accessGuard;
    private final SecurityIdentity identity;

    @Inject
    public RestGroupBudget(TeamBudgetService service, ResourceAccessGuard accessGuard, SecurityIdentity identity) {
        this.service = service;
        this.accessGuard = accessGuard;
        this.identity = identity;
    }

    @Override
    public Response readBudget(String groupId) {
        accessGuard.requireAccess(groupId, AccessLevel.VIEW, "group");
        if (service.config(groupId) == null) {
            return BudgetResponses.error(Response.Status.NOT_FOUND, "Group not found.");
        }
        try {
            return BudgetResponses.ok(service.snapshot(groupId));
        } catch (TeamBudgetAccountingUnavailableException e) {
            return BudgetResponses.unavailable(e);
        }
    }

    @Override
    public Response readHistory(String groupId, int months) {
        accessGuard.requireAccess(groupId, AccessLevel.VIEW, "group");
        if (service.config(groupId) == null) {
            return BudgetResponses.error(Response.Status.NOT_FOUND, "Group not found.");
        }
        try {
            List<TeamBudgetMonth> history = service.history(groupId, months);
            return BudgetResponses.ok(Map.of("groupId", groupId, "months", history));
        } catch (TeamBudgetAccountingUnavailableException e) {
            return BudgetResponses.unavailable(e);
        }
    }

    @Override
    public Response setCurrentMonthCap(String groupId, CapRequest request) {
        if (request == null || request.capUsd() == null) {
            return BudgetResponses.error(Response.Status.BAD_REQUEST, "capUsd is required.");
        }
        try {
            return BudgetResponses.ok(service.setCurrentMonthCap(groupId, request.capUsd(), request.reason(), BudgetResponses.actor(identity)));
        } catch (IllegalArgumentException e) {
            return BudgetResponses.error(Response.Status.BAD_REQUEST, e.getMessage());
        } catch (TeamBudgetAccountingUnavailableException e) {
            return BudgetResponses.unavailable(e);
        }
    }

}
