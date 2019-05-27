package ai.labs.staticresources.rest;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

/**
 * @author ginccc
 */

@Path("/oauth2-redirect.html")
@Produces(MediaType.TEXT_HTML)
public interface IRestOAuth2HtmlRedirect {
    @GET
    String viewHtmlRedirect();
}
