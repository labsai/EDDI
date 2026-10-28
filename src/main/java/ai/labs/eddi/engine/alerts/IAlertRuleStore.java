/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import ai.labs.eddi.engine.alerts.model.AlertRule;

import java.util.List;
import java.util.Optional;

/**
 * Storage of {@link AlertRule}s — MongoDB ({@code alertrules}) or PostgreSQL
 * ({@code alert_rules}), selected by {@code DataStoreProducers}. Rules are not
 * versioned: a rule is operational configuration of the deployment, like a
 * schedule, not part of an agent.
 * <p>
 * Access control is the caller's business; the store answers what it is asked.
 */
public interface IAlertRuleStore {

    /** Stores a new rule under a generated id and returns it with that id. */
    AlertRule create(AlertRule rule);

    Optional<AlertRule> read(String id);

    /** Replaces the rule; {@code false} when no rule has the id. */
    boolean update(AlertRule rule);

    boolean delete(String id);

    /**
     * Rules newest first. With {@code agentId}, the rules whose scope lists that
     * agent (not the every-agent rules) — only those listing it alone when
     * {@code onlyThatAgent}; without, all of them.
     */
    List<AlertRule> list(String agentId, boolean onlyThatAgent, int offset, int limit);

    long count(String agentId, boolean onlyThatAgent);

    /** The enabled rules watching {@code agentId}: listing it, or every agent. */
    List<AlertRule> findEnabledForAgent(String agentId);

    /** Every enabled rule — for the digest pass. */
    List<AlertRule> findEnabled();

    /**
     * GDPR erasure: forget {@code userId} as the author or last editor of rules.
     */
    long clearAuthor(String userId);

    /**
     * GDPR export: the rules {@code userId} created or last edited, newest first,
     * at most {@code limit}.
     */
    List<AlertRule> findByAuthor(String userId, int limit);
}
