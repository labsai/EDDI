package ai.labs.group.rest;

import ai.labs.group.model.Group;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 * @author ginccc
 */
@Api(value = "User Management -> (2) Groups")
@Path("/groupstore/groups")
public interface IRestGroupStore {
    String resourceURI = "eddi://ai.labs.group/groupstore/groups/";

    @GET
    @Path("/{groupId}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Read user group.")
    Group readGroup(@PathParam("groupId") String groupId);

    @PUT
    @Path("/{groupId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Update user group.")
    void updateGroup(@PathParam("groupId") String groupId, Group group);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Create user group.")
    Response createGroup(Group group);

    @DELETE
    @Path("/{groupId}")
    @ApiOperation(value = "Delete user group.")
    void deleteGroup(@PathParam("groupId") String groupId);
}
