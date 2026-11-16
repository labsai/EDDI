/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.rest;

import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IRestTeamHandoffs;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.runtime.internal.handoff.TeamHandoffIntake;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * REST implementation of {@link IRestTeamHandoffs}. Access follows the
 * workspace: VIEW to read, EDIT to decide, both against the <em>group</em>'s
 * descriptor, with existence checked first so a caller who cannot see a group
 * cannot tell "not yours" from "does not exist" (same order as
 * {@link RestGroupWorkspace}).
 */
@ApplicationScoped
public class RestTeamHandoffs implements IRestTeamHandoffs {

    private static final Logger LOG = Logger.getLogger(RestTeamHandoffs.class);

    private final TeamHandoffIntake intake;
    private final IAgentGroupStore groupStore;
    private final SecurityIdentity identity;
    private final ResourceAccessGuard accessGuard;

    @Inject
    public RestTeamHandoffs(TeamHandoffIntake intake, IAgentGroupStore groupStore, SecurityIdentity identity, ResourceAccessGuard accessGuard) {
        this.intake = intake;
        this.groupStore = groupStore;
        this.identity = identity;
        this.accessGuard = accessGuard;
    }

    @Override
    public Response readHandoffs(String groupId) {
        try {
            requireGroupAccess(groupId, AccessLevel.VIEW);
            var view = intake.view(groupId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("inbox", view.inbox());
            body.put("origins", view.origins());
            return Response.ok(body).build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", e.getMessage())).build();
        } catch (WebApplicationException | ForbiddenException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorf(e, "Failed to read handoffs for group %s", sanitize(groupId));
            return Response.serverError().build();
        }
    }

    @Override
    public Response acceptHandoff(String groupId, String handoffId, AcceptRequest request) {
        try {
            requireGroupAccess(groupId, AccessLevel.EDIT);
            var edits = request != null
                    ? new TeamHandoffIntake.Edits(request.subject(), request.description(), request.priority(), request.note())
                    : null;
            return toResponse(intake.accept(groupId, handoffId, edits, actingUser()));
        } catch (IResourceStore.ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", e.getMessage())).build();
        } catch (WebApplicationException | ForbiddenException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorf(e, "Failed to accept handoff %s for group %s", sanitize(handoffId), sanitize(groupId));
            return Response.serverError().build();
        }
    }

    @Override
    public Response declineHandoff(String groupId, String handoffId, DeclineRequest request) {
        try {
            requireGroupAccess(groupId, AccessLevel.EDIT);
            return toResponse(
                    intake.decline(groupId, handoffId, request != null ? request.reasonCode() : null, request != null ? request.note() : null,
                            actingUser()));
        } catch (IResourceStore.ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", e.getMessage())).build();
        } catch (WebApplicationException | ForbiddenException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorf(e, "Failed to decline handoff %s for group %s", sanitize(handoffId), sanitize(groupId));
            return Response.serverError().build();
        }
    }

    @Override
    public Response deleteBacklogTask(String groupId, String taskId) {
        try {
            requireGroupAccess(groupId, AccessLevel.EDIT);
            var result = intake.deleteBacklogTask(groupId, taskId, actingUser());
            return result.ok() ? Response.noContent().build() : toResponse(result);
        } catch (IResourceStore.ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", e.getMessage())).build();
        } catch (WebApplicationException | ForbiddenException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorf(e, "Failed to delete backlog task %s of group %s", sanitize(taskId), sanitize(groupId));
            return Response.serverError().build();
        }
    }

    private static Response toResponse(TeamHandoffIntake.Result result) {
        return switch (result.status()) {
            case OK -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("message", result.message());
                body.put("task", result.task());
                body.put("origin", result.origin());
                yield Response.ok(body).build();
            }
            case NOT_FOUND -> error(Response.Status.NOT_FOUND, result.message());
            case INVALID -> error(Response.Status.BAD_REQUEST, result.message());
            case BACKLOG_FULL, DUPLICATE, CONFLICT -> error(Response.Status.CONFLICT, result.message());
            case UNAVAILABLE -> error(Response.Status.SERVICE_UNAVAILABLE, result.message());
        };
    }

    private static Response error(Response.Status status, String message) {
        return Response.status(status).entity(Map.of("error", message)).build();
    }

    private String actingUser() {
        return identity != null && identity.getPrincipal() != null ? identity.getPrincipal().getName() : null;
    }

    /** 404 for an unknown group, then 403 for a caller without {@code required}. */
    private void requireGroupAccess(String groupId, AccessLevel required)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        if (groupStore.getCurrentResourceId(groupId) == null) {
            throw new IResourceStore.ResourceNotFoundException("Group not found.");
        }
        accessGuard.requireAccess(groupId, required, "group");
    }
}
