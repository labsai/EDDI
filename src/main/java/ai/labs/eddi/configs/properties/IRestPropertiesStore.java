/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.properties;

import ai.labs.eddi.configs.properties.model.Properties;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/propertiesstore/properties")
@Tag(name = "Properties")
public interface IRestPropertiesStore {
    String resourceURI = "eddi://ai.labs.properties/propertiesstore/properties/";

    @GET
    @Path("/{userId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read properties.")
    @APIResponse(responseCode = "200", description = "Properties")
    @APIResponse(responseCode = "204", description = "No properties found")
    Properties readProperties(@PathParam("userId") String userId);

    @POST
    @Path("/{userId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Merge properties.")
    @APIResponse(responseCode = "200", description = "Properties merged")
    @APIResponse(responseCode = "400", description = "Invalid request body")
    @APIResponse(responseCode = "500", description = "Internal server error")
    Response mergeProperties(@PathParam("userId") String userId, Properties properties);

    @DELETE
    @Path("/{userId}")
    @Operation(description = "Delete properties.")
    @APIResponse(responseCode = "200", description = "Properties deleted")
    Response deleteProperties(@PathParam("userId") String userId);
}
