/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces;

import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.descriptors.model.ResourceVisibility;
import ai.labs.eddi.engine.security.spaces.rest.RestResourceSharing;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The layer between a query string and the sharing service.
 *
 * <h3>Why this deserves its own tests</h3> Everything here turns loose text
 * into a decision about who can reach a resource, and every failure mode is
 * silent: a level that parsed to something weaker, a subject nobody holds, a
 * visibility guessed between three options that differ on who can read the
 * thing. None of those look like errors afterwards — they look like a share
 * that worked. So each one is asserted to be refused, and the refusal is
 * asserted to happen <em>before</em> the service is called.
 */
class RestResourceSharingTest {

    private static final String RESOURCE_ID = "aaaaaaaaaaaaaaaaaaaaaaaa";

    private ResourceSharingService service;
    private RestResourceSharing sut;

    @BeforeEach
    void setUp() {
        service = mock(ResourceSharingService.class);
        sut = new RestResourceSharing(service);
    }

    private static ResourceSharingService.ShareResult empty() {
        return new ResourceSharingService.ShareResult(List.of(), List.of());
    }

    @Nested
    @DisplayName("subject normalisation")
    class Subject {

        @Test
        @DisplayName("a bare name is read as a person, because that is what people type")
        void bareNameBecomesUser() {
            when(service.share(anyString(), anyString(), any(), anyBoolean())).thenReturn(empty());

            sut.share(RESOURCE_ID, "alice@example.com", "USE", true);

            verify(service).share(eq(RESOURCE_ID), eq(Subjects.user("alice@example.com")), eq(AccessLevel.USE), eq(true));
        }

        @Test
        @DisplayName("an explicit prefix is normalised, not passed through raw")
        void prefixesAreNormalised() {
            when(service.share(anyString(), anyString(), any(), anyBoolean())).thenReturn(empty());

            // Keycloak wraps group paths in slashes; a subject stored with them would
            // never match the token it is meant to match.
            sut.share(RESOURCE_ID, "team:/engineering/", "VIEW", true);

            verify(service).share(eq(RESOURCE_ID), eq(Subjects.team("engineering")), eq(AccessLevel.VIEW), eq(true));
        }

        @Test
        @DisplayName("an unknown prefix is refused rather than read as a name")
        void unknownPrefixRefused() {
            // "group:" is a plausible typo for "team:". Reading it as a user named
            // "group:engineering" would create a grant nobody holds, which is
            // indistinguishable from a successful share.
            var refusal = assertThrows(BadRequestException.class,
                    () -> sut.share(RESOURCE_ID, "group:engineering", "USE", true));

            assertTrue(refusal.getMessage().contains("user:"), "the refusal must say what IS accepted");
            verify(service, never()).share(anyString(), anyString(), any(), anyBoolean());
        }

        @Test
        @DisplayName("a prefix with nothing after it is refused")
        void emptyAfterPrefixRefused() {
            assertThrows(BadRequestException.class, () -> sut.share(RESOURCE_ID, "user:", "USE", true));
            assertThrows(BadRequestException.class, () -> sut.share(RESOURCE_ID, "user:   ", "USE", true));
            assertThrows(BadRequestException.class, () -> sut.share(RESOURCE_ID, "team:///", "USE", true));

            verify(service, never()).share(anyString(), anyString(), any(), anyBoolean());
        }

        @Test
        @DisplayName("a missing or blank subject is refused")
        void blankSubjectRefused() {
            assertThrows(BadRequestException.class, () -> sut.share(RESOURCE_ID, null, "USE", true));
            assertThrows(BadRequestException.class, () -> sut.share(RESOURCE_ID, "   ", "USE", true));

            verify(service, never()).share(anyString(), anyString(), any(), anyBoolean());
        }

        @Test
        @DisplayName("revoking normalises the subject the same way sharing does")
        void revokeNormalisesIdentically() {
            // If the two normalised differently, a grant could be created under one
            // spelling and be un-revokable under the other.
            when(service.revoke(anyString(), anyString(), anyBoolean())).thenReturn(empty());

            sut.revoke(RESOURCE_ID, "  alice@example.com  ", true);

            verify(service).revoke(eq(RESOURCE_ID), eq(Subjects.user("alice@example.com")), eq(true));
        }

        @Test
        @DisplayName("revoking refuses the same subjects sharing refuses")
        void revokeRefusesIdentically() {
            assertThrows(BadRequestException.class, () -> sut.revoke(RESOURCE_ID, "group:engineering", true));
            assertThrows(BadRequestException.class, () -> sut.revoke(RESOURCE_ID, null, true));

            verify(service, never()).revoke(anyString(), anyString(), anyBoolean());
        }
    }

    @Nested
    @DisplayName("level and visibility parsing")
    class Parsing {

        @Test
        @DisplayName("every level the API documents round-trips")
        void allLevelsAccepted() {
            when(service.share(anyString(), anyString(), any(), anyBoolean())).thenReturn(empty());

            for (AccessLevel level : AccessLevel.values()) {
                sut.share(RESOURCE_ID, "alice", level.name(), true);
                verify(service).share(eq(RESOURCE_ID), anyString(), eq(level), anyBoolean());
            }
        }

        @Test
        @DisplayName("an unknown level is refused, not silently downgraded")
        void unknownLevelRefused() {
            // A typo that became a weaker grant looks exactly like a successful share,
            // and a typo that became a stronger one is worse.
            var refusal = assertThrows(BadRequestException.class,
                    () -> sut.share(RESOURCE_ID, "alice", "ADMIN", true));

            assertTrue(refusal.getMessage().contains("USE"), "the refusal must list what is accepted");
            verify(service, never()).share(anyString(), anyString(), any(), anyBoolean());
        }

