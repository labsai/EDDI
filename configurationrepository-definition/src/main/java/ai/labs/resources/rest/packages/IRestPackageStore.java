package ai.labs.resources.rest.packages;

import ai.labs.resources.rest.IRestVersionInfo;
import ai.labs.resources.rest.documentdescriptor.model.DocumentDescriptor;
import ai.labs.resources.rest.packages.model.PackageConfiguration;
import io.swagger.annotations.Api;
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
    List<DocumentDescriptor> readPackageDescriptors(@QueryParam("filter") @DefaultValue("") String filter,
                                                    @QueryParam("index") @DefaultValue("0") Integer index,
                                                    @QueryParam("limit") @DefaultValue("20") Integer limit);

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    PackageConfiguration readPackage(@PathParam("id") String id,
                                     @ApiParam(name = "version", required = true, format = "integer", example = "1")
                                     @QueryParam("version") Integer version);

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    Response updatePackage(@PathParam("id") String id,
                           @ApiParam(name = "version", required = true, format = "integer", example = "1")
                      @QueryParam("version") Integer version, PackageConfiguration packageConfiguration);

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.TEXT_PLAIN)
    Response updateResourceInPackage(@PathParam("id") String id,
                                     @ApiParam(name = "version", required = true, format = "integer", example = "1")
                                     @QueryParam("version") Integer version, URI resourceURI);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    Response createPackage(PackageConfiguration packageConfiguration);

    @DELETE
    @Path("/{id}")
    void deletePackage(@PathParam("id") String id,
                       @ApiParam(name = "version", required = true, format = "integer", example = "1")
                       @QueryParam("version") Integer version);
}
