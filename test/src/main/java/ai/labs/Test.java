package ai.labs;

import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@ApplicationScoped
@Path("/test")
public class Test {
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public Response ok() {
        return Response.ok("test").build();
    }
}