        @Test
        @DisplayName("every visibility the API documents round-trips")
        void allVisibilitiesAccepted() {
            when(service.setVisibility(anyString(), any(), anyBoolean())).thenReturn(empty());

            for (ResourceVisibility visibility : ResourceVisibility.values()) {
                sut.setVisibility(RESOURCE_ID, visibility.wireName(), true);
                verify(service).setVisibility(eq(RESOURCE_ID), eq(visibility), anyBoolean());
            }
        }

        @Test
        @DisplayName("an unknown visibility is refused rather than guessed at")
        void unknownVisibilityRefused() {
            // The three options differ on who can read the resource. Guessing between
            // them is not a recoverable mistake.
            assertThrows(BadRequestException.class, () -> sut.setVisibility(RESOURCE_ID, "world-readable", true));
            assertThrows(BadRequestException.class, () -> sut.setVisibility(RESOURCE_ID, null, true));

            verify(service, never()).setVisibility(anyString(), any(), anyBoolean());
        }

        @Test
        @DisplayName("'private' is the wire spelling, and the constant name is accepted as an alias")
        void privateHasTwoAcceptedSpellings() {
            // The enum constant is `privateAccess` because `private` is a Java keyword,
            // while the wire name is `private`. `parseOrNull` matches on both
            // deliberately, so a client that read the constant name out of generated
            // code is not punished for it. Pinned because the leniency is easy to lose
            // in a refactor, and losing it would 400 a request that used to work.
            when(service.setVisibility(anyString(), any(), anyBoolean())).thenReturn(empty());

            sut.setVisibility(RESOURCE_ID, "private", true);
            sut.setVisibility(RESOURCE_ID, "privateAccess", true);
            sut.setVisibility(RESOURCE_ID, "PRIVATE", true);

            verify(service, times(3)).setVisibility(eq(RESOURCE_ID), eq(ResourceVisibility.privateAccess), anyBoolean());
        }

        @Test
        @DisplayName("the leniency does not extend to something that is not a visibility")
        void leniencyHasLimits() {
            assertThrows(BadRequestException.class, () -> sut.setVisibility(RESOURCE_ID, "privat", true));
            assertThrows(BadRequestException.class, () -> sut.setVisibility(RESOURCE_ID, "public", true));

            verify(service, never()).setVisibility(anyString(), any(), anyBoolean());
        }
    }

    @Nested
    @DisplayName("cascade defaulting")
    class Cascade {

        @Test
        @DisplayName("an absent cascade means true — a half-shared agent is not a useful default")
        void absentCascadeIsTrue() {
            // An agent shared without its workflows and rule sets is a name pointing at
            // documents the recipient cannot open.
            when(service.share(anyString(), anyString(), any(), anyBoolean())).thenReturn(empty());

            sut.share(RESOURCE_ID, "alice", "USE", null);

            verify(service).share(anyString(), anyString(), any(), eq(true));
        }

        @Test
        @DisplayName("only an explicit false turns the cascade off")
        void explicitFalseHonoured() {
            when(service.share(anyString(), anyString(), any(), anyBoolean())).thenReturn(empty());

            sut.share(RESOURCE_ID, "alice", "USE", false);

            verify(service).share(anyString(), anyString(), any(), eq(false));
        }

        @Test
        @DisplayName("visibility and revoke default the same way")
        void otherOperationsDefaultIdentically() {
            when(service.revoke(anyString(), anyString(), anyBoolean())).thenReturn(empty());
            when(service.setVisibility(anyString(), any(), anyBoolean())).thenReturn(empty());

            sut.revoke(RESOURCE_ID, "alice", null);
            sut.setVisibility(RESOURCE_ID, "published", null);

            verify(service).revoke(anyString(), anyString(), eq(true));
            verify(service).setVisibility(anyString(), any(), eq(true));
        }
    }

    @Nested
    @DisplayName("ownership transfer")
    class Transfer {

        @Test
        @DisplayName("a transfer with no new owner is refused")
        void ownerlessTransferRefused() {
            assertThrows(BadRequestException.class, () -> sut.transferOwnership(RESOURCE_ID, null, null, true));
            assertThrows(BadRequestException.class, () -> sut.transferOwnership(RESOURCE_ID, "  ", null, true));

            verify(service, never()).transferOwnership(anyString(), anyString(), anyString(), anyBoolean());
        }

        @Test
        @DisplayName("the new owner is trimmed, because ownership is compared by string")
        void ownerIsTrimmed() {
            // An owner stored with surrounding whitespace would never match its own
            // principal again, and the new owner would silently not own it.
            when(service.transferOwnership(anyString(), anyString(), any(), anyBoolean())).thenReturn(empty());

            sut.transferOwnership(RESOURCE_ID, "  bob@example.com  ", null, true);

            verify(service).transferOwnership(eq(RESOURCE_ID), eq("bob@example.com"), any(), anyBoolean());
        }
    }

    @Test
    @DisplayName("reading the sharing state passes the service's answer through unchanged")
    void readSharesReturnsServiceAnswer() {
        var info = new ResourceSharingService.ShareInfo(RESOURCE_ID, "alice", "user:alice",
                ResourceVisibility.space.wireName(), List.of(), AccessLevel.OWN.name());
        when(service.describe(RESOURCE_ID)).thenReturn(info);

        var response = sut.readShares(RESOURCE_ID);

        assertEquals(200, response.getStatus());
        assertEquals(info, response.getEntity());
    }
}
