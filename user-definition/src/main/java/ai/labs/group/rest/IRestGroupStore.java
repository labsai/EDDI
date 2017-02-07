package ai.labs.group.rest;

import ai.labs.group.model.Group;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 * @author ginccc
 */
@Path("/groupstore/groups")
public interface IRestGroupStore {
    @GET
    @Path("/{groupId}")
    @Produces(MediaType.APPLICATION_JSON)
    Group readGroup(@PathParam("groupId") String groupId) throws Exception;

    @PUT
    @Path("/{groupId}")
    @Consumes(MediaType.APPLICATION_JSON)
    void updateGroup(@PathParam("groupId") String groupId, Group group);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    Response createGroup(Group group);

    @DELETE
    @Path("/{groupId}")
    void deleteGroup(@PathParam("groupId") String groupId);
}
