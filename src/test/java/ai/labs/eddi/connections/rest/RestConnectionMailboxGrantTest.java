/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.rest;

import ai.labs.eddi.configs.connections.IConnectionStore;
import ai.labs.eddi.configs.connections.model.AuthType;
import ai.labs.eddi.configs.connections.model.Binding;
import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.configs.connections.model.OAuthConfig;
import ai.labs.eddi.connections.ConnectionsConfig;
import ai.labs.eddi.connections.CredentialReferenceResolver;
import ai.labs.eddi.connections.MailboxGrants;
import ai.labs.eddi.connections.grants.ConnectionGrant;
import ai.labs.eddi.connections.grants.IConnectionGrantStore;
import ai.labs.eddi.connections.model.ConnectionReference;
import ai.labs.eddi.connections.oauth.CredentialEndpointAllowlist;
import ai.labs.eddi.connections.oauth.IOAuthStateStore;
import ai.labs.eddi.connections.oauth.MailboxAccountCheck;
import ai.labs.eddi.connections.oauth.OAuthState;
import ai.labs.eddi.connections.oauth.OAuthTokenClient;
import ai.labs.eddi.connections.oauth.OAuthTokenService;
import ai.labs.eddi.connections.oauth.TokenResponse;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Linking a dedicated mailbox to a {@code MAILBOX} connection, through the same
 * authorize → provider → callback flow per-user links take.
 * <p>
 * As in {@code RestConnectionAuthorizationCallbackTest}, every callback test
 * starts with a real {@code authorizeMailbox} call and feeds the row and cookie
 * it issued back in. The checks this flow adds — the provider account is the
 * address, the address is not the admin's own — are each tested against a
 * success control with the same harness, so a refusal cannot pass because
 * nothing is ever stored.
 */
class RestConnectionMailboxGrantTest {

    private static final String TENANT = ConnectionReference.DEFAULT_TENANT;
    private static final String CONNECTION_NAME = "gws-support-mailbox";
    private static final String CONNECTION_ID = "68a1b2c3d4e5f60718293a4b";
    private static final String ADMIN = "admin-alice";
    private static final String ADMIN_EMAIL = "alice@company.example";
    private static final String MAILBOX = "support@company.example";
    private static final String CODE = "provider-authorization-code";
    private static final ConnectionsConfig CONFIG = new ConnectionsConfig(true, Optional.of("https://eddi.example.com"));
    private static final TokenResponse TOKEN = new TokenResponse("ya29.mailbox", "1//refresh", Duration.ofHours(1),
            List.of("https://mail.google.com/"));

    private IOAuthStateStore stateStore;
    private IConnectionGrantStore grantStore;
    private OAuthTokenClient tokenClient;
    private OAuthTokenService tokenService;
    private SecurityIdentity identity;
    private JsonWebToken jwt;
    private IConnectionStore connectionStore;
    private MailboxAccountCheck accountCheck;
    private AuditLedgerService auditLedger;
    private ConnectionConfiguration connection;
    private SimpleMeterRegistry meters;

    /** The principal each stored grant was filed under, in order. */
    private final List<String> grantsStoredFor = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        grantsStoredFor.clear();
        stateStore = mock(IOAuthStateStore.class);
        doReturn(true).when(stateStore).exists(anyString());
        grantStore = mock(IConnectionGrantStore.class);
        tokenClient = mock(OAuthTokenClient.class);
        tokenService = mock(OAuthTokenService.class);
        identity = mock(SecurityIdentity.class);
        jwt = mock(JsonWebToken.class);
        connectionStore = mock(IConnectionStore.class);
        accountCheck = mock(MailboxAccountCheck.class);
        auditLedger = mock(AuditLedgerService.class);
        meters = new SimpleMeterRegistry();
        connection = mailboxConnection();

        doReturn(jwt).when(identity).getPrincipal();
        doReturn(ADMIN).when(jwt).getName();
        doReturn(ADMIN_EMAIL).when(jwt).getClaim("email");
        doReturn(true).when(identity).hasRole("eddi-admin");

        doReturn(TOKEN).when(tokenClient).authorizationCode(any(), any(), any(), any(), any());
        doAnswer(invocation -> {
            grantsStoredFor.add(invocation.getArgument(2));
            var grant = new ConnectionGrant();
            grant.setAccessTokenIv("iv-of-this-link");
            return grant;
        }).when(tokenService).persistNew(any(), any(), any(), any(), any());
        doReturn(MailboxAccountCheck.Outcome.MATCH).when(accountCheck).check(any(), eq("ya29.mailbox"), eq(MAILBOX));

