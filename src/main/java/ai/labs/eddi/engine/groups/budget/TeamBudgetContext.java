/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.groups.budget;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.TeamBudgetConfig;
import ai.labs.eddi.utils.LogSanitizer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * The budget values a group member can template with (F6):
 * {@code {context.teamBudget}} (the member's own team) and
 * {@code {context.teamBudgets}} (the teams its group's {@code oversight.teams}
 * lists, for a "growth board").
 * <p>
 * Numbers, month, name and which limit binds -- never transcripts, adjustment
 * actors or reasons (a reason is free text and appears only on the REST
 * history, behind VIEW). Every listed team is filtered through the caller's
 * view check, the discussion owner's identity; a team the owner cannot see is
 * dropped, so a board can never show a number its author could not have read.
 * Cached per discussion for a short while so a turn costs no extra I/O.
 */
@ApplicationScoped
public class TeamBudgetContext {

    private static final Logger LOGGER = Logger.getLogger(TeamBudgetContext.class);

    static final long CACHE_MILLIS = 30_000L;
    static final int MAX_CACHED = 500;

    private record Cached(long expiresAtMillis, Map<String, Object> values) {
    }

    private final TeamBudgetService service;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    @Inject
    public TeamBudgetContext(TeamBudgetService service) {
        this(service, Clock.systemUTC());
    }

    TeamBudgetContext(TeamBudgetService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    /**
     * The template values for {@code gc}'s members: empty when the group has no
     * {@code teamBudget} or its budget cannot be read.
     *
     * @param mayView
     *            whether the discussion's owner may read a given group
     */
    public Map<String, Object> values(GroupConversation gc, Predicate<String> mayView) {
        // No state means no teamBudget on this root discussion: nothing to show, no
        // read.
        if (gc == null || gc.getGroupId() == null || gc.getTeamBudgetState() == null) {
            return Map.of();
        }
        long now = clock.millis();
        Cached cached = gc.getId() == null ? null : cache.get(gc.getId());
        if (cached != null && cached.expiresAtMillis() > now) {
            return cached.values();
        }
        Map<String, Object> values = compute(gc, mayView);
        if (gc.getId() != null) {
            if (cache.size() >= MAX_CACHED) {
                cache.values().removeIf(c -> c.expiresAtMillis() <= now);
                if (cache.size() >= MAX_CACHED) {
                    cache.clear();
                }
            }
            cache.put(gc.getId(), new Cached(now + CACHE_MILLIS, values));
        }
        return values;
    }

    private Map<String, Object> compute(GroupConversation gc, Predicate<String> mayView) {
        AgentGroupConfiguration config = service.config(gc.getGroupId());
        if (config == null || config.getTeamBudget() == null) {
            return Map.of();
        }
        try {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("teamBudget", view(service.snapshot(gc.getGroupId(), config)));
            TeamBudgetConfig.Oversight oversight = config.getTeamBudget().oversight();
            if (oversight != null && oversight.teams() != null && !oversight.teams().isEmpty()) {
                List<Map<String, Object>> board = new ArrayList<>();
                int dropped = 0;
                for (String team : oversight.teams()) {
                    if (team == null || !mayView.test(team)) {
                        dropped++;
                        continue;
                    }
                    AgentGroupConfiguration teamConfig = service.config(team);
                    if (teamConfig == null) {
                        dropped++;
                        continue;
                    }
                    board.add(view(service.snapshot(team, teamConfig)));
                }
                if (dropped > 0) {
                    LOGGER.debugf("Group %s: %d oversight team(s) left out of {context.teamBudgets} (not viewable by the owner, or gone)",
                            LogSanitizer.sanitize(gc.getGroupId()), dropped);
                }
                values.put("teamBudgets", board);
            }
            return values;
        } catch (TeamBudgetAccountingUnavailableException e) {
            LOGGER.warnf("Team budget values for group %s unavailable: %s", LogSanitizer.sanitize(gc.getGroupId()), e.getMessage());
            return Map.of();
        }
    }

    static Map<String, Object> view(TeamBudgetSnapshot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("groupId", s.groupId());
        m.put("name", s.groupName());
        m.put("month", s.month());
        m.put("capUsd", s.capUsd());
        m.put("spentUsd", s.spentUsd());
        m.put("remainingUsd", s.remainingUsd());
        m.put("exhausted", s.exhausted());
        m.put("limitedBy", s.limitedBy());
        m.put("resetsAt", s.resetsAt() == null ? null : s.resetsAt().toString());
        return m;
    }
}
