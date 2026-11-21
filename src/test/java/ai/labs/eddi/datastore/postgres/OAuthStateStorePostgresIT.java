/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.connections.oauth.OAuthState;
import ai.labs.eddi.connections.oauth.PostgresOAuthStateStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The OAuth state row against a real PostgreSQL: every column the callback
 * reads comes back from the {@code INSERT} through the claiming
 * {@code UPDATE … RETURNING}, including a mailbox link's address and linker
 * hash — positional parameters and a {@code RETURNING} list are exactly what a
 * mocked JDBC test cannot catch.
 */
class OAuthStateStorePostgresIT extends PostgresTestBase {

    private static OAuthState row(String mailboxAddress, String linkerEmailHash) {
        var state = new OAuthState();
        state.setState(UUID.randomUUID().toString());
        state.setTenantId("default");
        state.setConnectionName("gws-support-mailbox");
        state.setConnectionId("68a1b2c3d4e5f60718293a4b");
        state.setPrincipal("admin-alice");
        state.setCodeVerifier("verifier");
        state.setRedirectUri("https://eddi.example.com/connections/callback");
        state.setReturnTo("/manage/connections");
        state.setNonceHash("nonce-hash");
        state.setMailboxAddress(mailboxAddress);
        state.setLinkerEmailHash(linkerEmailHash);
        state.setCreatedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        state.setExpiresAt(Instant.now().plus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS));
        return state;
    }

    @Test
    @DisplayName("a mailbox link's row round-trips through create and claim, field for field")
    void mailboxLinkRoundTrips() {
        var store = new PostgresOAuthStateStore(createDataSourceInstance());
        OAuthState written = row("support@company.example", "linker-hash");
        store.create(written);

        OAuthState claimed = store.claim(written.getState()).orElseThrow();

        assertEquals("support@company.example", claimed.getMailboxAddress());
        assertEquals("linker-hash", claimed.getLinkerEmailHash());
        assertTrue(claimed.isMailboxLink());
        assertEquals("admin-alice", claimed.getPrincipal());
        assertEquals("68a1b2c3d4e5f60718293a4b", claimed.getConnectionId());
        assertEquals("nonce-hash", claimed.getNonceHash());
        assertFalse(store.claim(written.getState()).isPresent(), "single use");
    }

    @Test
    @DisplayName("an ordinary per-user row reads back as one")
    void perUserRowHasNoMailbox() {
        var store = new PostgresOAuthStateStore(createDataSourceInstance());
        OAuthState written = row(null, null);
        store.create(written);

        OAuthState claimed = store.claim(written.getState()).orElseThrow();

        assertNull(claimed.getMailboxAddress());
        assertNull(claimed.getLinkerEmailHash());
        assertFalse(claimed.isMailboxLink());
    }
}
