package ai.labs.eddi.configs.packages;

import ai.labs.eddi.configs.IRestVersionInfo;
import ai.labs.eddi.configs.packages.model.PackageConfiguration;
import ai.labs.eddi.models.DocumentDescriptor;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;

/**
 * @author ginccc
 */
// @Api(value = "Configurations -> (3) Packages", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/packagestore/packages")
@Tag(name = "06. Packages", description = "packages for bots")
public interface IRestPackageStore extends IRestVersionInfo {
    String resourceURI = "eddi://ai.labs.package/packagestore/packages/";

    @GET
    @Path("/jsonSchema")
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(responseCode = "200", description = "JSON Schema (for validation).")
    @Operation(description = "Read JSON Schema for package definition.")
    Response readJsonSchema();

    @GET
    @Path("descriptors")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read list of package descriptors.")
    List<DocumentDescriptor> readPackageDescriptors(@QueryParam("filter") @DefaultValue("") String filter,
                                                    @QueryParam("index") @DefaultValue("0") Integer index,
                                                    @QueryParam("limit") @DefaultValue("20") Integer limit);

    @POST
    @Path("descriptors")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read list of package descriptors including a given resourceUri.")
    List<DocumentDescriptor> readPackageDescriptors(
            @QueryParam("filter") @DefaultValue("") String filter,
            @QueryParam("index") @DefaultValue("0") Integer index,
            @QueryParam("limit") @DefaultValue("20") Integer limit,
            @Parameter(name = "body", description = "eddi://ai.labs.TYPE/PATH/ID?version=VERSION")
            @DefaultValue("") String containingResourceUri,
            @QueryParam("includePreviousVersions") @DefaultValue("false") Boolean includePreviousVersions);


    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read package.")
    PackageConfiguration readPackage(@PathParam("id") String id,
                                     @Parameter(name = "version", required = true, example = "1")
                                     @QueryParam("version") Integer version);

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(description = "Update package.")
    Response updatePackage(@PathParam("id") String id,
                           @Parameter(name = "version", required = true, example = "1")
                           @QueryParam("version") Integer version, PackageConfiguration packageConfiguration);

    @PUT
    @Path("/{id}/updateResourceUri")
    @Consumes(MediaType.TEXT_PLAIN)
    @Operation(description = "Update references to other resources within this package resource.")
    Response updateResourceInPackage(@PathParam("id") String id,
                                     @Parameter(name = "version", required = true, example = "1")
                                     @QueryParam("version") Integer version, URI resourceURI);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(description = "Create package.")
    Response createPackage(PackageConfiguration packageConfiguration);

    @POST
    @Path("/{id}")
    @Operation(description = "Duplicate this package.")
    Response duplicatePackage(@PathParam("id") String id,
                              @QueryParam("version") Integer version,
                              @QueryParam("deepCopy") @DefaultValue("false") Boolean deepCopy);

    @DELETE
    @Path("/{id}")
    @Operation(description = "Delete package.")
    Response deletePackage(@PathParam("id") String id,
                           @Parameter(name = "version", required = true, example = "1")
                           @QueryParam("version") Integer version);
}
