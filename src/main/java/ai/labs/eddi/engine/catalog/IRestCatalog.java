/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.catalog;

import ai.labs.eddi.engine.api.IRestAgentProfile.RatingProfile;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * What an end user may pick from: the agents and groups the caller may
 * <em>use</em> that their owners chose to offer.
 *
 * <h3>Why its own endpoint</h3> The authoring stores ({@code agentstore},
 * {@code groupstore}, {@code deploymentstore}) answer only {@code eddi-admin}
 * and {@code eddi-editor}, so an end-user app reading them fails for an
 * {@code eddi-user}. This answers at the level talking needs — {@code USE},
 * decided by {@code ResourceAccessGuard} exactly as starting a conversation or
 * running a group is — and discloses nothing about how anything is built.
 *
 * <h3>Listing is opt-in</h3> An entry appears only when it is <b>listed</b>:
 * its configuration says {@code "listed": true}, or, with workspaces enforced,
 * its visibility is {@code published} or {@code internal}. An upgrade therefore
 * lists nothing. The Platform Operator is never listed by visibility alone —
 * only an explicit {@code listed: true} on its configuration shows it.
 */
@Path("/catalog")
@Tag(name = "Catalog", description = "The agents and groups an end user may pick from")
@RolesAllowed({"eddi-admin", "eddi-editor", "eddi-user"})
public interface IRestCatalog {

    /**
     * One agent of the catalog.
     *
     * @param agentId
     *            the agent's id — what {@code POST /agents/{agentId}/start} takes
     * @param name
     *            its display name, or null
     * @param description
     *            its description, or null
     * @param reviewNotice
     *            as {@code GET /agents/{agentId}/profile}: what to tell the person
     *            chatting when the deployed version lets its maintainers read
     *            conversations; null when it does not
     * @param rating
     *            as the profile: the rating controls of the deployed version, or
     *            null when ratings are off
     * @param callerLevel
     *            what the caller holds on the agent — {@code USE}, {@code VIEW},
     *            {@code EDIT} or {@code OWN}; always at least {@code USE}
     */
    record CatalogAgent(String agentId, String name, String description, String reviewNotice, RatingProfile rating,
            String callerLevel) {
    }

    /**
     * One member of a catalog group — enough to show who takes part and to open a
     * thread with a member, nothing about how the member is built.
     */
    record CatalogGroupMember(String agentId, String displayName, String role, String memberType, Integer speakingOrder) {
    }

    /**
     * One group of the catalog.
     *
     * @param groupId
     *            the group's id — what {@code POST /groups/{groupId}/conversations}
     *            takes
     * @param version
     *            the group's current version
     * @param name
     *            its display name, or null
     * @param description
     *            its description, or null
     * @param style
     *            the discussion style, or null
     * @param members
     *            who takes part
     * @param callerLevel
     *            what the caller holds on the group; always at least {@code USE}
     */
    record CatalogGroup(String groupId, Integer version, String name, String description, String style,
            List<CatalogGroupMember> members, String callerLevel) {

        public CatalogGroup {
            members = members == null ? List.of() : List.copyOf(members);
        }
    }

    @GET
    @Path("/agents")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "The agents the caller may use and that are listed",
               description = "Deployed (production, READY) agents that the caller may USE and that are listed: "
                       + "'listed: true' on the agent, or with workspaces enforced 'published' or 'internal' visibility. "
                       + "With authorization enabled an anonymous caller is refused; with it disabled no role "
                       + "or visibility is checked and every deployed agent with 'listed: true' is returned.")
    @APIResponse(responseCode = "200", description = "The catalog; empty when nothing is listed.")
    List<CatalogAgent> listAgents();

    @GET
    @Path("/groups")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "The groups the caller may use and that are listed",
               description = "Groups the caller may USE (the same check as running one) that are listed: "
                       + "'listed: true' on the group, or with workspaces enforced 'published' or 'internal' visibility.")
    @APIResponse(responseCode = "200", description = "The catalog; empty when nothing is listed.")
    List<CatalogGroup> listGroups();
}
