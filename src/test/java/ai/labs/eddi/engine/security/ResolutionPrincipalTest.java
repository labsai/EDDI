/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import ai.labs.eddi.engine.security.ResolutionPrincipal.Provenance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@link ResolutionPrincipal} is worth for a credential decision.
 * <p>
 * {@code ConnectionResolver} releases a {@code PER_USER} grant on
 * {@link ResolutionPrincipal#isVerified()} alone, so every loosening of this
 * predicate is a path to handing one user's live SaaS tokens to whoever claimed
 * to be them.
 */
@DisplayName("ResolutionPrincipal — how much an identity is worth")
class ResolutionPrincipalTest {

    @Test
    @DisplayName("a null provenance counts as NOT verified, so conversations predating provenance are not grandfathered in")
    void nullProvenanceIsNotVerified() {
        // Stored conversations written before provenance existed deserialize with a
        // null here. Reading that as anything but "nobody authenticated this" would
        // make every legacy conversation a free PER_USER grant — and legacy is exactly
        // the population nothing is known about.
        assertFalse(new ResolutionPrincipal("alice", null).isVerified(),
                "an unknown provenance must never satisfy the verified check; treating null as 'not SELF_ASSERTED' "
                        + "would grandfather every pre-provenance conversation into another user's credentials");
        // Positive control on the same user id: only the provenance differs, so a
        // predicate that ignored provenance entirely could not pass both of these.
        assertTrue(new ResolutionPrincipal("alice", Provenance.VERIFIED).isVerified(),
                "an authenticated identity must still be usable, otherwise PER_USER connections resolve for nobody");
    }

    @Test
    @DisplayName("a self-asserted provenance counts as NOT verified")
    void selfAssertedIsNotVerified() {
        // The /v1 api-key shape: the shared key authenticated, the user id did not.
        assertFalse(new ResolutionPrincipal("alice", Provenance.SELF_ASSERTED).isVerified(),
                "a caller-supplied user id must not unlock that user's grants; the shared key proves nothing about "
                        + "which person is behind it");
    }

    @Test
    @DisplayName("a VERIFIED provenance without a usable user id is still NOT verified")
    void verifiedWithoutUserIdIsNotVerified() {
        // isVerified() is an AND, not just a provenance read: there has to be an id to
        // look a grant up under. A blank one would otherwise resolve grants stored
        // under the empty principal.
        assertFalse(new ResolutionPrincipal(null, Provenance.VERIFIED).isVerified(),
                "a verified provenance with no user id must not pass — there is no subject to resolve a grant for");
        assertFalse(new ResolutionPrincipal("   ", Provenance.VERIFIED).isVerified(),
                "a blank user id must not pass; grants would be looked up under a principal nobody owns");
        assertTrue(new ResolutionPrincipal("alice", Provenance.VERIFIED).isVerified(),
                "the same provenance with a real user id must pass, or the AND above would be vacuous");
    }

    @Test
    @DisplayName("hasUserId is false for null, empty and blank ids and true for a real one")
    void hasUserId() {
        assertFalse(new ResolutionPrincipal(null, Provenance.VERIFIED).hasUserId(),
                "a null user id must not be reported as present — callers branch on this to refuse a PER_USER grant");
        assertFalse(new ResolutionPrincipal("", Provenance.VERIFIED).hasUserId(),
                "an empty user id must not be reported as present");
        assertFalse(new ResolutionPrincipal("\t ", Provenance.VERIFIED).hasUserId(),
                "a whitespace-only user id must not be reported as present; isBlank, not isEmpty, is the check");
        assertTrue(new ResolutionPrincipal("alice", Provenance.SELF_ASSERTED).hasUserId(),
                "a real user id must be reported as present regardless of provenance — hasUserId asks whether there "
                        + "is a subject, not whether it was authenticated");
    }
}
