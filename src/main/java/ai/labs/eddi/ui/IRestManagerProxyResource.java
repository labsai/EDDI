package ai.labs.eddi.ui;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/")
public interface IRestManagerProxyResource {

    @GET
    @Path("/scripts/{path:.*}")
    @Produces(MediaType.APPLICATION_JSON)
    Response proxyClientScript(@PathParam("path") String path);

    @GET
    @Path("/manage")
    @Produces(MediaType.TEXT_HTML)
    Response
    proxyClientRequest();

    @GET
    @Path("/manage/{path:.*}")
    @Produces(MediaType.TEXT_HTML)
    Response proxyClientRequest(@PathParam("path") String path);
}
