package ai.labs.core.rest.internal;

import ai.labs.rest.restinterfaces.IRestHealthCheck;

import javax.ws.rs.core.Response;

/**
 * @author ginccc
 */
public class RestHealthCheck implements IRestHealthCheck {
    @Override
    public Response checkHealth() {
        return Response.ok().build();
    }
}
