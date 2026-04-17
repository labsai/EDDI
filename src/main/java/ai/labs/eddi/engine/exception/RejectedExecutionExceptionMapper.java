package ai.labs.eddi.engine.exception;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

/**
 * Maps {@link RejectedExecutionException} to HTTP 503 Service Unavailable.
 *
 * <p>
 * This exception is thrown by the {@code IConversationCoordinator} when the
 * coordinator's capacity is exceeded (too many active conversations) or when a
 * task cannot be enqueued. Without this mapper, the exception would surface as
 * an HTTP 500 Internal Server Error — misleading for a transient backpressure
 * condition.
 * </p>
 *
 * @author ginccc
 * @since 6.0.2
 */
@Provider
public class RejectedExecutionExceptionMapper implements ExceptionMapper<RejectedExecutionException> {

    @Override
    public Response toResponse(RejectedExecutionException exception) {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(Map.of(
                        "error", "capacity_exceeded",
                        "message", exception.getMessage() != null ? exception.getMessage() : "Service temporarily unavailable"))
                .type(MediaType.APPLICATION_JSON)
                .header("Retry-After", "5")
                .build();
    }
}
