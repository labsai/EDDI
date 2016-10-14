package io.sls.group.rest;

import io.sls.group.model.Group;
import org.jboss.resteasy.annotations.GZIP;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 * User: jarisch
 * Date: 29.08.12
 * Time: 10:52
 */
@Path("/groupstore/groups")
public interface IRestGroupStore {
    @GET
    @GZIP
    @Path("/{groupId}")
    @Produces(MediaType.APPLICATION_JSON)
    Group readGroup(@PathParam("groupId") String groupId) throws Exception;

    @PUT
    @Path("/{groupId}")
    @Consumes(MediaType.APPLICATION_JSON)
    void updateGroup(@PathParam("groupId") String groupId, @GZIP Group group);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    Response createGroup(@GZIP Group group);

    @DELETE
    @Path("/{groupId}")
    void deleteGroup(@PathParam("groupId") String groupId);
}