        doReturn(CONNECTION_ID).when(connectionStore).idOfName(TENANT, CONNECTION_NAME);
        doReturn(version()).when(connectionStore).getCurrentResourceId(CONNECTION_ID);
        doReturn(connection).when(connectionStore).read(CONNECTION_ID, 1);
        doReturn(true).when(auditLedger).isEnabled();
    }

    private static IResourceStore.IResourceId version() {
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return CONNECTION_ID;
            }

            @Override
            public Integer getVersion() {
                return 1;
            }
        };
    }

    static ConnectionConfiguration mailboxConnection() {
        var connection = new ConnectionConfiguration();
        connection.setName(CONNECTION_NAME);
        connection.setAuthType(AuthType.OAUTH2_AUTHORIZATION_CODE);
        connection.setBinding(Binding.MAILBOX);
        connection.setBaseUrlAllowlist(List.of("imaps://imap.gmail.com", "smtps://smtp.gmail.com", "https://gmail.googleapis.com"));
        var oauth = new OAuthConfig();
        oauth.setAuthorizationUrl("https://accounts.google.com/o/oauth2/v2/auth");
        oauth.setTokenUrl("https://oauth2.googleapis.com/token");
        oauth.setClientId("client-id");
        oauth.setClientSecret("${vault:gws-mailbox-client-secret}");
        oauth.setScopes(List.of("https://mail.google.com/"));
        oauth.setAccountCheck(OAuthConfig.ACCOUNT_CHECK_GOOGLE_GMAIL_PROFILE);
        connection.setOauth(oauth);
        return connection;
    }

    @SuppressWarnings("unchecked")
    private RestConnectionAuthorization resource() {
        var resource = new RestConnectionAuthorization(stateStore, grantStore, tokenClient, tokenService, mock(CredentialEndpointAllowlist.class),
                CONFIG, identity, mock(CredentialReferenceResolver.class), meters, connectionStore);
        resource.mailboxAccountCheck = accountCheck;
        resource.mailboxGrants = new MailboxGrants(grantStore);
        Instance<AuditLedgerService> ledger = mock(Instance.class);
        doReturn(true).when(ledger).isResolvable();
        doReturn(auditLedger).when(ledger).get();
        resource.auditLedger = ledger;
        return resource;
    }

    private record StartedFlow(OAuthState row, NewCookie cookie, String authorizationUrl) {
    }

    private StartedFlow startMailboxLink(RestConnectionAuthorization resource, String address) {
        Response response = resource.authorizeMailbox(CONNECTION_NAME, new IRestConnectionAuthorization.MailboxRequest(address),
                "/manage/connections");
        assertEquals(200, response.getStatus());
        var captor = ArgumentCaptor.forClass(OAuthState.class);
        verify(stateStore, atLeastOnce()).create(captor.capture());
        OAuthState row = captor.getValue();
        NewCookie cookie = response.getCookies().get(RestConnectionAuthorization.COOKIE_PREFIX + row.getState());
        assertNotNull(cookie);
        when(stateStore.claim(row.getState())).thenReturn(Optional.of(row));
        @SuppressWarnings("unchecked")
        String url = ((Map<String, String>) response.getEntity()).get("authorizationUrl");
        return new StartedFlow(row, cookie, url);
    }

    private static HttpHeaders browserWith(NewCookie issued) {
        HttpHeaders headers = mock(HttpHeaders.class);
        doReturn(Map.of(issued.getName(), new Cookie.Builder(issued.getName()).value(issued.getValue()).build())).when(headers).getCookies();
        return headers;
    }

    private static String errorOf(Response response) {
        URI location = response.getLocation();
        assertEquals(303, response.getStatus());
        return location.getQuery();
    }

    private double callbackCount(String outcome) {
        return meters.counter("eddi.connection.oauth.callback.count", "outcome", outcome, "authType", "OAUTH2_AUTHORIZATION_CODE").count();
    }

    @Nested
    @DisplayName("the link flow")
    class Link {

        @Test
        @DisplayName("success: the grant is filed under mailbox:<address>, never under the admin, and audited")
        void successStoresUnderMailboxPrincipal() {
            RestConnectionAuthorization resource = resource();
            StartedFlow flow = startMailboxLink(resource, "Support@Company.Example");

            Response response = resource.callback(CODE, flow.row().getState(), null, browserWith(flow.cookie()));

            assertEquals("mailboxLinked=" + CONNECTION_NAME, errorOf(response));
            assertEquals(List.of(MailboxGrants.PRINCIPAL_PREFIX + MAILBOX), grantsStoredFor);
            // The row stays the admin's (an erasure of the admin withdraws it).
            assertEquals(ADMIN, flow.row().getPrincipal());
            var audit = ArgumentCaptor.forClass(AuditEntry.class);
            verify(auditLedger).submit(audit.capture());
            assertEquals("connection.mailbox.link", audit.getValue().input().get("action"));
            assertEquals(MAILBOX, audit.getValue().input().get("mailbox"));
            assertEquals(ADMIN, audit.getValue().userId());
        }

        @Test
        @DisplayName("the authorization URL asks Google for this account, offline, with consent")
        void authorizationUrlCarriesGoogleParameters() {
            StartedFlow flow = startMailboxLink(resource(), MAILBOX);

            assertTrue(flow.authorizationUrl().contains("login_hint=support%40company.example"), flow.authorizationUrl());
            assertTrue(flow.authorizationUrl().contains("access_type=offline"), flow.authorizationUrl());
            assertTrue(flow.authorizationUrl().contains("prompt=consent"), flow.authorizationUrl());
            assertTrue(flow.authorizationUrl().contains("code_challenge_method=S256"), flow.authorizationUrl());
        }

        @Test
        @DisplayName("a provider account that is not the address is refused, and nothing is stored")
        void accountMismatchIsRefused() {
            doReturn(MailboxAccountCheck.Outcome.MISMATCH).when(accountCheck).check(any(), any(), any());
            RestConnectionAuthorization resource = resource();
            StartedFlow flow = startMailboxLink(resource, MAILBOX);

            Response response = resource.callback(CODE, flow.row().getState(), null, browserWith(flow.cookie()));

            assertEquals("error=mailbox_account_mismatch", errorOf(response));
            assertEquals(List.of(), grantsStoredFor, "a mailbox signed in as the wrong account must not be stored");
            assertEquals(1.0, callbackCount("account_mismatch"));
            verify(auditLedger, never()).submit(any());
        }

        @Test
        @DisplayName("an account that cannot be verified is refused too — never stored unverified")
        void unverifiableAccountIsRefused() {
            doReturn(MailboxAccountCheck.Outcome.UNAVAILABLE).when(accountCheck).check(any(), any(), any());
            RestConnectionAuthorization resource = resource();
            StartedFlow flow = startMailboxLink(resource, MAILBOX);

            Response response = resource.callback(CODE, flow.row().getState(), null, browserWith(flow.cookie()));

            assertEquals("error=mailbox_account_unverified", errorOf(response));
            assertEquals(List.of(), grantsStoredFor);
        }

        @Test
        @DisplayName("the admin's own address is refused at authorize, before the browser leaves")
        void ownMailboxRefusedAtAuthorize() {
            RestConnectionAuthorization resource = resource();

            var refused = assertThrows(BadRequestException.class,
                    () -> resource.authorizeMailbox(CONNECTION_NAME, new IRestConnectionAuthorization.MailboxRequest("ALICE@company.example"), null));
            assertTrue(refused.getMessage().contains("your own mailbox"), refused.getMessage());
            verify(stateStore, never()).create(any());
        }

        @Test
        @DisplayName("the admin's own address is refused at the callback too, from the hash in the row")
        void ownMailboxRefusedAtCallback() {
            RestConnectionAuthorization resource = resource();
            StartedFlow flow = startMailboxLink(resource, MAILBOX);
            // A row whose linker hash names the mailbox itself — the state the
            // authorize-time check exists to prevent, reaching the callback anyway.
            flow.row().setLinkerEmailHash(sha256OfMailbox());

            Response response = resource.callback(CODE, flow.row().getState(), null, browserWith(flow.cookie()));

            assertEquals("error=mailbox_is_own", errorOf(response));
            assertEquals(List.of(), grantsStoredFor);
            verify(tokenClient, never()).authorizationCode(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("a connection that stopped being MAILBOX while the admin was at Google is not filed under anybody")
        void bindingChangedMeanwhile() {
            RestConnectionAuthorization resource = resource();
            StartedFlow flow = startMailboxLink(resource, MAILBOX);
            connection.setBinding(Binding.PER_USER);

            Response response = resource.callback(CODE, flow.row().getState(), null, browserWith(flow.cookie()));

            assertEquals("error=exchange_failed", errorOf(response));
            assertEquals(List.of(), grantsStoredFor, "never fall back to filing the grant under the admin");
        }

        @Test
        @DisplayName("a post-write re-check that fails discards the grant under the mailbox principal, not the admin's")
        void discardUsesMailboxPrincipal() throws Exception {
            RestConnectionAuthorization resource = resource();
            StartedFlow flow = startMailboxLink(resource, MAILBOX);
            doAnswer(invocation -> {
                grantsStoredFor.add(invocation.getArgument(2));
                connection.setBinding(Binding.PER_USER);
                var grant = new ConnectionGrant();
                grant.setAccessTokenIv("iv-of-this-link");
                return grant;
            }).when(tokenService).persistNew(any(), any(), any(), any(), any());

            Response response = resource.callback(CODE, flow.row().getState(), null, browserWith(flow.cookie()));

            assertEquals("error=exchange_failed", errorOf(response));
            verify(grantStore).deleteIfSealedWith(TENANT, CONNECTION_NAME, MailboxGrants.PRINCIPAL_PREFIX + MAILBOX, "iv-of-this-link");
        }

        @Test
        @DisplayName("a PER_USER connection cannot be used to start a mailbox link")
        void perUserConnectionRefused() {
            connection.setBinding(Binding.PER_USER);

            assertThrows(BadRequestException.class,
                    () -> resource().authorizeMailbox(CONNECTION_NAME, new IRestConnectionAuthorization.MailboxRequest(MAILBOX), null));
        }

        @Test
        @DisplayName("an ordinary user's own-account link cannot be started on a MAILBOX connection")
        void perUserAuthorizeRefusedOnMailboxConnection() {
            assertThrows(BadRequestException.class, () -> resource().authorize(CONNECTION_NAME, null));
        }

        @Test
        @DisplayName("a malformed address is refused")
        void malformedAddress() {
            assertThrows(BadRequestException.class,
                    () -> resource().authorizeMailbox(CONNECTION_NAME, new IRestConnectionAuthorization.MailboxRequest("not an address"), null));
        }
    }

    @Nested
    @DisplayName("who may")
    class Access {

        @Test
        @DisplayName("a non-admin is refused on all three routes, checked in code and not only by the annotation")
        void nonAdminRefused() {
            doReturn(false).when(identity).hasRole("eddi-admin");
            RestConnectionAuthorization resource = resource();
            var request = new IRestConnectionAuthorization.MailboxRequest(MAILBOX);

            assertThrows(ForbiddenException.class, () -> resource.authorizeMailbox(CONNECTION_NAME, request, null));
            assertThrows(ForbiddenException.class, () -> resource.unlinkMailbox(CONNECTION_NAME, request));
            assertThrows(ForbiddenException.class, () -> resource.listMailboxGrants(CONNECTION_NAME));
            verify(grantStore, never()).delete(any(), any(), any());
        }

        @Test
        @DisplayName("an anonymous caller is refused")
        void anonymousRefused() {
            doReturn(true).when(identity).isAnonymous();

            assertThrows(ForbiddenException.class,
                    () -> resource().authorizeMailbox(CONNECTION_NAME, new IRestConnectionAuthorization.MailboxRequest(MAILBOX), null));
        }
    }

    @Nested
    @DisplayName("listing and unlinking")
    class ListAndUnlink {

        @Test
        @DisplayName("list shows each mailbox of this connection — address and status, never tokens, never user grants")
        void listsMailboxGrants() {
            var mailboxGrant = grant(CONNECTION_NAME, MailboxGrants.PRINCIPAL_PREFIX + MAILBOX, ConnectionGrant.Status.ACTIVE);
            var userGrant = grant(CONNECTION_NAME, "bob", ConnectionGrant.Status.ACTIVE);
            var otherConnection = grant("other", MailboxGrants.PRINCIPAL_PREFIX + "x@company.example", ConnectionGrant.Status.ACTIVE);
            doReturn(List.of(mailboxGrant, userGrant, otherConnection)).when(grantStore).findByTenant(TENANT);

            List<Map<String, Object>> listed = resource().listMailboxGrants(CONNECTION_NAME);

            assertEquals(1, listed.size(), listed.toString());
            assertEquals(MAILBOX, listed.get(0).get("address"));
            assertEquals("ACTIVE", listed.get(0).get("status"));
            assertEquals(true, listed.get(0).get("usable"));
            assertTrue(listed.get(0).keySet().stream().noneMatch(key -> key.toLowerCase().contains("token")), listed.toString());
        }

        @Test
        @DisplayName("unlink deletes by name under the mailbox principal and audits it")
        void unlinkDeletes() {
            Response response = resource().unlinkMailbox(CONNECTION_NAME, new IRestConnectionAuthorization.MailboxRequest("SUPPORT@company.example"));

            assertEquals(204, response.getStatus());
            verify(grantStore).delete(TENANT, CONNECTION_NAME, MailboxGrants.PRINCIPAL_PREFIX + MAILBOX);
            var audit = ArgumentCaptor.forClass(AuditEntry.class);
            verify(auditLedger).submit(audit.capture());
            assertEquals("connection.mailbox.unlink", audit.getValue().input().get("action"));
        }
    }

    private static ConnectionGrant grant(String connectionName, String principal, ConnectionGrant.Status status) {
        var grant = new ConnectionGrant();
        grant.setTenantId(TENANT);
        grant.setConnectionName(connectionName);
        grant.setPrincipal(principal);
        grant.setStatus(status);
        grant.setEncryptedAccessToken("ciphertext");
        grant.setExpiresAt(Instant.now().plusSeconds(3600));
        return grant;
    }

    /**
     * base64url(sha256(mailbox)), the form the row stores the linker's address in.
     */
    private static String sha256OfMailbox() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(MAILBOX.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
