/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.OptionsSource;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.VoteConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.VoteMethod;
import ai.labs.eddi.configs.groups.model.GroupConversation.DecisionRecord;
import ai.labs.eddi.configs.groups.model.GroupConversation.DecisionType;
import ai.labs.eddi.configs.groups.model.GroupConversation.Dissent;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses ballots and produces the vote's {@link DecisionRecord} (I14).
 * <p>
 * LLM ballots are correlated (shared priors, sycophancy), so what this class
 * protects is the <b>auditable process artifact</b>: the weighted tally, every
 * raw ballot, and the losing side's statements as dissents. Epistemics are not
 * claimed. Every parse failure degrades honestly — an unreadable reply is a
 * non-ballot that counts against quorum, never a guessed vote.
 * <p>
 * Mirrors {@link DebateVerdictParser}'s discipline: three-tier parse (strict
 * JSON → JSON embedded in prose/fence → give up), {@code
 * FAIL_ON_TRAILING_TOKENS} so a reply carrying two ballots is ambiguous rather
 * than first-wins, and no failure mode that kills the discussion.
 *
 * @author ginccc
 */
public final class VoteTallyEngine {

    private static final Logger LOGGER = Logger.getLogger(VoteTallyEngine.class);

    /** The mechanism tag on a decision produced purely by counting ballots. */
    public static final String METHOD_VOTE = "vote";
    /** The mechanism tag when a moderator broke the tie (or quorum failure). */
    public static final String METHOD_TIEBREAK = "vote+moderator-tiebreak";

    /** {@code Option A: text} / {@code Option 2 - text} lines in a synthesis. */
    private static final Pattern OPTION_LINE = Pattern.compile("^\\s*Option\\s+([A-Za-z0-9]+)\\s*[:\\-]\\s*(.+?)\\s*$",
            Pattern.MULTILINE);
    /** A ballot voting by label ("Option A") instead of the option's text. */
    private static final Pattern OPTION_LABEL = Pattern.compile("^\\s*Option\\s+([A-Za-z0-9]+)\\s*$", Pattern.CASE_INSENSITIVE);

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private VoteTallyEngine() {
    }

    /** One parsed ballot. {@code votes} hold canonical option texts. */
    public record Ballot(String agentId, String displayName, List<String> votes, Double confidence, String statement) {
    }

    /**
     * The tally's verdict on what happened, before any tie policy runs.
     *
     * @param decision
     *            the record to store when {@code unresolvedOptions} is empty — a
     *            decided VOTE, or a {@code NONE} record explaining why not
     * @param unresolvedOptions
     *            non-empty when a tie policy must choose: the tied options, or
     *            every option on a quorum failure
     * @param quorumReached
     *            whether enough valid ballots arrived
     */
    public record TallyOutcome(DecisionRecord decision, List<String> unresolvedOptions, boolean quorumReached) {
    }

    /**
     * The ballot options: {@code EXPLICIT} takes the config verbatim;
     * {@code LAST_SYNTHESIS} extracts {@code Option X: …} lines from the newest
     * SYNTHESIS entry. An empty result means the vote cannot run.
     */
    public static List<String> resolveOptions(VoteConfig config, List<TranscriptEntry> transcript) {
        if (config.optionsSource() == OptionsSource.EXPLICIT) {
            return config.options();
        }
        for (int i = transcript.size() - 1; i >= 0; i--) {
            TranscriptEntry entry = transcript.get(i);
            if (entry != null && entry.type() == TranscriptEntryType.SYNTHESIS && entry.content() != null) {
                List<String> options = new ArrayList<>();
                Matcher matcher = OPTION_LINE.matcher(entry.content());
                while (matcher.find()) {
                    options.add(matcher.group(2).trim());
                }
                return options;
            }
        }
        return List.of();
    }

    /** The JSON contract line rendered into the ballot prompt. */
    public static String ballotContract(VoteMethod method) {
        return method == VoteMethod.APPROVAL
                ? "{\"votes\": [\"<exact option text>\", \"...\"], \"confidence\": <0..1>, \"statement\": \"<one sentence explaining your vote>\"}"
                : "{\"vote\": \"<exact option text>\", \"confidence\": <0..1>, \"statement\": \"<one sentence explaining your vote>\"}";
    }

