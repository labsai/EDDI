/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces.directory;

import ai.labs.eddi.engine.security.spaces.CallerSpaces;
import ai.labs.eddi.engine.security.spaces.SpaceContext;
import ai.labs.eddi.engine.security.spaces.Subjects;
import ai.labs.eddi.engine.security.spaces.WorkspaceSettings;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.json.JsonValue;
import jakarta.ws.rs.BadRequestException;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The directory decides who a share reaches, so every rule here is one where
 * getting it wrong is silent: a grant stored for nobody, or for the wrong
 * person.
 */
class UserDirectoryTest {

    private static final CallerSpaces ALICE = CallerSpaces.of("alice", Set.of("/engineering"));

    private IUserDirectoryStore store;
    private SecurityIdentity identity;
    private SpaceContext spaceContext;
    private WorkspaceSettings settings;
    private ManagedExecutor executor;
    private UserDirectory directory;

    @BeforeEach
    void setUp() {
        store = mock(IUserDirectoryStore.class);
        identity = mock(SecurityIdentity.class);
        spaceContext = mock(SpaceContext.class);
        settings = mock(WorkspaceSettings.class);
        executor = mock(ManagedExecutor.class);
        when(settings.isStampingOwnership()).thenReturn(true);
        // Run scheduled writes inline, so a test can observe them.
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(executor).execute(any());
        directory = new UserDirectory(store, identity, spaceContext, settings, executor, true, true);
    }

    private static DirectoryUser user(String principal, String username, String email, boolean verified, String name) {
        return new DirectoryUser(principal, "sub-" + principal, username, email, verified, name, List.of(), Instant.EPOCH, Instant.EPOCH);
    }

    @Nested
    @DisplayName("resolving a person")
    class ResolvePerson {

        @Test
        @DisplayName("an exact principal wins, even over a username that looks the same")
        void exactPrincipalFirst() {
            when(store.find("carol")).thenReturn(Optional.of(user("carol", "carol", null, false, "Carol")));

            assertEquals(Subjects.user("carol"), directory.resolveSubject("carol", ALICE, false));
            verify(store, never()).findByUsername(anyString());
        }

        @Test
        @DisplayName("a username resolves to that account's principal")
        void usernameResolves() {
            when(store.find("Carol.T")).thenReturn(Optional.empty());
            when(store.findByUsername("Carol.T")).thenReturn(List.of(user("f81d4fae", "carol.t", null, false, "Carol")));

            assertEquals(Subjects.user("f81d4fae"), directory.resolveSubject("user:Carol.T", ALICE, false));
        }

        @Test
        @DisplayName("a verified email resolves — the grant is stored against the principal, not the address")
        void verifiedEmailResolves() {
            when(store.find(anyString())).thenReturn(Optional.empty());
            when(store.findByVerifiedEmail("carol@example.com")).thenReturn(List.of(user("carol", "carol", "carol@example.com", true, "Carol")));

            assertEquals(Subjects.user("carol"), directory.resolveSubject("carol@example.com", ALICE, false),
                    "this is the share that used to be stored as user:carol@example.com and reach nobody");
        }

        @Test
        @DisplayName("an email the provider did not verify is not a way to find anybody")
        void unverifiedEmailRefused() {
            when(store.find(anyString())).thenReturn(Optional.empty());
            // The store only ever returns verified matches; an unverified address
            // therefore comes back empty, and the refusal must say why.
            when(store.findByVerifiedEmail(anyString())).thenReturn(List.of());

            var refusal = assertThrows(BadRequestException.class, () -> directory.resolveSubject("ceo@example.com", ALICE, false));
            assertTrue(refusal.getMessage().contains("verified"), refusal.getMessage());
        }

        @Test
        @DisplayName("somebody who never signed in is refused with a message that says so")
        void unknownPersonRefused() {
            when(store.find(anyString())).thenReturn(Optional.empty());

            var refusal = assertThrows(BadRequestException.class, () -> directory.resolveSubject("caorl", ALICE, false));
            assertTrue(refusal.getMessage().contains("signed in"), "'no such user' for a real colleague reads as a bug");
        }

