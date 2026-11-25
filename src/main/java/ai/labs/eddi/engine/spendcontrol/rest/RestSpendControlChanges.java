/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.spendcontrol.rest;

import ai.labs.eddi.engine.spendcontrol.SpendControlChangeService;
import ai.labs.eddi.engine.spendcontrol.SpendControlException;
import ai.labs.eddi.engine.spendcontrol.model.SpendControlChange.Status;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A thin layer over {@link SpendControlChangeService}; a refusal answers
 * {@code {"error": "...", "code": "...", ...details}} with its status. The list
 * defaults to the pending changes, the inbox an administrator works.
 */
@ApplicationScoped
public class RestSpendControlChanges implements IRestSpendControlChanges {

    private final SpendControlChangeService service;
    private final SecurityIdentity identity;

    @Inject
    public RestSpendControlChanges(SpendControlChangeService service, SecurityIdentity identity) {
        this.service = service;
        this.identity = identity;
    }

    @Override
    public Response list(String status, Integer limit) {
        return answer(() -> {
            Status filter = status == null || status.isBlank() ? Status.PENDING : SpendControlChangeService.parseStatus(status);
            return service.list(filter, limit);
        });
    }

    @Override
    public Response read(String id) {
        return answer(() -> service.read(id));
    }

    @Override
    public Response approve(String id, DecisionRequest request) {
        return answer(() -> service.approve(id, request == null ? null : request.note(), identity));
    }

    @Override
    public Response reject(String id, DecisionRequest request) {
        return answer(() -> service.reject(id, request == null ? null : request.note(), identity));
    }

    private static Response answer(Supplier<Object> call) {
        try {
            return Response.ok(call.get(), MediaType.APPLICATION_JSON_TYPE).build();
        } catch (SpendControlException e) {
            return refusal(e);
        }
    }

    /** The body every surface reports for a refusal. */
    public static Response refusal(SpendControlException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.getMessage());
        body.put("code", e.code());
        body.putAll(e.details());
        return Response.status(e.status()).type(MediaType.APPLICATION_JSON_TYPE).entity(body).build();
    }
}