    /**
     * Three-tier ballot parse. Returns {@code null} for a non-ballot — which counts
     * against quorum, never as a guessed vote.
     */
    static Ballot parseBallot(TranscriptEntry entry, List<String> options, VoteMethod method) {
        if (entry == null || entry.content() == null || entry.content().isBlank()) {
            return null;
        }
        String content = entry.content();

        JsonNode node = readJson(content);
        if (node == null) {
            node = readJson(embeddedJson(content));
        }
        if (node != null && node.isObject()) {
            List<String> votes = new ArrayList<>();
            if (method == VoteMethod.APPROVAL && node.path("votes").isArray()) {
                for (JsonNode vote : node.path("votes")) {
                    String canonical = canonicalOption(vote.asText(), options);
                    if (canonical == null) {
                        return null; // an out-of-contract vote makes the whole ballot unreadable
                    }
                    if (!votes.contains(canonical)) {
                        votes.add(canonical);
                    }
                }
            } else if (node.path("vote").isTextual()) {
                String canonical = canonicalOption(node.path("vote").asText(), options);
                if (canonical == null) {
                    return null;
                }
                votes.add(canonical);
            }
            if (!votes.isEmpty()) {
                Double confidence = node.path("confidence").isNumber()
                        ? Math.max(0.0, Math.min(1.0, node.path("confidence").asDouble()))
                        : null;
                String statement = node.path("statement").isTextual() ? node.path("statement").asText() : null;
                return new Ballot(entry.speakerAgentId(), entry.speakerDisplayName(), List.copyOf(votes), confidence, statement);
            }
        }

        // Tier 2: exactly ONE option's text appears in the reply. Two or more is
        // ambiguous — refusing to pick by position is the point of this tier.
        String scanned = null;
        String lower = content.toLowerCase(Locale.ROOT);
        for (String option : options) {
            if (lower.contains(option.toLowerCase(Locale.ROOT))) {
                if (scanned != null) {
                    return null;
                }
                scanned = option;
            }
        }
        if (scanned != null) {
            return new Ballot(entry.speakerAgentId(), entry.speakerDisplayName(), List.of(scanned), null, null);
        }
        return null;
    }

    /**
     * Weighted tally over one VOTE phase repeat.
     *
     * @param voteEntries
     *            the repeat's VOTE transcript entries (one per participant that
     *            replied)
     * @param participantCount
     *            the quorum denominator — everyone who was ASKED, so abstentions
     *            and unparseable replies count against quorum
     */
    public static TallyOutcome tally(List<TranscriptEntry> voteEntries, int participantCount, List<String> options,
                                     VoteConfig config, String phaseName) {
        List<Ballot> ballots = new ArrayList<>();
        for (TranscriptEntry entry : voteEntries) {
            Ballot ballot = parseBallot(entry, options, config.method());
            if (ballot != null) {
                ballots.add(ballot);
            } else if (entry != null) {
                LOGGER.debugf("Non-ballot reply from '%s' counts against quorum", entry.speakerAgentId());
            }
        }

        Map<String, Double> totals = new LinkedHashMap<>();
        for (String option : options) {
            totals.put(option, 0.0);
        }
        List<Map<String, Object>> rawBallots = new ArrayList<>();
        for (Ballot ballot : ballots) {
            double weight = config.weights().getOrDefault(ballot.agentId(), 1.0);
            if (config.weightByConfidence() && ballot.confidence() != null) {
                weight *= ballot.confidence();
            }
            for (String vote : ballot.votes()) {
                totals.merge(vote, weight, Double::sum);
            }
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("agentId", ballot.agentId());
            raw.put("votes", ballot.votes());
            if (ballot.confidence() != null) {
                raw.put("confidence", ballot.confidence());
            }
            raw.put("weight", weight);
            if (ballot.statement() != null && !ballot.statement().isBlank()) {
                raw.put("statement", ballot.statement());
            }
            rawBallots.add(raw);
        }

        Map<String, Object> tallyMap = new LinkedHashMap<>();
        tallyMap.put("totals", totals);
        tallyMap.put("ballots", rawBallots);
        tallyMap.put("participants", participantCount);
        tallyMap.put("validBallots", ballots.size());
        tallyMap.put("quorum", config.quorum());

        boolean quorumReached = participantCount > 0 && (double) ballots.size() / participantCount >= config.quorum();
        tallyMap.put("quorumReached", quorumReached);

        if (!quorumReached) {
            var decision = new DecisionRecord(DecisionType.NONE,
                    "Quorum not reached: %d of %d participants cast a valid ballot (needed %.0f%%)."
                            .formatted(ballots.size(), participantCount, config.quorum() * 100),
                    null, tallyMap, List.of(), METHOD_VOTE, phaseName, null);
            return new TallyOutcome(decision, List.copyOf(options), false);
        }

        double max = totals.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        List<String> leaders = totals.entrySet().stream()
                .filter(e -> e.getValue() == max && max > 0.0)
                .map(Map.Entry::getKey).toList();

        if (leaders.size() != 1) {
            var decision = new DecisionRecord(DecisionType.NONE,
                    leaders.isEmpty() ? "No option received any weighted vote." : "Tie between: " + String.join(" / ", leaders) + ".",
                    null, tallyMap, List.of(), METHOD_VOTE, phaseName, null);
            return new TallyOutcome(decision, leaders.isEmpty() ? List.copyOf(options) : leaders, true);
        }

        String winner = leaders.get(0);
        var decision = new DecisionRecord(DecisionType.VOTE,
                "\"%s\" wins with %.2f of %.2f weighted votes.".formatted(winner, totals.get(winner),
                        totals.values().stream().mapToDouble(Double::doubleValue).sum()),
                winner, tallyMap, losingDissents(ballots, winner), METHOD_VOTE, phaseName, null);
        return new TallyOutcome(decision, List.of(), true);
    }

