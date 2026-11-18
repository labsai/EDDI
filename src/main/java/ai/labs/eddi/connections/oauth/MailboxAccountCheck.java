/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.oauth;

import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.configs.connections.model.OAuthConfig;
import ai.labs.eddi.connections.ConnectionException;
import ai.labs.eddi.engine.httpclient.outbound.BodyMode;
import ai.labs.eddi.engine.httpclient.outbound.OutboundHttp;
import ai.labs.eddi.engine.httpclient.outbound.OutboundRequest;
import ai.labs.eddi.engine.httpclient.outbound.OutboundResponse;
import ai.labs.eddi.engine.httpclient.outbound.Profile;
import ai.labs.eddi.engine.httpclient.outbound.RedirectPolicy;
import ai.labs.eddi.engine.httpclient.outbound.SsrfPolicy;
import ai.labs.eddi.engine.mail.AddressNormalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Proves that a {@code MAILBOX} grant's access token belongs to the mailbox it
 * is filed under — at link time, before the grant is stored, and after every
 * refresh, before the new token is used.
 * <p>
 * Linking a dedicated mailbox is an administrator signing in to it on EDDI's
 * behalf. Without this check nothing stops that administrator signing in as
 * <em>another</em> account — their own, or any account they hold — and EDDI
 * then reading and answering that mailbox's mail under the dedicated address's
 * name. The consent screen's {@code login_hint} is only a hint.
 * <p>
 * The check is named on the connection ({@code oauth.accountCheck}), never a
 * URL: the endpoint is a constant here, so a connection document cannot point a
 * freshly minted mailbox token at a host of its choosing to "verify" it. The
 * connection's {@code baseUrlAllowlist} must still name the endpoint's origin,
 * because the token is a credential and this is where it is sent.
 */
@ApplicationScoped
public class MailboxAccountCheck {

    private static final Logger LOGGER = Logger.getLogger(MailboxAccountCheck.class);

    /** The Gmail API's {@code users.getProfile} for the token's own account. */
    static final URI GMAIL_PROFILE = URI.create("https://gmail.googleapis.com/gmail/v1/users/me/profile");

    /** A profile is a few hundred bytes; anything near this is not one. */
    static final int MAX_RESPONSE_BYTES = 16 * 1024;

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** What a check found. */
    public enum Outcome {
        /** The token's account is the mailbox. */
        MATCH,
        /**
         * The provider named a different account. Proof the grant is not the mailbox's.
         */
        MISMATCH,
        /**
         * Nothing could be learned — unreachable, an error status, an unreadable
         * answer. Not proof of anything: a link is refused on it, an existing grant is
         * not deleted for it.
         */
        UNAVAILABLE
    }

    private final OutboundHttp http;
    private final URI gmailProfile;

    @Inject
    public MailboxAccountCheck(OutboundHttp http) {
        this(http, GMAIL_PROFILE);
    }

    /** For tests: a fake profile endpoint. */
    MailboxAccountCheck(OutboundHttp http, URI gmailProfile) {
        this.http = http;
        this.gmailProfile = gmailProfile;
    }

    /**
     * Checks that {@code accessToken} belongs to {@code expectedAddress}.
     *
     * @throws ConnectionException
     *             {@code INVALID_CONFIGURATION} when the connection names no known
     *             check, or its allowlist does not let the token reach the check's
     *             endpoint — nothing is sent then
     */
    public Outcome check(ConnectionConfiguration connection, String accessToken, String expectedAddress) {
        String accountCheck = connection.getOauth() == null ? null : connection.getOauth().getAccountCheck();
        if (!OAuthConfig.ACCOUNT_CHECK_GOOGLE_GMAIL_PROFILE.equals(accountCheck)) {
            throw new ConnectionException(ConnectionException.Reason.INVALID_CONFIGURATION,
                    "Connection '" + connection.getName() + "' names no known oauth.accountCheck (one of " + OAuthConfig.ACCOUNT_CHECKS
                            + "), so a mailbox grant cannot be verified. Re-save the connection with the current validation rules.");
        }
        if (!connection.allowlistNames(ConnectionConfiguration.GMAIL_API_ORIGIN)) {
            throw new ConnectionException(ConnectionException.Reason.INVALID_CONFIGURATION,
                    "Connection '" + connection.getName() + "' must list " + ConnectionConfiguration.GMAIL_API_ORIGIN
                            + " in its baseUrlAllowlist: the mailbox's account is read from the Gmail API with the mailbox's own token.");
        }
        String expected = AddressNormalizer.tryNormalize(expectedAddress).orElse(null);
        if (expected == null) {
            return Outcome.MISMATCH;
        }
        Optional<String> actual = gmailProfileAddress(connection, accessToken);
        if (actual.isEmpty()) {
            return Outcome.UNAVAILABLE;
        }
        return expected.equals(actual.get()) ? Outcome.MATCH : Outcome.MISMATCH;
    }

    /**
     * The normalised {@code emailAddress} of the token's Gmail profile, or empty
     * when it could not be read. The address is personal data and is never logged.
     */
    private Optional<String> gmailProfileAddress(ConnectionConfiguration connection, String accessToken) {
        // STRICT SSRF on a fixed public host, and never a redirect: the request
        // carries the mailbox's access token, and a followed redirect would carry it
        // to wherever the answer pointed.
        OutboundRequest request = OutboundRequest.get(Profile.DEFAULT, gmailProfile).ssrf(SsrfPolicy.STRICT).redirects(RedirectPolicy.NEVER)
                .timeout(TIMEOUT).header("Authorization", "Bearer " + accessToken).header("Accept", "application/json").build();
        OutboundResponse<String> response;
        try {
            response = http.send(request, BodyMode.string(MAX_RESPONSE_BYTES, BodyMode.OnOverflow.FAIL));
        } catch (IOException e) {
            LOGGER.warnf("Could not read the Gmail profile for connection '%s' (%s)", sanitize(connection.getName()), e.getClass().getSimpleName());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        if (response.status() < 200 || response.status() >= 300) {
            LOGGER.warnf("The Gmail profile for connection '%s' answered HTTP %d; the mailbox's account could not be verified",
                    sanitize(connection.getName()), response.status());
            return Optional.empty();
        }
        try {
            JsonNode email = MAPPER.readTree(response.body()).path("emailAddress");
            return email.isTextual() ? AddressNormalizer.tryNormalize(email.asText()) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            LOGGER.warnf("The Gmail profile for connection '%s' could not be parsed (%s)", sanitize(connection.getName()),
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
