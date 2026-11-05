/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.samples;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.engine.hitl.oversight.OversightConfigLookup;
import ai.labs.eddi.engine.hitl.oversight.OversightErrors;
import ai.labs.eddi.engine.hitl.oversight.OversightLedger;
import ai.labs.eddi.engine.hitl.oversight.OversightMetrics;
import ai.labs.eddi.engine.hitl.oversight.autonomy.AutonomyService;
import ai.labs.eddi.engine.hitl.review.ReasonCodes;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * The review queue of the sampled audit: reads, and the one write — a human's
 * verdict on one sample. There is deliberately no bulk verdict: approving many
 * samples with one click is exactly the rubber-stamping the queue exists to
 * prevent.
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class AuditSampleService {

    private static final Logger LOGGER = Logger.getLogger(AuditSampleService.class);

    private final IAuditSampleStore store;
    private final OversightConfigLookup configLookup;
    private final AutonomyService autonomy;
    private final OversightLedger ledger;
    private final OversightMetrics metrics;
    private final Clock clock;

    @Inject
    public AuditSampleService(IAuditSampleStore store, OversightConfigLookup configLookup, AutonomyService autonomy, OversightLedger ledger,
            OversightMetrics metrics) {
        this(store, configLookup, autonomy, ledger, metrics, Clock.systemUTC());
    }

    /** Test seam: a fixed clock. */
    public AuditSampleService(IAuditSampleStore store, OversightConfigLookup configLookup, AutonomyService autonomy, OversightLedger ledger,
            OversightMetrics metrics, Clock clock) {
        this.store = store;
        this.configLookup = configLookup;
        this.autonomy = autonomy;
        this.ledger = ledger;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** A verdict request. */
    public record Verdict(String verdict, String reasonCode, String note, Long expectedVersion) {
    }

    public List<AuditSample> list(SampleFilter filter, int index, int limit) {
        return store.query(filter, index, limit);
    }

    public long count(SampleFilter filter) {
        return store.count(filter);
    }

    public Optional<AuditSample> read(String id) {
        return store.read(id);
    }

    /** The reason codes a reviewer may flag a sample of this agent with. */
    public List<Map<String, String>> reasonCodes(AuditSample sample) {
        return ReasonCodes.asList(configuredCodes(sample));
    }

    /**
     * Records a human's verdict on a sample, compare-and-set from PENDING.
     *
     * @param reviewer
     *            the caller — never taken from the body
     */
    public AuditSample review(AuditSample sample, Verdict verdict, String reviewer) {
        if (verdict == null || verdict.verdict() == null) {
            throw OversightErrors.badRequest(OversightErrors.INVALID_PARAMETER, "verdict is required: OK or FLAGGED");
        }
        AuditSample.Status status;
        switch (verdict.verdict().trim().toUpperCase(Locale.ROOT)) {
            case "OK" -> status = AuditSample.Status.OK;
            case "FLAGGED" -> status = AuditSample.Status.FLAGGED;
            default -> throw OversightErrors.badRequest(OversightErrors.INVALID_PARAMETER, "verdict must be OK or FLAGGED");
        }
        if (verdict.expectedVersion() == null) {
            throw OversightErrors.badRequest(OversightErrors.INVALID_PARAMETER, "expectedVersion is required (the version you reviewed)");
        }
        String reasonCode = blankToNull(verdict.reasonCode());
        if (status == AuditSample.Status.FLAGGED) {
            if (reasonCode == null) {
                throw OversightErrors.badRequest(OversightErrors.UNKNOWN_REASON_CODE, "A flagged sample needs a reasonCode");
            }
            try {
                ReasonCodes.require(reasonCode, configuredCodes(sample), "reasonCode");
            } catch (IllegalArgumentException unknown) {
                throw OversightErrors.badRequest(OversightErrors.UNKNOWN_REASON_CODE, unknown.getMessage());
            }
        } else {
            reasonCode = null;
        }
        String note = blankToNull(verdict.note());
        if (note != null && note.codePointCount(0, note.length()) > AuditSample.MAX_NOTE_LENGTH) {
            throw OversightErrors.badRequest(OversightErrors.NOTE_TOO_LONG, "The note may be at most " + AuditSample.MAX_NOTE_LENGTH + " characters");
        }
        Instant now = clock.instant();
        // The expiry sweep marks a sample EXPIRED once its deadline is past; until it
        // runs, the same
        // boundary applies here, so a late FLAGGED verdict cannot reach earned
        // autonomy.
        if (sample.reviewDueAt() != null && now.isAfter(sample.reviewDueAt())) {
            throw OversightErrors.conflict(OversightErrors.SAMPLE_ALREADY_REVIEWED, "The sample is past its review deadline; reload it");
        }
        AuditSample reviewed = sample.reviewed(status, reasonCode, note, reviewer, now);
        AuditSample stored = store.review(reviewed, verdict.expectedVersion())
                .orElseThrow(() -> OversightErrors.conflict(OversightErrors.SAMPLE_ALREADY_REVIEWED,
                        "The sample was reviewed, expired or changed meanwhile; reload it"));
        metrics.sampleReviewed(status.name());
        var details = new LinkedHashMap<String, Object>();
        details.put("sampleId", stored.id());
        details.put("category", stored.category());
        details.put("verdict", status.name());
        if (reasonCode != null) {
            details.put("reasonCode", reasonCode);
        }
        details.put("argsDigest", stored.argsDigest());
        details.put("toolName", stored.toolName());
        ledger.write(OversightLedger.SAMPLE_REVIEW, stored.conversationId(), stored.agentId(), stored.agentVersion(), reviewer, details);
        if (status == AuditSample.Status.FLAGGED) {
            try {
                autonomy.onSampleFlagged(stored);
            } catch (RuntimeException e) {
                LOGGER.errorf("A flagged sample of agent %s could not be evaluated for suspension: %s", sanitize(stored.agentId()),
                        e.getMessage());
            }
        }
        return stored;
    }

    private Map<String, String> configuredCodes(AuditSample sample) {
        AgentConfiguration.HitlConfig hitl = configLookup.hitlConfig(sample.agentId(), sample.agentVersion());
        return hitl != null && hitl.getReview() != null ? hitl.getReview().getReasonCodes() : null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
