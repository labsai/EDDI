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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

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
 * <b>Cache.</b> Only positive answers are cached (per node, bounded): an
 * erasure time only ever grows, so a cached one can be trusted for what it
 * covers. A "not erased" answer is never cached, and a cached time that does
 * not cover the record is re-read, so a stale answer can never let a raw id be
 * stored after another node's erasure.
 * <p>
 * <b>Ordering against erasure.</b> The projector checks an identity before it
 * inserts and, for a row it inserted with an identity kept, again after (a
 * fresh read), scrubbing the row if the second check covers it. Erasure records
 * the person before its participants pseudonymise the stored rows. So either
 * the second check ran before the record, and then so did the insert and the
 * participants' pass reaches the row, or it ran after and sees the record.
 * <p>
 * <b>Precision.</b> The stores keep milliseconds; a record's time is truncated
 * to milliseconds before it is compared, so a decision in the same millisecond
 * as the erasure is covered.
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class ErasedSubjects {

    static final long CACHE_TTL_MINUTES = 10;
    static final long CACHE_SIZE = 10_000;

    private final IErasedSubjectStore store;
    private final GdprPseudonymizer pseudonymizer;
    private final AuditLedgerService ledger;
    /** Positive answers only: identity -> latest erasure time seen. */
    private final Cache<String, Instant> erased = Caffeine.newBuilder().maximumSize(CACHE_SIZE)
            .expireAfterWrite(Duration.ofMinutes(CACHE_TTL_MINUTES)).build();

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
        erased.invalidate(userId);
    }

    /**
     * When {@code identity} was last erased, or empty when never, always read from
     * the store. Identities that cannot be a person (null, {@code unknown},
     * {@code anonymous}, {@code system:*}) or are already a pseudonym are answered
     * without a read.
     *
     * @throws RuntimeException
     *             the store or the token could not be read: the answer is unknown,
     *             not "no"
     */
    public Optional<Instant> erasedAt(String identity) {
        if (!isPersonalIdentity(identity)) {
            return Optional.empty();
        }
        Optional<Instant> fresh = store.erasedAt(List.of(pseudonymizer.stableTokenFor(identity)));
        fresh.ifPresent(at -> erased.put(identity, at));
        return fresh;
    }

    /**
     * Whether a record about {@code identity} made at {@code at} falls under an
     * erasure of that identity: true when they were erased at or after {@code at}
     * (compared in milliseconds), or {@code at} is unknown. A record made after the
     * erasure is new data of a returning user and is not touched. Answered from the
     * cache only when the cached erasure covers {@code at}; otherwise read from the
     * store.
     *
     * @throws RuntimeException
     *             the store or the token could not be read
     */
    public boolean covers(String identity, Instant at) {
        if (!isPersonalIdentity(identity)) {
            return false;
        }
        Instant when = at != null ? at.truncatedTo(ChronoUnit.MILLIS) : null;
        Instant cached = erased.getIfPresent(identity);
        if (cached != null && covered(when, cached)) {
            return true;
        }
        Optional<Instant> fresh = erasedAt(identity);
        return fresh.isPresent() && covered(when, fresh.get());
    }

    private static boolean covered(Instant at, Instant erasedAt) {
        return at == null || !at.isAfter(erasedAt);
    }

    /**
     * The pseudonym erasure writes in place of {@code userId} — the audit ledger's,
     * as {@code OversightGdprParticipant} uses, so a rebuilt row and an erased one
     * name the person with the same token and aggregates stay correct.
     */
    public String pseudonymOf(String userId) {
        return ledger.pseudonymOf(userId);
    }

    /**
     * Whether {@code identity} can name a person: not null, blank, {@code unknown},
     * {@code anonymous}, {@code system:*} or a pseudonym.
     */
    public static boolean isPersonalIdentity(String identity) {
        return identity != null && !identity.isBlank() && !"unknown".equals(identity) && !"anonymous".equals(identity)
                && !identity.startsWith("system:") && !identity.startsWith(AuditHmac.GDPR_PSEUDONYM_PREFIX);
    }
}