        @Test
        @DisplayName("two accounts sharing a username are refused rather than guessed between")
        void ambiguousRefused() {
            when(store.find(anyString())).thenReturn(Optional.empty());
            when(store.findByUsername("sam")).thenReturn(List.of(user("sam-1", "sam", null, false, "Sam A"), user("sam-2", "Sam", null, false,
                    "Sam B")));

            var refusal = assertThrows(BadRequestException.class, () -> directory.resolveSubject("sam", ALICE, false));
            assertTrue(refusal.getMessage().contains("sam-1") && refusal.getMessage().contains("sam-2"));
        }

        @Test
        @DisplayName("an unknown prefix is refused rather than read as a name")
        void unknownPrefixRefused() {
            assertThrows(BadRequestException.class, () -> directory.resolveSubject("group:engineering", ALICE, false));
            verify(store, never()).find(anyString());
        }

        @Test
        @DisplayName("with the directory switched off, a name passes through as it always did")
        void inactivePassesThrough() {
            var off = new UserDirectory(store, identity, spaceContext, settings, executor, false, true);

            assertEquals(Subjects.user("anyone@example.com"), off.resolveSubject("anyone@example.com", ALICE, false));
            verify(store, never()).find(anyString());
        }
    }

    @Nested
    @DisplayName("resolving a team")
    class ResolveTeam {

        @Test
        @DisplayName("the caller's own team is valid before anybody else in it signed in")
        void ownTeamAccepted() {
            assertEquals(Subjects.team("engineering"), directory.resolveSubject("team:/engineering/", ALICE, false));
            verify(store, never()).teamExists(anyString());
        }

        @Test
        @DisplayName("a team some recorded user belongs to is valid")
        void knownTeamAccepted() {
            when(store.teamExists(Subjects.team("finance"))).thenReturn(true);

            assertEquals(Subjects.team("finance"), directory.resolveSubject("team:finance", ALICE, false));
        }

        @Test
        @DisplayName("a misspelt team is refused — it would be a grant nobody holds")
        void unknownTeamRefused() {
            when(store.teamExists(anyString())).thenReturn(false);

            assertThrows(BadRequestException.class, () -> directory.resolveSubject("team:markting", ALICE, false));
        }

        @Test
        @DisplayName("an administrator may name any team")
        void adminMayNameAnyTeam() {
            assertEquals(Subjects.team("markting"), directory.resolveSubject("team:markting", ALICE, true));
        }
    }

    @Nested
    @DisplayName("recording")
    class Recording {

        private void signedInAs(String principal, String email, Object verified) {
            var jwt = mock(JsonWebToken.class);
            when(jwt.getSubject()).thenReturn("sub-" + principal);
            when(jwt.getClaim("preferred_username")).thenReturn(principal);
            when(jwt.getClaim("email")).thenReturn(email);
            when(jwt.getClaim("email_verified")).thenReturn(verified);
            when(jwt.getClaim("name")).thenReturn("Name " + principal);
            when(identity.getPrincipal()).thenReturn(jwt);
            when(spaceContext.currentPrincipal()).thenReturn(principal);
            when(spaceContext.currentGroupPaths()).thenReturn(Set.of("/engineering"));
        }

        @Test
        @DisplayName("records what the token says, including teams and whether the email was verified")
        void recordsProfile() {
            signedInAs("alice", "alice@example.com", Boolean.TRUE);

            directory.recordCurrentCaller();

            var captor = ArgumentCaptor.forClass(DirectoryUser.class);
            verify(store).upsert(captor.capture());
            DirectoryUser recorded = captor.getValue();
            assertEquals("alice", recorded.principal());
            assertEquals("sub-alice", recorded.subject());
            assertEquals("alice@example.com", recorded.email());
            assertTrue(recorded.emailVerified());
            assertEquals(List.of(Subjects.team("engineering")), recorded.teams());
        }

