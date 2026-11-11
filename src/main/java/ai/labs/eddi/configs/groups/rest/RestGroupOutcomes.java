/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.rest;

import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IRestGroupOutcomes;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.groups.outcomes.OutcomeEvent;
import ai.labs.eddi.engine.groups.outcomes.OutcomeEventService;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * @see IRestGroupOutcomes
 */
@ApplicationScoped
public class RestGroupOutcomes implements IRestGroupOutcomes {

    private static final Logger LOG = Logger.getLogger(RestGroupOutcomes.class);
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    private final OutcomeEventService outcomes;
    private final IAgentGroupStore groupStore;
    private final SecurityIdentity identity;
    private final ResourceAccessGuard accessGuard;

    @Inject
    public RestGroupOutcomes(OutcomeEventService outcomes, IAgentGroupStore groupStore, SecurityIdentity identity,
            ResourceAccessGuard accessGuard) {
        this.outcomes = outcomes;
        this.groupStore = groupStore;
        this.identity = identity;
        this.accessGuard = accessGuard;
    }

    @Override
    public Response record(String groupId, OutcomeRequest request) {
        try {
            requireGroupAccess(groupId, AccessLevel.EDIT);
            if (request == null) {
                return error(Response.Status.BAD_REQUEST, "A request body is required.");
            }
            var result = outcomes.record(groupId, new OutcomeEventService.Request(request.subjectType(), request.subjectId(), request.kind(),
                    request.metric(), request.value(), request.unit(), request.note(), request.source(), request.occurredAt(),
                    request.idempotencyKey()), principal());
            return switch (result.status()) {
                case RECORDED -> Response.status(Response.Status.CREATED).entity(result.event()).build();
                case DUPLICATE -> Response.ok(result.event()).build();
                case DISABLED, SUBJECT_FULL -> error(Response.Status.CONFLICT, result.message());
                case NOT_FOUND, GROUP_NOT_FOUND -> error(Response.Status.NOT_FOUND, result.message());
                case INVALID -> error(Response.Status.BAD_REQUEST, result.message());
            };
        } catch (IResourceStore.ResourceNotFoundException e) {
            return error(Response.Status.NOT_FOUND, e.getMessage());
        } catch (ForbiddenException e) {
            // the access guard's denial is a 403, not a failure of this endpoint
            throw e;
        } catch (Exception e) {
            LOG.errorf(e, "Failed to record an outcome for group %s", sanitize(groupId));
            return Response.serverError().build();
        }
    }

    @Override
    public Response list(String groupId, String subjectId, Integer limit) {
        try {
            requireGroupAccess(groupId, AccessLevel.VIEW);
            int size = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));
            List<OutcomeEvent> events = subjectId != null && !subjectId.isBlank()
                    ? outcomes.forSubject(groupId, subjectId.trim())
                    : outcomes.recent(groupId, size);
            return Response.ok(events.size() > size ? events.subList(0, size) : events).build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            return error(Response.Status.NOT_FOUND, e.getMessage());
        } catch (ForbiddenException e) {
            // the access guard's denial is a 403, not a failure of this endpoint
            throw e;
        } catch (Exception e) {
            LOG.errorf(e, "Failed to list outcomes for group %s", sanitize(groupId));
            return Response.serverError().build();
        }
    }

    private String principal() {
        return identity != null && identity.getPrincipal() != null && identity.getPrincipal().getName() != null
                && !identity.getPrincipal().getName().isBlank() ? identity.getPrincipal().getName() : "anonymous";
    }

    private static Response error(Response.Status status, String message) {
        return Response.status(status).entity(Map.of("error", message)).build();
    }

    /**
     * 404 for an unknown group, 403 when the caller may not reach it (as the
     * workspace endpoints).
     */
    private void requireGroupAccess(String groupId, AccessLevel required) throws IResourceStore.ResourceNotFoundException,
            IResourceStore.ResourceStoreException {
        if (groupStore.getCurrentResourceId(groupId) == null) {
            throw new IResourceStore.ResourceNotFoundException("Group not found.");
        }
        accessGuard.requireAccess(groupId, required, "group");
    }
}
