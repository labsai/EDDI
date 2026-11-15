/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr;

import ai.labs.eddi.engine.audit.AuditHmac;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Who GDPR erasure has erased, and since when — for projections that are
 * rebuilt from data erasure does not rewrite.
 * <p>
 * Approval analytics are a projection of the audit ledger. Erasure
 * pseudonymises the decision rows that exist, but a rebuild re-reads the
 * ledger, and a {@code hitl.approval} entry names its decider in
 * {@code output.decidedBy} while it is filed under the conversation owner's id:
 * erasing a decider who is not the owner leaves that entry as it was, and a
 * rebuild that re-inserts a row it had lost (retention, a failed write, a
 * pre-6.7 entry never imported) would name the erased person again. The
 * projector asks this record about every identity it stores and, for a decision
 * made no later than the erasure, stores what erasure would have left instead.
 * <p>
 * <b>Storage.</b> The source of truth is {@link IErasedSubjectStore}, shared by
 * every node. It holds a keyed one-way token per person
 * ({@link GdprPseudonymizer#stableTokenFor}, keyed under the per-deployment
 * salt so it survives audit-key rotation) and the time of their latest erasure
 * — never the raw id. Rows are kept indefinitely: they are a few bytes each and
 * the ledger they guard is kept for years.
 * <p>
 * <b>Cache.</b> Lookups are cached per node, bounded in size and for
 * {@value #CACHE_TTL_SECONDS} seconds. This node's own erasures invalidate its
 * entry at once; another node's erasure is seen at most that long later, and
 * erasure itself pseudonymises the rows already stored after recording here.
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class ErasedSubjects {

    static final long CACHE_TTL_SECONDS = 30;
    static final long CACHE_SIZE = 10_000;

    private final IErasedSubjectStore store;
    private final GdprPseudonymizer pseudonymizer;
    private final AuditLedgerService ledger;
    private final Cache<String, Optional<Instant>> cache = Caffeine.newBuilder().maximumSize(CACHE_SIZE)
            .expireAfterWrite(Duration.ofSeconds(CACHE_TTL_SECONDS)).build();

    @Inject
    public ErasedSubjects(IErasedSubjectStore store, GdprPseudonymizer pseudonymizer, AuditLedgerService ledger) {
        this.store = store;
        this.pseudonymizer = pseudonymizer;
        this.ledger = ledger;
    }

    /**
     * Records that {@code userId} was erased at {@code erasedAt}. Idempotent; a
     * later time widens the window.
     *
     * @throws RuntimeException
     *             the store could not be written — the caller must report the
     *             erasure incomplete
     */
    public void record(String userId, Instant erasedAt) {
        if (userId == null || erasedAt == null) {
            return;
        }
        store.record(pseudonymizer.stableTokenFor(userId), erasedAt);
        cache.invalidate(userId);
    }

    /**
     * When {@code identity} was last erased, or empty when never. Identities that
     * cannot be a person (null, {@code unknown}, {@code anonymous},
     * {@code system:*}) or are already a pseudonym are answered without a lookup.
     *
     * @throws RuntimeException
     *             the store could not be read: the answer is unknown, not "no"
     */
    public Optional<Instant> erasedAt(String identity) {
        if (!isPersonalIdentity(identity)) {
            return Optional.empty();
        }
        return cache.get(identity, this::lookup);
    }

    /**
     * Whether a record about {@code identity} made at {@code at} falls under an
     * erasure of that identity — true when they were erased at or after {@code at},
     * or {@code at} is unknown. A record made after the erasure is new data of a
     * returning user and is not touched.
     */
    public boolean covers(String identity, Instant at) {
        Optional<Instant> erased = erasedAt(identity);
        return erased.isPresent() && (at == null || !at.isAfter(erased.get()));
    }

    /**
     * The pseudonym erasure writes in place of {@code userId} — the audit ledger's,
     * as {@code OversightGdprParticipant} uses, so a rebuilt row and an erased one
     * name the person with the same token and aggregates stay correct.
     */
    public String pseudonymOf(String userId) {
        return ledger.pseudonymOf(userId);
    }

    private Optional<Instant> lookup(String identity) {
        Set<String> tokens = new LinkedHashSet<>();
        tokens.add(pseudonymizer.stableTokenFor(identity));
        // Written instead of the salted token when the salt was unavailable.
        tokens.add(AuditHmac.pseudonymFor(identity));
        return store.erasedAt(tokens);
    }

    static boolean isPersonalIdentity(String identity) {
        return identity != null && !identity.isBlank() && !"unknown".equals(identity) && !"anonymous".equals(identity)
                && !identity.startsWith("system:") && !identity.startsWith(AuditHmac.GDPR_PSEUDONYM_PREFIX);
    }
}
