package ai.labs.staticresources.rest;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

/**
 * @author ginccc
 */

@Path("/chat")
@Produces(MediaType.TEXT_HTML)
public interface IRestHtmlChatResource {

    @GET
    String viewDefault();

    @GET
    @Path("{path:.*}")
    String viewHtml(@PathParam("path") String path);
}
