/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.triggermanagement;

import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import jakarta.annotation.security.RolesAllowed;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Operator surface for the (intent, userId) &rarr; conversationId mapping used
 * by triggers and channel integrations.
 * <p>
 * Admin-only: a {@code conversationId} is the capability needed to read or
 * drive someone else's conversation, so an endpoint that hands one out for an
 * arbitrary {@code userId} is a discovery oracle, not a lookup. Runtime callers
 * (Slack, GDPR, MCP) go through {@code IUserConversationStore} directly and are
 * unaffected by this restriction.
 */
@Path("/userconversationstore/userconversations")
@Tag(name = "Conversations / Store", description = "Query, delete, and manage conversation history")
@RolesAllowed("eddi-admin")
public interface IRestUserConversationStore {
    String resourceURI = "eddi://ai.labs.userconversation/userconversationstore/userconversations/";

    @GET
    @Path("/{intent}/{userId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Read user conversation", description = "Read a user conversation for intent and user id.")
    UserConversation readUserConversation(@PathParam("intent") String intent, @PathParam("userId") String userId);

    @POST
    @Path("/{intent}/{userId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Create user conversation", description = "Create a new user conversation for intent and user id.")
    Response createUserConversation(@PathParam("intent") String intent, @PathParam("userId") String userId, UserConversation userConversation);

    @DELETE
    @Path("/{intent}/{userId}")
    @Operation(summary = "Delete user conversation", description = "Delete a user conversation for intent and user id.")
    Response deleteUserConversation(@PathParam("intent") String intent, @PathParam("userId") String userId);
}
