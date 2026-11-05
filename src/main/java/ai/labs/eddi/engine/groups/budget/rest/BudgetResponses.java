/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.groups.budget.rest;

import ai.labs.eddi.engine.groups.budget.TeamBudgetAccountingUnavailableException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/** Response and identity helpers shared by the two team-budget REST classes. */
final class BudgetResponses {

    private BudgetResponses() {
    }

    static String actor(SecurityIdentity identity) {
        return identity != null && identity.getPrincipal() != null && identity.getPrincipal().getName() != null
                ? identity.getPrincipal().getName()
                : "unknown";
    }

    static Response ok(Object body) {
        return Response.ok(body, MediaType.APPLICATION_JSON_TYPE).build();
    }

    static Response error(Response.Status status, String message) {
        return Response.status(status).type(MediaType.APPLICATION_JSON_TYPE).entity(Map.of("error", message)).build();
    }

    static Response unavailable(TeamBudgetAccountingUnavailableException e) {
        return error(Response.Status.SERVICE_UNAVAILABLE, "The team budget could not be read right now: " + e.getMessage());
    }
}
