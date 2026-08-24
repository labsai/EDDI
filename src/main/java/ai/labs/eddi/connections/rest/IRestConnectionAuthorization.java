/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.rest;

import io.quarkus.security.Authenticated;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.Map;

/**
 * The grant lifecycle: a user linking their own account, seeing what they have
 * linked, and unlinking.
 * <p>
 * Deliberately separate from {@code IRestConnectionStore}. That one is
 * {@code eddi-admin} and manages <em>configuration</em>; this one is any
 * authenticated user and manages <em>their own credentials</em>. Folding them
 * together would mean either admins-only account linking (which defeats the
 * point of {@code PER_USER}) or an admin-scoped path any user can reach.
 * <p>
 * Both are excluded from operator write scope, and for different reasons that
 * both matter: a config write is an egress channel, and these routes mint and
 * destroy credentials without touching a config document at all.
 */
@Path("/connections")
@Tag(name = "Connections", description = "Link and unlink your own accounts for per-user connections")
public interface IRestConnectionAuthorization {

    /**
     * Starts an authorization-code flow for the CALLING user.
     * <p>
     * {@code @Authenticated} rather than a role: linking your own account is not an
     * administrative act. The principal comes from the verified identity — never
     * from a parameter — so there is no way to start a flow on somebody else's
     * behalf.
     *
     * @param returnTo
     *            where to send the browser afterwards. Validated against the
     *            deployment's own public base URL; an unvalidated value here is an
     *            open redirect on a page the user reaches mid-authentication.
     * @return {@code {"authorizationUrl": "…"}} for the browser to follow, plus a
     *         {@code Set-Cookie} that binds the flow to this browser. A client
     *         calling this cross-origin must send and store credentials, or the
     *         callback will refuse — see {@code docs/connections.md}.
     */
    @POST
    @Path("/{name}/authorize")
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Begin linking an account", description = "Returns the provider authorization URL for the calling user.")
    Response authorize(@PathParam("name") String name, @QueryParam("returnTo") String returnTo);

    /**
     * The provider's redirect target.
     * <p>
     * Necessarily a {@code permit} path: the redirect is a top-level browser GET
     * carrying no bearer token, and {@code quarkus.oidc.application-type=service}
     * answers an unauthenticated request with a 401 rather than a login redirect.
     * It is secured by two things that must BOTH hold: the single-use, short-TTL,
     * server-stored {@code state}, which binds tenant, connection and principal;
     * and a nonce cookie proving the callback reached the same browser that started
     * the flow. The state alone is not enough — an attacker who starts a flow under
     * their own account can send the victim the provider's consent link and have
     * the victim's tokens filed under the attacker's principal.
     * <p>
     * The provider's {@code error_description} is deliberately not bound: it is
     * attacker-influenceable text on its way to a browser, so the callback answers
     * a refusal with its own fixed outcome code and logs the provider's short
     * {@code error} value sanitized. Declaring the parameter only to leave it
     * unread invites a later reader to "fix" the omission by echoing it onward.
     */
    @GET
    @Path("/callback")
    @Operation(summary = "OAuth redirect target", description = "Consumes the provider's authorization code. Guarded by a single-use state.")
    Response callback(@QueryParam("code") String code, @QueryParam("state") String state, @QueryParam("error") String error,
                      @Context HttpHeaders headers);

    /** The calling user's linked accounts. Never includes token material. */
    @GET
    @Path("/mine")
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "List your linked accounts", description = "Connection name, status and expiry only — never tokens.")
    List<Map<String, Object>> listMine();

    /** Unlinks the calling user's own account. */
    @DELETE
    @Path("/{name}/grant")
    @Authenticated
    @Operation(summary = "Unlink your account", description = "Deletes the calling user's grant for this connection.")
    Response disconnect(@PathParam("name") String name);
}
