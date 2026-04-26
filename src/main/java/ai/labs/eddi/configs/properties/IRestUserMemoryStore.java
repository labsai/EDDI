/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.properties;

import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import jakarta.annotation.security.RolesAllowed;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

/**
 * REST API for managing persistent user memories. Provides CRUD operations for
 * structured memory entries and GDPR-compliant user data deletion.
 *
 * @author ginccc
 * @since 6.0.0
 */
@Path("/usermemorystore/memories")
@Tag(name = "User Memory", description = "Persistent user memory management")
@RolesAllowed({"eddi-admin", "eddi-user"})
public interface IRestUserMemoryStore {

    @GET
    @Path("/{userId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get all memories for a user", description = "Returns all structured memory entries for the given user.")
    List<UserMemoryEntry> getAllMemories(@PathParam("userId") String userId);

    @GET
    @Path("/{userId}/visible")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get visible memories for an agent", description = "Returns memories visible to a specific agent, "
            + "considering self/group/global visibility.")
    List<UserMemoryEntry> getVisibleMemories(@PathParam("userId") String userId, @QueryParam("agentId") String agentId,
                                             @QueryParam("groupId") List<String> groupIds, @QueryParam("order")
                                             @DefaultValue("most_recent") String recallOrder,
                                             @QueryParam("limit")
                                             @DefaultValue("50") int maxEntries);

    @GET
    @Path("/{userId}/search")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Search user memories", description = "Filter memories by key or value content.")
    List<UserMemoryEntry> searchMemories(@PathParam("userId") String userId, @QueryParam("q") String query);

    @GET
    @Path("/{userId}/category/{category}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get memories by category", description = "Returns memories filtered by category (preference, fact, context).")
    List<UserMemoryEntry> getMemoriesByCategory(@PathParam("userId") String userId, @PathParam("category") String category);

    @GET
    @Path("/{userId}/key/{key}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Get a specific memory by key", description = "Returns a single memory entry by its key name.")
    Response getMemoryByKey(@PathParam("userId") String userId, @PathParam("key") String key);

    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Upsert a memory entry", description = "Insert or update a memory entry. Upsert key depends on visibility.")
    Response upsertMemory(UserMemoryEntry entry);

    @DELETE
    @Path("/entry/{entryId}")
    @Operation(summary = "Delete a specific memory entry")
    Response deleteMemory(@PathParam("entryId") String entryId);

    @DELETE
    @Path("/{userId}")
    @Operation(summary = "Delete all memories for a user (GDPR)", description = "Permanently removes all memory entries for a user. "
            + "Intended for GDPR right-to-erasure requests.")
    Response deleteAllForUser(@PathParam("userId") String userId);

    @GET
    @Path("/{userId}/count")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Count memory entries for a user")
    Response countMemories(@PathParam("userId") String userId);
}
