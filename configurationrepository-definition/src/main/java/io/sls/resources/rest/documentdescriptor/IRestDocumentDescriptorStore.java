package io.sls.resources.rest.documentdescriptor;

import io.sls.resources.rest.documentdescriptor.model.DocumentDescriptor;
import io.sls.resources.rest.documentdescriptor.model.SimpleDocumentDescriptor;
import io.sls.resources.rest.method.PATCH;
import io.sls.resources.rest.patch.PatchInstruction;
import org.jboss.resteasy.annotations.GZIP;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.util.List;

/**
 * User: jarisch
 * Date: 17.05.12
 * Time: 14:55
 */
@Path("/descriptorstore/descriptors")
public interface IRestDocumentDescriptorStore {
    String resourceURI = "resource://io.sls.descriptor/descriptorstore/descriptors/";

    @GET
    @GZIP
    @Produces(MediaType.APPLICATION_JSON)
    @Deprecated
    List<DocumentDescriptor> readDescriptors(@QueryParam("type") @DefaultValue("") String type,
                                             @QueryParam("filter") @DefaultValue("") String filter,
                                             @QueryParam("index") @DefaultValue("0") Integer index,
                                             @QueryParam("limit") @DefaultValue("20") Integer limit);

    @GET
    @GZIP
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    DocumentDescriptor readDescriptor(@PathParam("id") String id, @QueryParam("version") Integer version) throws Exception;

    @GET
    @GZIP
    @Path("/{id}/simple")
    @Produces(MediaType.APPLICATION_JSON)
    SimpleDocumentDescriptor readSimpleDescriptor(@PathParam("id") String id, @QueryParam("version") Integer version);

    @PATCH
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    void patchDescriptor(@PathParam("id") String id, @QueryParam("version") Integer version, @GZIP PatchInstruction<DocumentDescriptor> patchInstruction);
}
