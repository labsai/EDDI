/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.exception;

import ai.labs.eddi.connections.ConnectionException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A connection refusal has to arrive as the status that describes it, carrying
 * the sentence that says what to do about it.
 * <p>
 * Without this mapper every {@link ConnectionException} escaping a resource is
 * a bare 500 with an empty body, and the one piece of text that names the fix —
 * "add this origin to the allowlist", "connect your account first" — reaches
 * the server log only, which is the one place the caller who has to act on it
 * never looks.
 */
@DisplayName("connection refusals reach the caller as the right status, with the message")
class ConnectionExceptionMapperTest {

    private final ConnectionExceptionMapper mapper = new ConnectionExceptionMapper();

    /**
     * The intended status of every reason, written out rather than derived, so a
     * change to the mapping has to be re-decided here too.
     * <p>
     * The three "you have not linked an account" reasons are 409 and not 401 or 403
     * on purpose: the caller is authenticated and permitted, the resource simply is
     * not in a state that can serve the request, and what fixes it is connecting
     * rather than presenting a different token.
     */
    private static final Map<ConnectionException.Reason, Integer> EXPECTED = new EnumMap<>(Map.of(
            ConnectionException.Reason.INVALID_CONFIGURATION, 400,
            ConnectionException.Reason.UNSUPPORTED_PLACEMENT, 400,
            ConnectionException.Reason.TARGET_NOT_ALLOWED, 400,
            // 400 rather than the 409 its neighbours use, and the difference is the
            // point: those are fixed by a human linking an account, while this one says
            // the request itself was incomplete — the calling system omitted a header it
            // was supposed to attach, and the same call by the same user keeps failing
            // until it sends one. A 409 would send an integrator looking for an
            // unconnected user who does not exist.
            ConnectionException.Reason.NO_CALLER_CREDENTIAL, 400,
            ConnectionException.Reason.NOT_FOUND, 404,
            ConnectionException.Reason.NOT_CONNECTED, 409,
            ConnectionException.Reason.NO_VERIFIED_PRINCIPAL, 409,
            ConnectionException.Reason.GRANT_UNUSABLE, 409,
            ConnectionException.Reason.TOKEN_ENDPOINT_UNAVAILABLE, 503));

    @Test
    @DisplayName("the table above covers every reason there is")
    void everyReasonIsCovered() {
        // A new Reason added without a decision here would otherwise be tested by
        // nothing, and the switch in the mapper would pick a status nobody chose.
        assertEquals(ConnectionException.Reason.values().length, EXPECTED.size(),
                "a ConnectionException.Reason was added without deciding which HTTP status describes it");
    }

    @Test
    @DisplayName("each reason maps to the status that describes it")
    void reasonsMapToTheirStatus() {
        EXPECTED.forEach((reason, expectedStatus) -> {
            Response response = mapper.toResponse(new ConnectionException(reason, "message for " + reason));
            assertEquals(expectedStatus.intValue(), response.getStatus(),
                    reason + " must be answered with " + expectedStatus + "; a wrong status sends the caller to fix the wrong thing — a 403 "
                            + "for NOT_CONNECTED, say, reads as 'you are not allowed' rather than 'link your account'");
        });
    }

    @Test
    @DisplayName("the actionable message becomes the response body")
    void messageReachesTheEntity() {
        String actionable = "eddi.connections.credential-endpoint-allowlist does not contain https://auth.example.com. Add it if that is "
                + "intended.";

        Response response = mapper.toResponse(new ConnectionException(ConnectionException.Reason.INVALID_CONFIGURATION, actionable));

        assertEquals(actionable, response.getEntity(),
                "the sentence naming the setting to change is the whole value of this mapper; dropping it leaves the caller a bare status");
        assertEquals(MediaType.TEXT_PLAIN_TYPE, response.getMediaType(),
                "the body is a plain sentence, and typing it as anything else invites a client to parse it as structured data");
    }

    @Test
    @DisplayName("a reason-less exception is a 500 rather than an accidental 4xx")
    void missingReasonIsAServerError() {
        // Every constructed ConnectionException names a reason, so a null one means
        // something built it wrong — the caller cannot act on that, and telling them
        // it is their request would be a lie.
        Response response = mapper.toResponse(new ConnectionException(null, "no reason given"));

        assertEquals(500, response.getStatus(), "an unclassifiable failure must not be reported as the caller's fault");
        assertEquals("no reason given", response.getEntity(), "the message is still the only clue anyone gets");
    }
}