        @Test
        @DisplayName("email_verified arriving as a JSON value is still read, and 'false' stays false")
        void readsJsonBooleans() {
            signedInAs("alice", "\"alice@example.com\"", JsonValue.FALSE);

            directory.recordCurrentCaller();

            var captor = ArgumentCaptor.forClass(DirectoryUser.class);
            verify(store).upsert(captor.capture());
            assertFalse(captor.getValue().emailVerified());
            assertEquals("alice@example.com", captor.getValue().email(), "the JSON string's quotes must be stripped");
        }

        @Test
        @DisplayName("an unchanged caller is written once, not on every request")
        void writesOncePerChange() {
            signedInAs("alice", "alice@example.com", Boolean.TRUE);

            directory.recordCurrentCaller();
            directory.recordCurrentCaller();
            directory.recordCurrentCaller();

            verify(store, times(1)).upsert(any());
        }

        @Test
        @DisplayName("an anonymous caller is never recorded")
        void anonymousNotRecorded() {
            when(spaceContext.currentPrincipal()).thenReturn(null);

            directory.recordCurrentCaller();

            verify(store, never()).upsert(any());
        }

        @Test
        @DisplayName("nothing is recorded while authentication is off")
        void inactiveWithoutAuth() {
            when(settings.isStampingOwnership()).thenReturn(false);
            signedInAs("alice", "alice@example.com", Boolean.TRUE);

            directory.recordCurrentCaller();

            verify(store, never()).upsert(any());
        }

        @Test
        @DisplayName("a failed write is retried on the next request instead of being remembered as done")
        void failedWriteRetried() {
            signedInAs("alice", "alice@example.com", Boolean.TRUE);
            doAnswer(invocation -> {
                throw new IllegalStateException("database down");
            }).doNothing().when(store).upsert(any());

            directory.recordCurrentCaller();
            directory.recordCurrentCaller();

            verify(store, times(2)).upsert(any());
        }
    }

    @Nested
    @DisplayName("search and labels")
    class SearchAndLabels {

        @Test
        @DisplayName("offers teams first, never the caller themselves, and escapes nothing into a pattern")
        void searchOrdersAndExcludesSelf() {
            when(store.searchTeams("e", 5)).thenReturn(List.of(Subjects.team("engineering"), Subjects.team("exec")));
            when(store.search("e", 6)).thenReturn(List.of(user("alice", "alice", "e.alice@example.com", true, "Alice"),
                    user("eve", "eve", "eve@example.com", true, "Eve")));

            var matches = directory.search("e", 5, ALICE);

            assertEquals("team", matches.get(0).kind());
            assertEquals("engineering", matches.get(0).label());
            assertTrue(matches.stream().noneMatch(m -> m.subject().equals(Subjects.user("alice"))), "sharing with yourself is never meant");
            assertTrue(matches.stream().anyMatch(m -> m.subject().equals(Subjects.user("eve")) && "eve@example.com".equals(m.detail())));
        }

        @Test
        @DisplayName("the email is withheld when the deployment does not expose it")
        void emailHiddenWhenNotExposed() {
            var private_ = new UserDirectory(store, identity, spaceContext, settings, executor, true, false);
            when(store.search(anyString(), anyInt())).thenReturn(List.of(user("eve", "eve", "eve@example.com", true, "Eve")));

            var matches = private_.search("ev", 5, ALICE);

            assertTrue(matches.stream().noneMatch(m -> "eve@example.com".equals(m.detail())));
        }

        @Test
        @DisplayName("labels are looked up once per page and then served from cache")
        void labelsAreCached() {
            when(store.findAll(any())).thenReturn(Map.of("bob", user("bob", "bob", null, false, "Bob Builder")));

            assertEquals("Bob Builder", directory.labelFor("bob"));
            assertEquals("Bob Builder", directory.labelFor("bob"));
            assertEquals("ghost", directory.labelFor("ghost"), "an unknown principal is shown as itself");
            assertNull(directory.labelFor(null));

            verify(store, times(2)).findAll(any());
        }
    }
}
