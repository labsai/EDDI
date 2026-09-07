/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.connections;

import ai.labs.eddi.configs.IRestVersionInfo;
import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * CRUD for connection configurations.
 * <p>
 * {@code eddi-admin} only, deliberately narrower than the {@code {eddi-admin,
 * eddi-editor}} pair its sibling config stores use. A connection is an egress
 * channel plus a credential — the same class of capability as a vault write,
 * which is already admin-only and is already excluded from operator write
 * scope. An editor who can create a connection can point an existing credential
 * at a host of their choosing.
 * <p>
 * Note what is <em>not</em> here: there is no endpoint that returns a resolved
 * credential, and no endpoint that returns a grant. A connection document
 * carries only references, so reading one is safe; a grant carries tokens, so
 * it has no read surface at all.
 */
@Path("/connectionstore/connections")
@Tag(name = "Configuration / Connections", description = "How to authenticate to an external system")
@RolesAllowed("eddi-admin")
public interface IRestConnectionStore extends IRestVersionInfo {

    String resourceBaseType = "eddi://ai.labs.connection";
    String resourceURI = resourceBaseType + "/connectionstore/connections/";

    @GET
    @Path("/jsonSchema")
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(responseCode = "200", description = "JSON Schema for connection validation.")
    @Operation(operationId = "readConnectionJsonSchema", summary = "Get JSON Schema",
               description = "Read JSON Schema for a connection configuration.")
    Response readJsonSchema();

    @GET
    @Path("descriptors")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "List connection descriptors", description = "Read the list of connection configuration descriptors.")
    List<DocumentDescriptor> readConnectionDescriptors(@QueryParam("filter")
    @DefaultValue("") String filter,
                                                       @QueryParam("index")
                                                       @DefaultValue("0") Integer index,
                                                       @QueryParam("limit")
                                                       @DefaultValue("20") Integer limit);

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Read connection", description = "Read a connection configuration. Secret-bearing fields are references, never values.")
    ConnectionConfiguration readConnection(@PathParam("id") String id,
                                           @Parameter(name = "version", required = true, example = "1")
                                           @QueryParam("version") Integer version);

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Update connection", description = "Update a connection configuration.")
    Response updateConnection(@PathParam("id") String id,
                              @Parameter(name = "version", required = true, example = "1")
                              @QueryParam("version") Integer version,
                              ConnectionConfiguration connectionConfiguration);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Create connection", description = "Create a connection configuration.")
    Response createConnection(ConnectionConfiguration connectionConfiguration);

    @POST
    @Path("/{id}")
    @Operation(summary = "Duplicate connection", description = "Duplicate an existing connection configuration.")
    Response duplicateConnection(@PathParam("id") String id, @QueryParam("version") Integer version);

    @DELETE
    @Path("/{id}")
    @Operation(summary = "Delete connection", description = "Delete a connection configuration. Any grants it produced are deleted with it.")
    Response deleteConnection(@PathParam("id") String id,
                              @Parameter(name = "version", required = true, example = "1")
                              @QueryParam("version") Integer version,
                              @QueryParam("permanent")
                              @DefaultValue("false") Boolean permanent);
}
