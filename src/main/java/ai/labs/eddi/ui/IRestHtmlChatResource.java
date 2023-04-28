package ai.labs.eddi.ui;

import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

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
