package io.sls.resources.rest.behavior;

import io.sls.resources.rest.IRestVersionInfo;
import io.sls.resources.rest.behavior.model.BehaviorConfiguration;
import io.sls.resources.rest.documentdescriptor.model.DocumentDescriptor;
import org.jboss.resteasy.annotations.GZIP;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.List;

/**
 * @author ginccc
 */
@Path("/behaviorstore/behaviorsets")
public interface IRestBehaviorStore extends IRestVersionInfo {
    String resourceURI = "resource://io.sls.behavior/behaviorstore/behaviorsets/";
    String versionQueryParam = "?version=";

    @GET
    @GZIP
    @Produces(MediaType.APPLICATION_JSON)
    List<DocumentDescriptor> readBehaviorDescriptors(@QueryParam("filter") @DefaultValue("") String filter,
                                                     @QueryParam("index") @DefaultValue("0") Integer index,
                                                     @QueryParam("limit") @DefaultValue("20") Integer limit);

    @GET
    @GZIP
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    BehaviorConfiguration readBehaviorRuleSet(@PathParam("id") String id, @QueryParam("version") Integer version) throws Exception;

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    URI updateBehaviorRuleSet(@PathParam("id") String id, @QueryParam("version") Integer version, @GZIP BehaviorConfiguration behaviorConfiguration);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    Response createBehaviorRuleSet(@GZIP BehaviorConfiguration behaviorConfiguration);

    @DELETE
    @Path("/{id}")
    void deleteBehaviorRuleSet(@PathParam("id") String id, @QueryParam("version") Integer version);
}
