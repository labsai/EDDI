package io.sls.resources.rest.packages;

import io.sls.resources.rest.IRestVersionInfo;
import io.sls.resources.rest.documentdescriptor.model.DocumentDescriptor;
import io.sls.resources.rest.packages.model.PackageConfiguration;
import org.jboss.resteasy.annotations.GZIP;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.List;

/**
 * @author ginccc
 */
@Path("/packagestore/packages")
public interface IRestPackageStore extends IRestVersionInfo {
    String resourceURI = "resource://io.sls.package/packagestore/packages/";
    String versionQueryParam = "?version=";

    @GET
    @GZIP
    @Produces(MediaType.APPLICATION_JSON)
    List<DocumentDescriptor> readPackageDescriptors(@QueryParam("filter") @DefaultValue("") String filter,
                                                    @QueryParam("index") @DefaultValue("0") Integer index,
                                                    @QueryParam("limit") @DefaultValue("20") Integer limit);

    @GET
    @GZIP
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    PackageConfiguration readPackage(@PathParam("id") String id, @QueryParam("version") Integer version) throws Exception;

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    URI updatePackage(@PathParam("id") String id, @QueryParam("version") Integer version, @GZIP PackageConfiguration packageConfiguration);

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.TEXT_PLAIN)
    Response updateResourceInPackage(@PathParam("id") String id, @QueryParam("version") Integer version, @GZIP URI resourceURI);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    Response createPackage(@GZIP PackageConfiguration packageConfiguration);

    @DELETE
    @Path("/{id}")
    void deletePackage(@PathParam("id") String id, @QueryParam("version") Integer version);
}
