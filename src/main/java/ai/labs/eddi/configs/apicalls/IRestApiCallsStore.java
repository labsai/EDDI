/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.apicalls;

import ai.labs.eddi.configs.IRestVersionInfo;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.apicalls.model.ApiEndpointDiscoveryRequest;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import jakarta.annotation.security.RolesAllowed;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.util.List;

/**
 * @author ginccc
 */
@Path("/apicallstore/apicalls")
@Tag(name = "Configuration / API Calls", description = "HTTP API call definitions for tool use")
@RolesAllowed({"eddi-admin", "eddi-editor"})
public interface IRestApiCallsStore extends IRestVersionInfo {
    String resourceBaseType = "eddi://ai.labs.apicalls";
    String resourceURI = resourceBaseType + "/apicallstore/apicalls/";

    @GET
    @Path("/jsonSchema")
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(responseCode = "200", description = "JSON Schema (for validation).")
    @Operation(operationId = "readApiCallsJsonSchema", description = "Read JSON Schema for regular httpCalls definition.")
    Response readJsonSchema();

    @GET
    @Path("descriptors")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read list of httpCalls descriptors.")
    List<DocumentDescriptor> readApiCallsDescriptors(@QueryParam("filter")
    @DefaultValue("") String filter,
                                                     @QueryParam("index")
                                                     @DefaultValue("0") Integer index,
                                                     @QueryParam("limit")
                                                     @DefaultValue("20") Integer limit);

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read httpCalls.")
    ApiCallsConfiguration readApiCalls(@PathParam("id") String id,
                                       @Parameter(name = "version", required = true, example = "1")
                                       @QueryParam("version") Integer version);

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(description = "Update httpCalls.")
    Response updateApiCalls(@PathParam("id") String id,
                            @Parameter(name = "version", required = true, example = "1")
                            @QueryParam("version") Integer version,
                            ApiCallsConfiguration httpCallsConfiguration);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(description = "Create httpCalls.")
    Response createApiCalls(ApiCallsConfiguration httpCallsConfiguration);

    @POST
    @Path("/{id}")
    @Operation(description = "Duplicate this httpCalls.")
    Response duplicateApiCalls(@PathParam("id") String id, @QueryParam("version") Integer version);

    @DELETE
    @Path("/{id}")
    @Operation(description = "Delete httpCalls.")
    Response deleteApiCalls(@PathParam("id") String id,
                            @Parameter(name = "version", required = true, example = "1")
                            @QueryParam("version") Integer version,
                            @QueryParam("permanent")
                            @DefaultValue("false") Boolean permanent);

    /**
     * Parse an OpenAPI document and return importable ApiCall configurations.
     * <p>
     * {@code POST}, and the auth field is reference-only — see
     * {@link ApiEndpointDiscoveryRequest}. The superseded {@code GET} form took a
     * live {@code Authorization} value as {@code ?apiAuth=} and echoed it back
     * inside every generated call.
     */
    @POST
    @Path("/discover-endpoints")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Discover API endpoints", description = "Parse an OpenAPI 3.x spec (URL or inline JSON/YAML) and return available endpoints "
            + "grouped by tag. Returns fully generated ApiCall objects ready for import. Used by the Manager UI for selective API call import. "
            + "authHeaderRef must be a ${vault:…}, ${vars:…} or ${caller:…} reference — a literal credential is rejected.")
    @APIResponse(responseCode = "200", description = "Discovered endpoints grouped by tag, with generated ApiCall objects.")
    Response discoverEndpoints(ApiEndpointDiscoveryRequest request);

    /**
     * Credential-free discovery of a public OpenAPI document.
     * <p>
     * Rejects a request that still carries a credential query parameter rather than
     * ignoring it, so a client that has not migrated learns it is putting a secret
     * in a URL instead of silently continuing to do so.
     */
    @GET
    @Path("/discover-endpoints")
    @Produces(MediaType.APPLICATION_JSON)
    @Deprecated(since = "6.3.0", forRemoval = true)
    @Operation(deprecated = true, summary = "Discover API endpoints (no credential)", description = "Deprecated — use POST /discover-endpoints. "
            + "A credential query parameter is rejected.")
    Response discoverEndpointsUnauthenticated(@QueryParam("specUrl") String specUrl, @QueryParam("apiBaseUrl")
    @DefaultValue("") String apiBaseUrl, @Context UriInfo uriInfo);
}
