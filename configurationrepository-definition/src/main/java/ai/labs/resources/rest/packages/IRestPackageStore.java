package ai.labs.resources.rest.packages;

import ai.labs.resources.rest.IRestVersionInfo;
import ai.labs.resources.rest.documentdescriptor.model.DocumentDescriptor;
import ai.labs.resources.rest.packages.model.PackageConfiguration;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.List;

/**
 * @author ginccc
 */
@Api(value = "configurations")
@Path("/packagestore/packages")
public interface IRestPackageStore extends IRestVersionInfo {
    String resourceURI = "eddi://ai.labs.package/packagestore/packages/";

    @GET
    @Path("descriptors")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Read list of package descriptors.")
    List<DocumentDescriptor> readPackageDescriptors(@QueryParam("filter") @DefaultValue("") String filter,
                                                    @QueryParam("index") @DefaultValue("0") Integer index,
                                                    @QueryParam("limit") @DefaultValue("20") Integer limit);

    @POST
    @Path("descriptors")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Read list of package descriptors including a given resourceUri.")
    List<DocumentDescriptor> readPackageDescriptors(
            @QueryParam("filter") @DefaultValue("") String filter,
            @QueryParam("index") @DefaultValue("0") Integer index,
            @QueryParam("limit") @DefaultValue("20") Integer limit,
            @ApiParam(name = "body", value = "eddi://ai.labs.TYPE/PATH/ID?version=VERSION")
            @DefaultValue("") String containingResourceUri,
            @QueryParam("includePreviousVersions") @DefaultValue("false") Boolean includePreviousVersions);

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Read package.")
    PackageConfiguration readPackage(@PathParam("id") String id,
                                     @ApiParam(name = "version", required = true, format = "integer", example = "1")
                                     @QueryParam("version") Integer version);

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Update package.")
    Response updatePackage(@PathParam("id") String id,
                           @ApiParam(name = "version", required = true, format = "integer", example = "1")
                           @QueryParam("version") Integer version, PackageConfiguration packageConfiguration);

    @PUT
    @Path("/{id}/updateResourceUri")
    @Consumes(MediaType.TEXT_PLAIN)
    @ApiOperation(value = "Update references to other resources within this package resource.")
    Response updateResourceInPackage(@PathParam("id") String id,
                                     @ApiParam(name = "version", required = true, format = "integer", example = "1")
                                     @QueryParam("version") Integer version, URI resourceURI);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Create package.")
    Response createPackage(PackageConfiguration packageConfiguration);

    @POST
    @Path("/{id}")
    @ApiOperation(value = "Duplicate this package.")
    Response duplicatePackage(@PathParam("id") String id,
                              @QueryParam("version") Integer version,
                              @QueryParam("deepCopy") @DefaultValue("false") Boolean deepCopy);

    @DELETE
    @Path("/{id}")
    @ApiOperation(value = "Delete package.")
    Response deletePackage(@PathParam("id") String id,
                           @ApiParam(name = "version", required = true, format = "integer", example = "1")
                           @QueryParam("version") Integer version);
}
