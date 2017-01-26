package ai.labs.resources.rest.extensions;

import ai.labs.resources.rest.documentdescriptor.model.DocumentDescriptor;
import ai.labs.resources.rest.extensions.model.ExtensionDefinition;
import org.jboss.resteasy.annotations.GZIP;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.List;

/**
 * @author ginccc
 */
@Path("/extensionstore/extensions")
public interface IRestExtensionStore {
    String resourceURI = "eddi://ai.labs.extensions/extensionstore/extensions/";
    String versionQueryParam = "?version=";

    @GET
    @GZIP
    @Produces(MediaType.APPLICATION_JSON)
    List<DocumentDescriptor> readExtensionDescriptors(@QueryParam("filter") @DefaultValue("") String filter,
                                                      @QueryParam("index") @DefaultValue("0") Integer index,
                                                      @QueryParam("limit") @DefaultValue("20") Integer limit);

    @GET
    @GZIP
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    ExtensionDefinition readExtension(@PathParam("id") String id, @QueryParam("version") Integer version) throws Exception;

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    URI updateExtension(@PathParam("id") String id, @QueryParam("version") Integer version, @GZIP ExtensionDefinition extension);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    Response createExtension(@GZIP ExtensionDefinition extension);

    @DELETE
    @Path("/{id}")
    void deleteExtension(@PathParam("id") String id, @QueryParam("version") Integer version);
}
