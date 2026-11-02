/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.groups.budget.rest;

import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.engine.groups.budget.TeamBudgetAccountingUnavailableException;
import ai.labs.eddi.engine.groups.budget.TeamBudgetMonth;
import ai.labs.eddi.engine.groups.budget.TeamBudgetService;
import ai.labs.eddi.engine.groups.budget.TeamBudgetSnapshot;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Budgets across teams over {@link TeamBudgetService}: listings are filtered by
 * VIEW per group, a transfer is {@code eddi-admin} at the interface. An outage
 * answers 503.
 */

@ApplicationScoped
public class RestTeamBudgets implements IRestTeamBudgets {

    private final TeamBudgetService service;
    private final ResourceAccessGuard accessGuard;
    private final SecurityIdentity identity;

    @Inject
    public RestTeamBudgets(TeamBudgetService service, ResourceAccessGuard accessGuard, SecurityIdentity identity) {
        this.service = service;
        this.accessGuard = accessGuard;
        this.identity = identity;
    }

    @Override
    public Response listBudgets(String groupIds) {
        Set<String> ids = new LinkedHashSet<>();
        if (groupIds != null) {
            for (String id : groupIds.split(",")) {
                if (!id.isBlank()) {
                    ids.add(id.trim());
                }
            }
        }
        if (ids.size() > MAX_LISTED_GROUPS) {
            return BudgetResponses.error(Response.Status.BAD_REQUEST, "At most " + MAX_LISTED_GROUPS + " groupIds per request.");
        }
        try {
            String month = service.currentMonth();
            List<String> visible = new ArrayList<>();
            Map<String, AgentGroupConfiguration> configs = new LinkedHashMap<>();
            for (String id : ids) {
                if (!accessGuard.hasAccess(id, AccessLevel.VIEW)) {
                    continue;
                }
                AgentGroupConfiguration config = service.config(id);
                if (config != null) {
                    visible.add(id);
                    configs.put(id, config);
                }
            }
            Map<String, TeamBudgetMonth> stored = new LinkedHashMap<>();
            for (TeamBudgetMonth m : service.store().readMany(visible, month)) {
                stored.put(m.groupId(), m);
            }
            List<TeamBudgetSnapshot> teams = new ArrayList<>();
            for (String id : visible) {
                teams.add(service.snapshot(id, configs.get(id), month, stored.get(id)));
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("month", month);
            body.put("tenantRemainingUsd", service.tenantRemaining());
            body.put("teams", teams);
            return BudgetResponses.ok(body);
        } catch (TeamBudgetAccountingUnavailableException e) {
            return BudgetResponses.unavailable(e);
        }
    }

    @Override
    public Response transfer(String idempotencyKey, TransferRequest request) {
        if (request == null || request.amountUsd() == null) {
            return BudgetResponses.error(Response.Status.BAD_REQUEST, "fromGroupId, toGroupId and amountUsd are required.");
        }
        try {
            return BudgetResponses.ok(service.transfer(request.fromGroupId(), request.toGroupId(), request.amountUsd(), request.reason(),
                    BudgetResponses.actor(identity), idempotencyKey));
        } catch (IllegalArgumentException e) {
            return BudgetResponses.error(Response.Status.BAD_REQUEST, e.getMessage());
        } catch (TeamBudgetAccountingUnavailableException e) {
            return BudgetResponses.unavailable(e);
        }
    }

}
