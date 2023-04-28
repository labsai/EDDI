package ai.labs.eddi.configs.http;

import ai.labs.eddi.configs.IRestVersionInfo;
import ai.labs.eddi.configs.http.model.HttpCallsConfiguration;
import ai.labs.eddi.models.DocumentDescriptor;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

/**
 * @author ginccc
 */
// @Api(value = "Configurations -> (2) Conversation LifeCycle Tasks -> (3) HttpCalls", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/httpcallsstore/httpcalls")
@Tag(name = "03. Httpcalls", description = "lifecycle extension for package")
public interface IRestHttpCallsStore extends IRestVersionInfo {
    String resourceBaseType = "eddi://ai.labs.httpcalls";
    String resourceURI = resourceBaseType + "/httpcallsstore/httpcalls/";

    @GET
    @Path("/jsonSchema")
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(responseCode = "200", description = "JSON Schema (for validation).")
    @Operation(description = "Read JSON Schema for regular httpCalls definition.")
    Response readJsonSchema();

    @GET
    @Path("descriptors")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read list of httpCalls descriptors.")
    List<DocumentDescriptor> readHttpCallsDescriptors(@QueryParam("filter") @DefaultValue("") String filter,
                                                      @QueryParam("index") @DefaultValue("0") Integer index,
                                                      @QueryParam("limit") @DefaultValue("20") Integer limit);

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read httpCalls.")
    HttpCallsConfiguration readHttpCalls(@PathParam("id") String id,
                                         @Parameter(name = "version", required = true, example = "1")
                                         @QueryParam("version") Integer version);

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(description = "Update httpCalls.")
    Response updateHttpCalls(@PathParam("id") String id,
                             @Parameter(name = "version", required = true, example = "1")
                             @QueryParam("version") Integer version, HttpCallsConfiguration httpCallsConfiguration);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(description = "Create httpCalls.")
    Response createHttpCalls(HttpCallsConfiguration httpCallsConfiguration);

    @POST
    @Path("/{id}")
    @Operation(description = "Duplicate this httpCalls.")
    Response duplicateHttpCalls(@PathParam("id") String id, @QueryParam("version") Integer version);

    @DELETE
    @Path("/{id}")
    @Operation(description = "Delete httpCalls.")
    Response deleteHttpCalls(@PathParam("id") String id,
                             @Parameter(name = "version", required = true, example = "1")
                             @QueryParam("version") Integer version);
}
