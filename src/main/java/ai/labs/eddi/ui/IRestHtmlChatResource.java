package ai.labs.eddi.ui;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 * @author ginccc
 */

@Path("/chat")
@Produces(MediaType.TEXT_HTML)
public interface IRestHtmlChatResource {

    @GET
    Response viewDefault();

    @GET
    @Path("{path:.*}")
    Response viewHtml(@PathParam("path") String path);
}
