package ai.labs.eddi.ui;

import org.eclipse.microprofile.openapi.annotations.tags.Tag;

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
@Tag(name = "13. Bot Chat UI", description = "Responsive Chat Window")
public interface IRestHtmlChatResource {

    @GET
    Response viewDefault();

    @GET
    @Path("{path:.*}")
    Response viewHtml(@PathParam("path") String path);
}
