/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.rest;

import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.ConversationStatus;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

import static ai.labs.eddi.datastore.IResourceStore.*;

/**
 * Conversation history store.
 * <p>
 * Every operation addressed at a single conversation
 * ({@link #readRawConversationLog}, {@link #readSimpleConversationLog},
 * {@link #deleteConversationLog}) is gated by
 * {@code ConversationAccessGuard.requireConversationOwner} in the
 * implementation, and the listing filters each row through the same guard, so a
 * caller can neither read nor delete a conversation they do not own. The
 * deployment-wide sweep has no owner to scope to and is therefore role-gated to
 * {@code eddi-admin} instead.
 * <p>
 * {@link #getActiveConversations} and {@link #endActiveConversations} are
 * agent-scoped operational endpoints, not per-conversation ones, so they are
 * gated like undeploy (which ends every active conversation of an agent) rather
 * than by conversation owner: {@code eddi-admin} or {@code eddi-editor}, plus
 * EDIT access on the agent. The EDIT check is enforced only with workspaces on
 * ({@code eddi.workspaces.enabled=true}, off by default); without them any
 * editor may list and end any agent's open conversations — the same reach
 * undeploy-with-end already gives an editor. They used to carry no role at all,
 * which let any authenticated principal list every user's open conversation ids
 * and end any of them.
 *
 * @author ginccc
 */
@Path("/conversationstore/conversations")
@Tag(name = "Conversations / Store", description = "Query, delete, and manage conversation history")
public interface IRestConversationStore {
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    List<ConversationDescriptor> readConversationDescriptors(@QueryParam("index")
    @DefaultValue("0") Integer index,
                                                             @QueryParam("limit")
                                                             @DefaultValue("20") Integer limit, @QueryParam("filter") String filter,
                                                             @QueryParam("conversationId") String conversationId,
                                                             @QueryParam("agentId") String agentId,
                                                             @QueryParam("agentVersion") Integer agentVersion,
                                                             @QueryParam("conversationState") ConversationState conversationState,
                                                             @QueryParam("viewState") ConversationDescriptor.ViewState viewState);

    @GET
    @Path("/simple/{conversationId}")
    @Produces(MediaType.APPLICATION_JSON)
    SimpleConversationMemorySnapshot readSimpleConversationLog(@PathParam("conversationId") String conversationId,
                                                               @QueryParam("returnDetailed")
                                                               @DefaultValue("false") Boolean returnDetailed,
                                                               @QueryParam("returnCurrentStepOnly")
                                                               @DefaultValue("true") Boolean returnCurrentStepOnly,
                                                               @QueryParam("returningFields") List<String> returningFields)
            throws ResourceStoreException, ResourceNotFoundException;

    @GET
    @Path("/{conversationId}")
    @Produces(MediaType.APPLICATION_JSON)
    ConversationMemorySnapshot readRawConversationLog(@PathParam("conversationId") String conversationId)
            throws ResourceStoreException, ResourceNotFoundException;

    @DELETE
    @Path("/{conversationId}")
    void deleteConversationLog(@PathParam("conversationId") String conversationId,
                               @QueryParam("deletePermanently")
                               @DefaultValue("false") Boolean deletePermanently)
            throws ResourceStoreException, ResourceNotFoundException;

    /**
     * Deployment-wide retention sweep: permanently deletes EVERY ended conversation
     * older than {@code deleteOlderThanDays}, across all owners. Admin-only, and
     * the age must be at least one day — {@code 0} would wipe the whole
     * deployment's ended conversations in a single call. Before this was gated it
     * was reachable by any caller at all, which is what made the {@code 0} case so
     * dangerous.
     */
    @DELETE
    @Path("/")
    @RolesAllowed("eddi-admin")
    Integer permanentlyDeleteEndedConversationLogs(@QueryParam("deleteOlderThanDays") Integer deleteOlderThanDays)
            throws ResourceStoreException, ResourceNotFoundException, ResourceModifiedException;

    /**
     * The open (not ENDED) conversations of an agent, across all owners. Requires
     * EDIT access on the agent when workspaces are enforced.
     */
    @GET
    @Path("/active/{agentId}")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"eddi-admin", "eddi-editor"})
    List<ConversationStatus> getActiveConversations(@PathParam("agentId") String agentId,
                                                    @Parameter(name = "agentVersion", required = false, example = "1",
                                                               description = "Restrict to one agent version; omit for every version")
                                                    @QueryParam("agentVersion") Integer agentVersion)
            throws ResourceStoreException, ResourceNotFoundException;

    /**
     * Ends the listed conversations. Only each entry's {@code conversationId} is
     * used — the agent and the current state are read from the server — and each
     * conversation requires EDIT access on its agent (under workspace enforcement),
     * checked for the whole list before anything is ended. Unknown and already
     * ended conversations are skipped. Ending continues past a conversation that
     * fails; the body lists the {@code ended}, {@code skipped} and {@code failed}
     * ids, and the status is 500 if any failed, else 200.
     */
    @POST
    @Path("end")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"eddi-admin", "eddi-editor"})
    Response endActiveConversations(List<ConversationStatus> conversationStatuses);
}
