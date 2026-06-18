/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.triggermanagement;

import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/userconversationstore/userconversations")
@Tag(name = "Conversation Store")
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
