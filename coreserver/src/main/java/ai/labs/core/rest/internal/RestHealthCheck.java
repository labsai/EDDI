package ai.labs.core.rest.internal;

import ai.labs.rest.IRestHealthCheck;

import javax.enterprise.context.RequestScoped;
import javax.ws.rs.core.Response;

/**
 * @author ginccc
 */
@RequestScoped
public class RestHealthCheck implements IRestHealthCheck {
    @Override
    public Response checkHealth() {
        return Response.ok().build();
    }
}
