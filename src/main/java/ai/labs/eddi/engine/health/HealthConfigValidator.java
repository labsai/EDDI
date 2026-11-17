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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Save-time checks of {@link HealthConfig}, run when an agent is created,
 * updated or duplicated through the agent store — the
 * {@code RatingConfigValidator} pattern. An agent that reached the store
 * another way (a ZIP import, an older version) is read leniently: an unknown
 * outcome is ignored and an out-of-range window falls back to the deployment
 * default ({@link HealthConfigPolicy}).
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

    /** The most {@code externalRefs} one agent may declare. */
    public static final int MAX_EXTERNAL_REFS = 10;
    /** The most event types {@code outcomeEvents} may declare. */
    public static final int MAX_OUTCOME_EVENTS = 50;
    /** A reference name: a property name, as {@code property.json} writes one. */
    public static final Pattern REF_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_.-]{0,63}$");
    /** A business event type ({@code order.placed}, {@code ticket:reopened}). */
    public static final Pattern EVENT_TYPE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.:/-]{0,127}$");

    /**
     * Words that make a reference name look like a personal identifier, matched as
     * whole words of the name (split at {@code _}, {@code -}, {@code .}, digits and
     * camelCase) — {@code customerEmail} and {@code phone_no} match,
     * {@code orderId} and {@code addressable} do not.
     */
    static final Set<String> IDENTIFIER_WORDS = Set.of("email", "mail", "phone", "mobile", "msisdn", "telephone", "tel", "fax", "ssn", "iban",
            "bic", "passport", "address", "street", "postcode", "zip", "zipcode", "dob", "birthday", "birthdate", "surname", "firstname",
            "lastname", "fullname", "username", "creditcard", "cardnumber", "pan", "taxid", "nino");
    /** Multi-word identifiers ({@code e_mail}, {@code first_name}, …). */
    static final List<List<String>> IDENTIFIER_PHRASES = List.of(List.of("e", "mail"), List.of("first", "name"), List.of("last", "name"),
            List.of("full", "name"), List.of("user", "name"), List.of("credit", "card"), List.of("card", "number"), List.of("tax", "id"),
            List.of("national", "id"), List.of("social", "security"), List.of("date", "of", "birth"), List.of("zip", "code"),
            List.of("post", "code"), List.of("ip", "address"));
    private static final Pattern WORD_BREAK = Pattern.compile("[^A-Za-z]+|(?<=[a-z])(?=[A-Z])");

    private HealthConfigValidator() {
    }

    /** The lower-case whole words of a reference name. */
    static List<String> words(String name) {
        List<String> words = new ArrayList<>();
        for (String word : WORD_BREAK.split(name)) {
            if (!word.isEmpty()) {
                words.add(word.toLowerCase(Locale.ROOT));
            }
        }
        return words;
    }

    /**
     * Whether a reference name looks like a personal identifier — refused unless
     * {@code hashRefs} is on.
     */
    public static boolean looksLikeIdentifier(String name) {
        if (name == null) {
            return false;
        }
        List<String> words = words(name);
        for (String word : words) {
            if (IDENTIFIER_WORDS.contains(word)) {
                return true;
            }
        }
        for (List<String> phrase : IDENTIFIER_PHRASES) {
            for (int i = 0; i + phrase.size() <= words.size(); i++) {
                if (words.subList(i, i + phrase.size()).equals(phrase)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The {@code externalRefs} no {@code property.json} of the agent's workflows
     * sets — a save-time <em>warning</em>, never a refusal: a reference can also be
     * set by an HTTP call's {@code postResponse} or the model, which the store
     * cannot see.
     *
     * @param propertyNames
     *            the property names the agent's property setters write
     */
    public static List<String> refsNotSet(AgentConfiguration configuration, Collection<String> propertyNames) {
        HealthConfig health = configuration == null ? null : configuration.getHealthConfig();
        if (health == null || health.getExternalRefs() == null || health.getExternalRefs().isEmpty()) {
            return List.of();
        }
        Set<String> set = propertyNames == null ? Set.of() : new HashSet<>(propertyNames);
        List<String> missing = new ArrayList<>();
        for (String ref : health.getExternalRefs()) {
            if (ref != null && !set.contains(ref) && !missing.contains(ref)) {
                missing.add(ref);
            }
        }
        return missing;
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
        validateRefs(health);
    }

    private static void validateRefs(HealthConfig health) {
        List<String> refs = health.getExternalRefs();
        if (refs != null) {
            if (refs.size() > MAX_EXTERNAL_REFS) {
                throw invalid("healthConfig.externalRefs may name at most " + MAX_EXTERNAL_REFS + " properties");
            }
            Set<String> seen = new HashSet<>();
            for (String ref : refs) {
                if (ref == null || !REF_NAME.matcher(ref).matches()) {
                    throw invalid("healthConfig.externalRefs: '" + ref + "' is not a property name (a letter, then letters, digits, _ . -, "
                            + "at most 64 characters)");
                }
                if (!seen.add(ref)) {
                    throw invalid("healthConfig.externalRefs names '" + ref + "' twice");
                }
                if (!health.isHashRefs() && looksLikeIdentifier(ref)) {
                    throw invalid("healthConfig.externalRefs: '" + ref + "' looks like a personal identifier — reference business objects "
                            + "(orderId, ticketId), or set healthConfig.hashRefs to store it only as a salted hash");
                }
            }
        }
        Map<String, String> events = health.getOutcomeEvents();
        if (events != null) {
            if (events.size() > MAX_OUTCOME_EVENTS) {
                throw invalid("healthConfig.outcomeEvents may declare at most " + MAX_OUTCOME_EVENTS + " event types");
            }
            for (Map.Entry<String, String> entry : events.entrySet()) {
                String type = entry.getKey();
                if (type == null || !EVENT_TYPE.matcher(type).matches()) {
                    throw invalid("healthConfig.outcomeEvents: '" + type + "' is not an event type (letters, digits, _ . : / -, at most 128 "
                            + "characters)");
                }
                String outcome = entry.getValue();
                if (outcome != null && !HealthConfig.NO_OUTCOME.equals(outcome) && !HealthConfig.DECLARABLE_OUTCOMES.contains(outcome)) {
                    throw invalid("healthConfig.outcomeEvents." + type + " must be one of " + String.join(", ", HealthConfig.DECLARABLE_OUTCOMES)
                            + " or " + HealthConfig.NO_OUTCOME + " — was '" + outcome + "'");
                }
            }
        }
    }

    private static BadRequestException invalid(String message) {
        return new BadRequestException(message, ErrorResponses.badRequest(message));
    }
}
