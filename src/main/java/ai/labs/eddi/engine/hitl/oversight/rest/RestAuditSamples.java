/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.rest;

import ai.labs.eddi.engine.hitl.oversight.OversightErrors;
import ai.labs.eddi.engine.hitl.oversight.samples.AuditSample;
import ai.labs.eddi.engine.hitl.oversight.samples.AuditSampleService;
import ai.labs.eddi.engine.hitl.oversight.samples.SampleFilter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Locale;

/**
 * @see IRestAuditSamples
 */
@ApplicationScoped
public class RestAuditSamples implements IRestAuditSamples {

    static final int MAX_PAGE_SIZE = 100;

    @Inject
    AuditSampleService samples;

    @Inject
    OversightAccess access;

    @Override
    public SamplePage listSamples(String agentId, String category, String status, String from, String to, Integer index, Integer limit) {
        String scoped = access.requireReadScope(agentId);
        AuditSample.Status wanted = status(status);
        int start = index == null ? 0 : index;
        if (start < 0) {
            throw OversightErrors.badRequest(OversightErrors.INVALID_PARAMETER, "index must be 0 or greater");
        }
        int size = limit == null ? 20 : Math.max(1, Math.min(MAX_PAGE_SIZE, limit));
        SampleFilter filter = new SampleFilter(scoped, OversightAccess.blankToNull(category), wanted, OversightWindow.parse("from", from, false),
                OversightWindow.parse("to", to, true));
        boolean admin = access.isAdmin();
        return new SamplePage(samples.count(filter), start, size,
                samples.list(filter, start, size).stream().map(s -> admin ? s : withoutUser(s)).toList());
    }

    @Override
    public PendingCount pendingCount(String agentId) {
        return new PendingCount(samples.count(SampleFilter.pending(access.requireReadScope(agentId))));
    }

    @Override
    public SampleDetail readSample(String sampleId) {
        AuditSample sample = load(sampleId);
        return new SampleDetail(access.isAdmin() ? sample : withoutUser(sample), samples.reasonCodes(sample));
    }

    @Override
    public AuditSample reviewSample(String sampleId, AuditSampleService.Verdict verdict) {
        AuditSample sample = load(sampleId);
        AuditSample reviewed = samples.review(sample, verdict, access.actor());
        return access.isAdmin() ? reviewed : withoutUser(reviewed);
    }

    private AuditSample load(String sampleId) {
        AuditSample sample = samples.read(sampleId)
                .orElseThrow(() -> OversightErrors.notFound(OversightErrors.SAMPLE_NOT_FOUND, "Sample not found"));
        access.requireReviewer(sample.agentId());
        return sample;
    }

    static AuditSample.Status status(String value) {
        String v = OversightAccess.blankToNull(value);
        if (v == null) {
            return AuditSample.Status.PENDING;
        }
        if ("ALL".equalsIgnoreCase(v)) {
            return null;
        }
        try {
            return AuditSample.Status.valueOf(v.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw OversightErrors.badRequest(OversightErrors.INVALID_PARAMETER, "status must be PENDING, OK, FLAGGED, EXPIRED or ALL");
        }
    }

    private static AuditSample withoutUser(AuditSample s) {
        return s.withColumns(null, s.reviewedBy(), s.status(), s.version());
    }
}
