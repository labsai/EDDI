/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.properties;

import ai.labs.eddi.configs.properties.model.Properties;
import jakarta.annotation.security.RolesAllowed;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Legacy flat-property view over the same {@link IUserMemoryStore} that
 * {@link IRestUserMemoryStore} serves. It is deliberately guarded with the
 * <em>identical</em> role set as that store: two doors onto one collection must
 * not have different locks — writes made here are loaded into the owner's
 * conversation properties on their next turn.
 * <p>
 * The role check is coarse (it only says "some user"); the per-userId ownership
 * check in {@code RestPropertiesStore} is what keeps one user out of another
 * user's properties.
 */
@Path("/propertiesstore/properties")
@Tag(name = "Configuration / Properties", description = "User property storage and setter configuration")
@RolesAllowed({"eddi-admin", "eddi-user"})
public interface IRestPropertiesStore {
    String resourceURI = "eddi://ai.labs.properties/propertiesstore/properties/";

    @GET
    @Path("/{userId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read properties.")
    Properties readProperties(@PathParam("userId") String userId);

    @POST
    @Path("/{userId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Merge properties.")
    Response mergeProperties(@PathParam("userId") String userId, Properties properties);

    @DELETE
    @Path("/{userId}")
    @Operation(description = "Delete properties.")
    Response deleteProperties(@PathParam("userId") String userId);
}