    /**
     * The minority report: losing-side ballots that carried a statement become the
     * decision's dissents.
     */
    private static List<Dissent> losingDissents(List<Ballot> ballots, String winner) {
        List<Dissent> dissents = new ArrayList<>();
        for (Ballot ballot : ballots) {
            if (!ballot.votes().contains(winner) && ballot.statement() != null && !ballot.statement().isBlank()) {
                dissents.add(new Dissent(ballot.agentId(), ballot.displayName(), ballot.statement()));
            }
        }
        return dissents;
    }

    /**
     * A tiebreaker's free-text choice, resolved by the same exact-scan rule as
     * ballot tier 2: exactly one option named, or no choice at all.
     */
    public static String resolveChoice(String reply, List<String> options) {
        if (reply == null || reply.isBlank()) {
            return null;
        }
        String canonical = canonicalOption(reply.trim(), options);
        if (canonical != null) {
            return canonical;
        }
        String lower = reply.toLowerCase(Locale.ROOT);
        String scanned = null;
        for (String option : options) {
            if (lower.contains(option.toLowerCase(Locale.ROOT))) {
                if (scanned != null) {
                    return null;
                }
                scanned = option;
            }
        }
        return scanned;
    }

    /**
     * Matches a vote string to its canonical option — by text, or by "Option X"
     * label position.
     */
    private static String canonicalOption(String vote, List<String> options) {
        if (vote == null) {
            return null;
        }
        String trimmed = vote.trim();
        for (String option : options) {
            if (option.equalsIgnoreCase(trimmed)) {
                return option;
            }
        }
        Matcher label = OPTION_LABEL.matcher(trimmed);
        if (label.matches()) {
            String index = label.group(1).toUpperCase(Locale.ROOT);
            int position = index.length() == 1 && Character.isLetter(index.charAt(0))
                    ? index.charAt(0) - 'A'
                    : parseIndex(index);
            if (position >= 0 && position < options.size()) {
                return options.get(position);
            }
        }
        return null;
    }

    private static int parseIndex(String index) {
        try {
            return Integer.parseInt(index) - 1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static JsonNode readJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(text.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** The first balanced-looking JSON object embedded in prose or a fence. */
    private static String embeddedJson(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        return start >= 0 && end > start ? content.substring(start, end + 1) : null;
    }
}
