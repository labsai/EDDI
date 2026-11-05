/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.rest;

import ai.labs.eddi.engine.hitl.oversight.OversightErrors;
import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalAnalyticsService;
import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalBackfillService;
import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalSummary;
import ai.labs.eddi.engine.hitl.oversight.analytics.DecisionFilter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * @see IRestApprovalAnalytics
 */
@ApplicationScoped
public class RestApprovalAnalytics implements IRestApprovalAnalytics {

    private static final Logger LOGGER = Logger.getLogger(RestApprovalAnalytics.class);
    static final int MAX_PAGE_SIZE = 100;
    static final Duration DEFAULT_REBUILD_SPAN = Duration.ofDays(90);

    @Inject
    ApprovalAnalyticsService analytics;

    @Inject
    ApprovalBackfillService backfill;

    @Inject
    OversightAccess access;

    Clock clock = Clock.systemUTC();

    @Override
    public ApprovalSummary.Result summarize(Query query, String groupBy) {
        DecisionFilter filter = filter(query);
        ApprovalAnalyticsService.GroupBy grouping;
        try {
            grouping = ApprovalAnalyticsService.GroupBy.parse(groupBy);
        } catch (IllegalArgumentException e) {
            throw OversightErrors.badRequest(OversightErrors.INVALID_PARAMETER, e.getMessage());
        }
        return analytics.summarize(filter, grouping);
    }

    @Override
    public DecisionPage listDecisions(Query query, Integer index, Integer limit) {
        DecisionFilter filter = filter(query);
        int start = index == null ? 0 : index;
        if (start < 0) {
            throw OversightErrors.badRequest(OversightErrors.INVALID_PARAMETER, "index must be 0 or greater");
        }
        int size = limit == null ? 20 : Math.max(1, Math.min(MAX_PAGE_SIZE, limit));
        return new DecisionPage(analytics.count(filter), start, size, analytics.decisions(filter, start, size, access.isAdmin()));
    }

    @Override
    public RebuildStatus rebuild(String from, String to) {
        Instant now = clock.instant();
        Instant end = OversightWindow.parse("to", to, true);
        Instant start = OversightWindow.parse("from", from, false);
        Instant effectiveEnd = end != null ? end : now;
        Instant effectiveStart = start != null ? start : effectiveEnd.minus(DEFAULT_REBUILD_SPAN);
        if (backfill.isRunning()) {
            return new RebuildStatus(false, true, effectiveStart.toString(), effectiveEnd.toString());
        }
        String actor = access.actor();
        LOGGER.infof("[HITL-OVERSIGHT] '%s' started an approval analytics rebuild for %s..%s", sanitize(actor), effectiveStart, effectiveEnd);
        Thread.ofVirtual().name("eddi-approval-analytics-rebuild").start(() -> backfill.rebuild(effectiveStart, effectiveEnd));
        return new RebuildStatus(true, false, effectiveStart.toString(), effectiveEnd.toString());
    }

    DecisionFilter filter(Query query) {
        Query q = query != null ? query : new Query();
        String groupId = OversightAccess.blankToNull(q.groupId);
        String agentId;
        if (groupId != null && OversightAccess.blankToNull(q.agentId) == null) {
            // Group decisions belong to no agent: reviewers and administrators only.
            if (!access.seesAll()) {
                throw OversightErrors.forbidden(OversightErrors.NOT_PERMITTED, "Group analytics need the eddi-approver or eddi-admin role");
            }
            agentId = null;
        } else {
            agentId = access.requireReadScope(q.agentId);
        }
        OversightWindow window = OversightWindow.of(q.from, q.to, clock.instant());
        return new DecisionFilter(agentId, groupId, OversightAccess.blankToNull(q.category), window.from(), window.to(),
                lower(q.surface), upper(q.pauseType), upper(q.channel), q.agentVersion);
    }

    private static String lower(String value) {
        String v = OversightAccess.blankToNull(value);
        return v != null ? v.toLowerCase(Locale.ROOT) : null;
    }

    private static String upper(String value) {
        String v = OversightAccess.blankToNull(value);
        return v != null ? v.toUpperCase(Locale.ROOT) : null;
    }
}
