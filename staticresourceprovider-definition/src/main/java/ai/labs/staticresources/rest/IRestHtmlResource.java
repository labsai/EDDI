package ai.labs.staticresources.rest;

import javax.ws.rs.GET;
import javax.ws.rs.Path;

/**
 * @author ginccc
 */

@Path("/view")
public interface IRestHtmlResource {

    @GET
    String viewDefault();

    @GET
    @Path("{path:.*}")
    String viewHtml(String path);
}
