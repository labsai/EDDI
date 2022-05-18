package ai.labs.eddi.ui;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@Path("/")
public interface IRestManagerProxyResource {

    @GET
    @Path("/scripts/{path:.*}")
    @Produces(MediaType.APPLICATION_JSON)
    Response proxyClientScript(@PathParam("path") String path);

    @GET
    @Path("/manager")
    @Produces(MediaType.MEDIA_TYPE_WILDCARD)
    Response
    proxyClientRequest();

    @GET
    @Path("/manager/{path:.*}")
    @Produces(MediaType.MEDIA_TYPE_WILDCARD)
    Response proxyClientRequest(@PathParam("path") String path);
}
