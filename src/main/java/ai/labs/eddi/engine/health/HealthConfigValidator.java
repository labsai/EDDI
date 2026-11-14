/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.model.HealthConfig;
import ai.labs.eddi.engine.exception.ErrorResponses;
import ai.labs.eddi.engine.lifecycle.IConversation;
import jakarta.ws.rs.BadRequestException;

import java.util.Map;
import java.util.Set;

/**
 * Save-time checks of {@link HealthConfig}, run when an agent is created,
 * updated or duplicated through the agent store — the
 * {@code RatingConfigValidator} pattern — when an upgrade or a sync is about to
 * adopt the source agent's settings, before its first write
 * ({@code UpgradeExecutor}, via {@code RestAgentStore.validateSettings}), and
 * when a {@code create} or {@code merge} import reads the archive's agent,
 * before anything is written ({@code RestImportService}). The field shipped
 * with these checks, so on import they refuse only what no save ever accepted.
 * <b>A rule tightened later must not be applied to imports as a refusal</b>:
 * exports made before it would stop importing — report it instead. Whatever
 * still reaches the store unchecked (written before an import was checked, or
 * by hand) is read leniently: an unknown outcome is ignored and an out-of-range
 * window falls back to the deployment default ({@link HealthConfigPolicy}).
 */
public final class HealthConfigValidator {

    /** The most actions one agent may map to outcomes. */
    public static final int MAX_OUTCOME_MAPPINGS = 100;
    /** The longest action name a mapping may use (rows keep at most this much). */
    public static final int MAX_ACTION_LENGTH = HealthTurnFactory.MAX_ACTION_LENGTH;
    /** The shortest and longest inactivity window, in hours. */
    public static final int MIN_WINDOW_HOURS = 1;
    public static final int MAX_WINDOW_HOURS = 720;

    /**
     * Reserved actions that say nothing about how a conversation went: they start,
     * pause or continue one. {@code CONVERSATION_END} and {@code STOP_CONVERSATION}
     * may be mapped — they end a conversation, and mapping them says how.
     */
    static final Set<String> UNMAPPABLE_ACTIONS = Set.of(IConversation.CONVERSATION_START, IConversation.PAUSE_CONVERSATION,
            IConversation.CONTINUE_CONVERSATION);

    private HealthConfigValidator() {
    }

    /**
     * @throws BadRequestException
     *             naming the offending field, when the health configuration is
     *             invalid
     */
    public static void validate(AgentConfiguration configuration) {
        HealthConfig health = configuration == null ? null : configuration.getHealthConfig();
        if (health == null) {
            return;
        }
        Map<String, String> outcomes = health.getOutcomes();
        if (outcomes != null) {
            if (outcomes.size() > MAX_OUTCOME_MAPPINGS) {
                throw invalid("healthConfig.outcomes may map at most " + MAX_OUTCOME_MAPPINGS + " actions");
            }
            for (Map.Entry<String, String> entry : outcomes.entrySet()) {
                String action = entry.getKey();
                if (action == null || action.isBlank()) {
                    throw invalid("healthConfig.outcomes must not map an empty action name");
                }
                if (action.length() > MAX_ACTION_LENGTH) {
                    throw invalid("healthConfig.outcomes: action names must be at most " + MAX_ACTION_LENGTH + " characters");
                }
                if (UNMAPPABLE_ACTIONS.contains(action)) {
                    throw invalid("healthConfig.outcomes." + action + ": a reserved action that starts, pauses or continues a conversation "
                            + "cannot declare an outcome");
                }
                String outcome = entry.getValue();
                if (outcome == null || !HealthConfig.DECLARABLE_OUTCOMES.contains(outcome)) {
                    throw invalid("healthConfig.outcomes." + action + " must be one of " + String.join(", ", HealthConfig.DECLARABLE_OUTCOMES)
                            + " — was '" + outcome + "'");
                }
            }
        }
        Map<String, Integer> windows = health.getInactivityWindowHours();
        if (windows != null) {
            for (Map.Entry<String, Integer> entry : windows.entrySet()) {
                if (entry.getKey() == null || !HealthOrigins.ALL.contains(entry.getKey())) {
                    throw invalid("healthConfig.inactivityWindowHours: '" + entry.getKey() + "' is not an origin (" + String.join(", ",
                            HealthOrigins.ALL) + ")");
                }
                Integer hours = entry.getValue();
                if (hours == null || hours < MIN_WINDOW_HOURS || hours > MAX_WINDOW_HOURS) {
                    throw invalid("healthConfig.inactivityWindowHours." + entry.getKey() + " must be between " + MIN_WINDOW_HOURS + " and "
                            + MAX_WINDOW_HOURS + " hours");
                }
            }
        }
    }

    private static BadRequestException invalid(String message) {
        return new BadRequestException(message, ErrorResponses.badRequest(message));
    }
}
