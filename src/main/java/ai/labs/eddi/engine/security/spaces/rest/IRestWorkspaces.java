/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces.rest;

import ai.labs.eddi.engine.security.spaces.directory.UserDirectory;
import ai.labs.eddi.engine.security.spaces.notifications.WorkspaceNotification;
import ai.labs.eddi.engine.security.spaces.rest.model.WorkspaceInfo;
import ai.labs.eddi.engine.security.spaces.rest.model.WorkspaceSettingsView;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.Map;

/**
 * What workspaces mean for the calling user.
 *
 * <h3>Why this endpoint exists</h3> A client cannot tell a deployment with
 * workspaces switched off from one where every resource predates ownership —
 * both return descriptors with no owner, no space and no visibility. Guessing
 * wrong means offering a Share action that silently does nothing. So the server
 * says which it is.
 * <p>
 * It also serves the caller's spaces rather than leaving a client to derive
 * them from the token. The derivation is not hard, but getting it subtly wrong
 * fails silently: a space id encoded differently selects nothing, which looks
 * like an empty workspace rather than a bug.
 *
 * @author ginccc
 */
@Path("/workspaces")
@Tag(name = "Operations / Sharing", description = "Share configuration resources with people and teams")
@RolesAllowed({"eddi-admin", "eddi-editor"})
public interface IRestWorkspaces {

    String resourceURI = "eddi://ai.labs.workspaces/workspaces/";

    /**
     * Whether workspaces are enforced, and which spaces this caller can reach.
     * <p>
     * Always answers for the caller who asked — never takes a principal as a
     * parameter, so it cannot be used to enumerate somebody else's group
     * membership.
     */
    @GET
    @RolesAllowed({"eddi-admin", "eddi-editor", "eddi-user", "eddi-viewer"})
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Read workspace settings for the calling user",
               description = "Whether workspace enforcement is active, the caller's principal and default space, "
                       + "and every space the caller can reach.")
    @APIResponse(responseCode = "200", description = "The caller's workspace context.")
    WorkspaceInfo readWorkspaceInfo();

    /**
     * Suggestions for a share box: teams, then people who have signed in, whose
     * name, username or email starts with {@code q}.
     * <p>
     * People appear here only after signing in to EDDI once — the directory is
     * built from sign-ins, not from the identity provider. The caller is never
     * listed, and at most {@value UserDirectory#MAX_RESULTS} entries come back.
     */
    @GET
    @Path("/directory")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Search people and teams to share with",
               description = "Teams and signed-in people whose name, username or email starts with the query.")
    @APIResponse(responseCode = "200", description = "Matching people and teams, teams first.")
    List<UserDirectory.Match> searchDirectory(@QueryParam("q")
    @DefaultValue("") String query,
                                              @QueryParam("limit")
                                              @DefaultValue("10") Integer limit);

    /**
     * The workspace settings in effect, with the source of each value.
     * Administrators only.
     */
    @GET
    @Path("/settings")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("eddi-admin")
    @Operation(summary = "Read the workspace settings",
               description = "Default space and legacy visibility, each with its source (PINNED, STORED or DEFAULT). Enforcement and the groups "
                       + "claim are shown read-only: they are startup properties.")
    @APIResponse(responseCode = "200", description = "The effective settings.")
    WorkspaceSettingsView readSettings();

    /**
     * Replaces the stored workspace settings. Takes effect on this instance at once
     * and on every other within five seconds. A field pinned by its property cannot
     * be changed here.
     */
    @PUT
    @Path("/settings")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("eddi-admin")
    @Operation(summary = "Change the workspace settings",
               description = "A null field is unset and falls back to its default. Enforcement and the groups claim are startup-only and are "
                       + "not accepted here.")
    @APIResponse(responseCode = "200", description = "Stored; the effective settings are returned.")
    @APIResponse(responseCode = "400", description = "A value is malformed; the message names the field.")
    @APIResponse(responseCode = "409", description = "A field is pinned by a property and the request would change it.")
    WorkspaceSettingsView updateSettings(WorkspaceSettingsView.Update update);

    /**
     * The caller's own notifications — shares with them, and access requests for
     * what they own — newest first. There is no way to read anybody else's.
     */
    @GET
    @RolesAllowed({"eddi-admin", "eddi-editor", "eddi-user", "eddi-viewer"})
    @Path("/notifications")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Read my notifications", description = "Shares with the caller and access requests for their resources.")
    @APIResponse(responseCode = "200", description = "Newest first.")
    List<WorkspaceNotification> readNotifications(@QueryParam("unreadOnly")
    @DefaultValue("false") Boolean unreadOnly,
                                                  @QueryParam("limit")
                                                  @DefaultValue("50") Integer limit);

    /** How many of the caller's notifications are unread — for a badge. */
    @GET
    @RolesAllowed({"eddi-admin", "eddi-editor", "eddi-user", "eddi-viewer"})
    @Path("/notifications/count")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Count my unread notifications")
    @APIResponse(responseCode = "200", description = "{unread: n}")
    Map<String, Long> countUnreadNotifications();

    /**
     * Marks the caller's notifications read.
     *
     * @param request
     *            {@code {"ids": [...]}}, or {@code {}} for all of them
     */
    @POST
    @RolesAllowed({"eddi-admin", "eddi-editor", "eddi-user", "eddi-viewer"})
    @Path("/notifications/read")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Mark my notifications read")
    @APIResponse(responseCode = "200", description = "{marked: n}")
    Map<String, Long> markNotificationsRead(MarkRead request);

    /**
     * Which notifications to mark read.
     *
     * @param ids
     *            the notifications, or null for all of the caller's
     */
    record MarkRead(List<String> ids) {
    }
}
