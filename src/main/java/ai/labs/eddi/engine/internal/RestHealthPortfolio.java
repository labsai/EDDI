/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.api.IRestHealthPortfolio;
import ai.labs.eddi.engine.health.HealthAccess;
import ai.labs.eddi.engine.health.HealthPeriods;
import ai.labs.eddi.engine.health.HealthPortfolioService;
import ai.labs.eddi.engine.health.HealthReportService;
import ai.labs.eddi.engine.health.report.HealthReports.Portfolio;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * @see IRestHealthPortfolio
 */
@ApplicationScoped
public class RestHealthPortfolio implements IRestHealthPortfolio {

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 500;
    static final Set<String> SORTS = Set.of("attention", "name", "traffic");
    static final Set<String> STATUSES = Set.of("HEALTHY", "WATCH", "NEEDS_ATTENTION", "NOT_ENOUGH_DATA");

    private final HealthAccess access;
    private final HealthReportService reports;
    private final HealthPortfolioService portfolio;

    @Inject
    public RestHealthPortfolio(HealthAccess access, HealthReportService reports, HealthPortfolioService portfolio) {
        this.access = access;
        this.reports = reports;
        this.portfolio = portfolio;
    }

    @Override
    public Portfolio listAgentHealth(String period, String sort, List<String> statuses, Integer limit, String cursor) {
        HealthPeriods.Window window = HealthPeriods.window(period, null, null, "7d", reports.today());
        String order = sort == null || sort.isBlank() ? "attention" : sort.trim();
        if (!SORTS.contains(order)) {
            throw HealthPeriods.badRequest("sort must be attention, name or traffic — was '" + sanitize(order) + "'");
        }
        List<String> wanted = new ArrayList<>();
        if (statuses != null) {
            for (String status : statuses) {
                if (status == null || status.isBlank()) {
                    continue;
                }
                if (!STATUSES.contains(status.trim())) {
                    throw HealthPeriods.badRequest("status must be one of " + String.join(", ", STATUSES) + " — was '" + sanitize(status) + "'");
                }
                wanted.add(status.trim());
            }
        }
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw HealthPeriods.badRequest("limit must be between 1 and " + MAX_LIMIT + " — was " + size);
        }
        int offset = 0;
        if (cursor != null && !cursor.isBlank()) {
            try {
                offset = Integer.parseInt(cursor.trim());
            } catch (NumberFormatException e) {
                offset = -1;
            }
            if (offset < 0) {
                throw HealthPeriods.badRequest("cursor is not a cursor this endpoint returned");
            }
        }
        return portfolio.portfolio(access.viewableAgents(), window, order, wanted, size, offset);
    }
}
