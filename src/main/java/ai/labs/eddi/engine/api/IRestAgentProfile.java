/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.api;

import ai.labs.eddi.configs.agents.model.AgentConfiguration.RatingConfig;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.ratings.RatingLimits;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * What a chat window needs to know about an agent before the first message: its
 * name, whether the conversation may be read by the agent's maintainers, and
 * whether and how to ask for a rating.
 *
 * <h3>Why its own endpoint</h3> A chat client used to read the agent's name
 * from the configuration store. Under workspaces somebody who may only
 * <em>talk</em> to an agent cannot read its configuration, so that read fails
 * and the window shows no name. This answers at the level talking needs —
 * {@code USE} — and discloses nothing about how the agent is built.
 */
@Path("/agents/{agentId}/profile")
@Tag(name = "Conversations", description = "Start, talk to, stream, undo/redo, and manage conversations")
@RolesAllowed({"eddi-admin", "eddi-editor", "eddi-user"})
public interface IRestAgentProfile {

    /**
     * @param agentId
     *            the agent
     * @param name
     *            its display name, or null
     * @param description
     *            its description, or null
     * @param reviewNotice
     *            what to tell the person chatting when the deployed version lets
     *            its maintainers read conversations; null when it does not
     * @param rating
     *            how the deployed version asks for ratings; null when ratings are
     *            off, and then a client shows no rating controls
     */
    record AgentProfile(String agentId, String name, String description, String reviewNotice, RatingProfile rating) {
    }

    /**
     * The part of the agent's {@code ratingConfig} a chat client needs to render
     * rating controls — nothing about how the agent is built.
     *
     * @param scale
     *            {@code THUMBS} or {@code FIVE_STAR}, for the conversation rating
     * @param conversationRating
     *            whether the conversation as a whole may be rated
     * @param promptText
     *            the question on the rating card, or null for the client's own
     * @param askOnEnd
     *            show the card when the conversation ends
     * @param askAfterTurns
     *            also offer it after this many agent replies; 0 never
     * @param messageRating
     *            whether single replies may be rated (always thumbs)
     * @param comment
     *            whether a free-text comment is accepted
     * @param commentMaxLength
     *            the longest comment accepted
     * @param commentAskBelowScore
     *            open the comment box only below this normalized score; 0 always
     * @param reasonTags
     *            the tags a person may pick
     * @param ratingWindowHours
     *            how long after the last activity a rating is accepted; 0 no limit
     */
    record RatingProfile(String scale, boolean conversationRating, String promptText, boolean askOnEnd, int askAfterTurns,
            boolean messageRating, boolean comment, int commentMaxLength, double commentAskBelowScore, List<String> reasonTags,
            int ratingWindowHours) {

        public RatingProfile {
            reasonTags = reasonTags == null ? List.of() : List.copyOf(reasonTags);
        }

        /** The client view of an enabled rating setting. */
        public static RatingProfile of(RatingConfig config) {
            var conversation = config.getConversationRating();
            var comment = config.getComment();
            return new RatingProfile(config.getScale().name(),
                    config.isConversationRatingEnabled(),
                    conversation == null ? null : conversation.getPromptText(),
                    conversation == null || conversation.isAskOnEnd(),
                    conversation == null ? 0 : Math.max(0, conversation.getAskAfterTurns()),
                    config.isMessageRatingEnabled(),
                    config.isCommentEnabled(),
                    RatingLimits.commentMaxLength(config),
                    comment == null ? 0 : comment.getAskBelowScore(),
                    config.getReasonTags(),
                    Math.max(0, config.getRatingWindowHours()));
        }
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "What a chat window shows about an agent",
               description = "Name, description, and the conversation-review notice for the deployed version. Requires USE.")
    @APIResponse(responseCode = "200", description = "The profile.")
    @APIResponse(responseCode = "403", description = "The caller may not use this agent.")
    AgentProfile readProfile(@PathParam("agentId") String agentId,
                             @QueryParam("environment")
                             @DefaultValue("production") Deployment.Environment environment);
}
