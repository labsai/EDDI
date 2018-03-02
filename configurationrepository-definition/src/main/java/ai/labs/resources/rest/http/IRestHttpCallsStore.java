package ai.labs.resources.rest.http;

import ai.labs.resources.rest.IRestVersionInfo;
import ai.labs.resources.rest.documentdescriptor.model.DocumentDescriptor;
import ai.labs.resources.rest.http.model.HttpCallsConfiguration;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiParam;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;

/**
 * @author ginccc
 */
@Api(value = "configurations")
@Path("/httpcallsstore/http")
public interface IRestHttpCallsStore extends IRestVersionInfo {
    String resourceURI = "eddi://ai.labs.httpcalls/httpcallsstore/http/";

    @GET
    @Path("descriptors")
    @Produces(MediaType.APPLICATION_JSON)
    List<DocumentDescriptor> readHttpCallsDescriptors(@QueryParam("filter") @DefaultValue("") String filter,
                                                      @QueryParam("index") @DefaultValue("0") Integer index,
                                                      @QueryParam("limit") @DefaultValue("20") Integer limit);

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    HttpCallsConfiguration readHttpCalls(@PathParam("id") String id,
                                         @ApiParam(name = "version", required = true, format = "integer", example = "1")
                                         @QueryParam("version") Integer version);

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    Response updateHttpCalls(@PathParam("id") String id,
                             @ApiParam(name = "version", required = true, format = "integer", example = "1")
                             @QueryParam("version") Integer version, HttpCallsConfiguration httpCallsConfiguration);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    Response createHttpCalls(HttpCallsConfiguration httpCallsConfiguration);

    @DELETE
    @Path("/{id}")
    Response deleteHttpCalls(@PathParam("id") String id,
                             @ApiParam(name = "version", required = true, format = "integer", example = "1")
                             @QueryParam("version") Integer version);
}
