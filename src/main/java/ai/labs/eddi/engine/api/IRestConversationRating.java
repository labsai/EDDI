/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.api;

import ai.labs.eddi.engine.api.IRestAgentProfile.RatingProfile;
import ai.labs.eddi.engine.ratings.model.ConversationRating;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * Rating a conversation, or one agent reply in it, by the person who had it.
 * <p>
 * Beside {@code endConversation}, {@code undo} and {@code rerun} under
 * {@code /agents/{conversationId}/…}, but its own resource rather than more
 * methods on {@link IRestAgentEngine}. A rating is written to its own store,
 * never to conversation memory, so it does not go through the conversation
 * coordinator and may be given while a turn runs.
 * <p>
 * Accepted only when the agent version the conversation runs on has
 * {@code ratingConfig} enabled, within its rating window, from the
 * conversation's owner. Errors carry a stable {@code error} code:
 * {@code RATING_DISABLED}, {@code RATING_WINDOW_CLOSED}, {@code INVALID_STEP},
 * {@code INVALID_SCORE}, {@code INVALID_TAG}, {@code COMMENT_NOT_ALLOWED},
 * {@code COMMENT_TOO_LONG}, {@code NOT_CONVERSATION_OWNER},
 * {@code CONVERSATION_NOT_FOUND}.
 */
@Path("/agents")
@Tag(name = "Conversations", description = "Start, talk to, stream, undo/redo, and manage conversations")
@RolesAllowed({"eddi-admin", "eddi-editor", "eddi-user"})
public interface IRestConversationRating {

    /**
     * A rating as the client sends it.
     *
     * @param score
     *            0/1 for thumbs, 1–5 for stars; a reply rating is always thumbs
     * @param tags
     *            reason tags from the agent's list, optional
     * @param comment
     *            free text, optional, only when the agent accepts comments
     * @param channel
     *            where the rating was given — {@code chat-ui}, {@code manager},
     *            {@code api}, {@code mcp}, {@code slack}, {@code teams}; anything
     *            else is stored as {@code other}, absent as {@code api}
     */
    record RatingRequest(Integer score, List<String> tags, String comment, String channel) {
    }

    /**
     * Every rating of a conversation.
     *
     * @param conversation
     *            the rating of the conversation as a whole, or null
     * @param steps
     *            the ratings of single replies, by step
     * @param controls
     *            the rating controls of the agent version <em>this
     *            conversation</em> runs on — the version a rating is checked
     *            against — or null when that version takes no ratings. A client
     *            resuming a conversation shows these rather than the profile's,
     *            which follow the version a <em>new</em> conversation would get.
     */
    record ConversationRatings(ConversationRating conversation, List<ConversationRating> steps, RatingProfile controls) {
    }

    @PUT
    @Path("/{conversationId}/rating")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Rate a conversation",
               description = "Stores (or replaces) the owner's rating of the conversation as a whole.")
    @APIResponse(responseCode = "200", description = "The stored rating.")
    @APIResponse(responseCode = "400", description = "Invalid score, tag or comment.")
    @APIResponse(responseCode = "403", description = "Not the conversation's owner, or processing is restricted.")
    @APIResponse(responseCode = "404", description = "No such conversation.")
    @APIResponse(responseCode = "409", description = "Ratings are off for this agent version, or the rating window has closed.")
    ConversationRating rateConversation(@PathParam("conversationId") String conversationId, RatingRequest rating);

    @GET
    @Path("/{conversationId}/rating")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Read a conversation's ratings",
               description = "The conversation rating and the reply ratings, so a client can restore its state after a reload.")
    @APIResponse(responseCode = "200", description = "The ratings; empty when there are none.")
    ConversationRatings readRatings(@PathParam("conversationId") String conversationId);

    @DELETE
    @Path("/{conversationId}/rating")
    @Operation(summary = "Withdraw a conversation rating")
    @APIResponse(responseCode = "204", description = "Withdrawn, or there was none.")
    Response withdrawConversationRating(@PathParam("conversationId") String conversationId);

    @PUT
    @Path("/{conversationId}/steps/{stepIndex}/rating")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Rate one agent reply",
               description = "Thumbs (score 0 or 1) on the agent output of one conversation step.")
    @APIResponse(responseCode = "200", description = "The stored rating.")
    @APIResponse(responseCode = "400", description = "Invalid step, score, tag or comment.")
    @APIResponse(responseCode = "409", description = "Reply ratings are off for this agent version, or the rating window has closed.")
    ConversationRating rateStep(@PathParam("conversationId") String conversationId, @PathParam("stepIndex") int stepIndex,
                                RatingRequest rating);

    @DELETE
    @Path("/{conversationId}/steps/{stepIndex}/rating")
    @Operation(summary = "Withdraw a reply rating")
    @APIResponse(responseCode = "204", description = "Withdrawn, or there was none.")
    Response withdrawStepRating(@PathParam("conversationId") String conversationId, @PathParam("stepIndex") int stepIndex);
}
