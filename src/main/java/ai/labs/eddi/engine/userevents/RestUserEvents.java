/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.userevents;

import ai.labs.eddi.engine.security.OwnershipValidator;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * {@code GET /me/events}: opens one stream for the caller and leaves the rest
 * to {@link UserEventHub}.
 * <p>
 * Everything the hub later scopes events by is read here, from this request's
 * authenticated identity — never from a parameter, a header the client chose,
 * or the event itself. An anonymous caller is refused with {@code 401} while
 * authorization is on ({@code @RolesAllowed} does that first; the check here is
 * the second line). With authorization off every caller shares one anonymous
 * bucket and, as everywhere else in that mode, is treated as the owner and an
 * administrator.
 */
@ApplicationScoped
public class RestUserEvents implements IRestUserEvents {

    private static final Logger LOGGER = Logger.getLogger(RestUserEvents.class);

    /** Seconds a client refused at a cap is asked to wait. */
    static final int RETRY_AFTER_SECONDS = 30;

    private final SecurityIdentity identity;
    private final OwnershipValidator ownershipValidator;
    private final Instance<ResourceAccessGuard> accessGuard;
    private final UserEventHub hub;

    @Inject
    public RestUserEvents(SecurityIdentity identity, OwnershipValidator ownershipValidator, Instance<ResourceAccessGuard> accessGuard,
            UserEventHub hub) {
        this.identity = identity;
        this.ownershipValidator = ownershipValidator;
        this.accessGuard = accessGuard;
        this.hub = hub;
    }

    @Override
    public void stream(SseEventSink eventSink, Sse sse) {
        // Thrown before anything is written to the sink, so each of these is a plain
        // HTTP status, not an SSE frame.
        if (!hub.isEnabled()) {
            throw new NotFoundException("The user event stream is switched off on this deployment.");
        }
        UserEventHub.Subscriber subscriber = subscriber();
        try {
            hub.open(subscriber, new SseUserEventSink(eventSink, sse));
        } catch (UserEventHub.Refused refused) {
            LOGGER.debugf("User event stream refused (%s)", refused.reason());
            throw new WebApplicationException(Response.status(429).header("Retry-After", RETRY_AFTER_SECONDS)
                    .type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(Map.of("error", refused.getMessage(), "code", refused.reason(), "retryAfterSeconds", RETRY_AFTER_SECONDS)).build());
        }
    }

    /** The caller, as the hub will scope events for them. */
    UserEventHub.Subscriber subscriber() {
        boolean authEnabled = ownershipValidator.isAuthEnabled();
        String principal = OwnershipValidator.principalName(identity);
        if (authEnabled && (identity == null || identity.isAnonymous() || principal == null)) {
            throw new NotAuthorizedException("Sign in to open the event stream", "Bearer");
        }
        boolean admin = ownershipValidator.isAdmin(identity);
        boolean approver = ownershipValidator.isApprover(identity);
        return new UserEventHub.Subscriber(authEnabled ? principal : null, admin, approver, teamSubjects(), tokenExpiry());
    }

    private List<String> teamSubjects() {
        try {
            return accessGuard != null && accessGuard.isResolvable() ? accessGuard.get().currentTeamSubjects() : List.of();
        } catch (RuntimeException e) {
            // No team shares then: a USE check on a team-shared agent answers no, which
            // only ever withholds an event.
            LOGGER.debugf("Could not read the caller's team subjects: %s", e.getMessage());
            return List.of();
        }
    }

    /** When the caller's access token expires (epoch millis), or 0 without one. */
    private long tokenExpiry() {
        if (identity != null && identity.getPrincipal() instanceof JsonWebToken jwt && jwt.getExpirationTime() > 0) {
            return jwt.getExpirationTime() * 1000L;
        }
        return 0;
    }
}
