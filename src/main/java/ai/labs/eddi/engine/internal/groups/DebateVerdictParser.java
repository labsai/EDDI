/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.GroupConversation.DecisionRecord;
import ai.labs.eddi.configs.groups.model.GroupConversation.DecisionType;
import ai.labs.eddi.configs.groups.model.GroupConversation.Dissent;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a debate judgment into a {@link DecisionRecord} (I3).
 * <p>
 * A DEBATE ends in a SYNTHESIS phase that judges a winner, but the judgment was
 * only ever prose — so a caller wanting to know who won had to parse English,
 * and nothing downstream (a router, a report, a test) could branch on the
 * result. This reads {@link DiscussionStylePresets#TEMPLATE_DEBATE_JUDGMENT}'s
 * JSON into the structured record instead.
 * <p>
 * <b>Every failure is prose-only, never a failed discussion.</b> Unparseable
 * output, a winner outside the contract, a missing field — all return
 * {@code type=NONE} carrying the original text in {@link DecisionRecord#raw()},
 * exactly as that record's Javadoc prescribes. The discussion already produced
 * its answer by the time this runs; losing the structured view of it is a
 * degradation, and failing the run over a malformed judge response would throw
 * away work the operator paid for.
 *
 * @author ginccc
 */
public final class DebateVerdictParser {

    private static final Logger LOGGER = Logger.getLogger(DebateVerdictParser.class);

    /** The mechanism tag recorded on every verdict this class produces. */
    public static final String METHOD = "debate-judgment";

    private static final String SIDE_PRO = "PRO";
    private static final String SIDE_CON = "CON";
    private static final String SIDE_TIE = "TIE";

    /**
     * {@code FAIL_ON_TRAILING_TOKENS} for the same reason
     * {@link ConvergenceDetector} enables it: Jackson otherwise parses the first
     * complete value and silently discards the rest, so a judge that emitted two
     * verdicts would have the first one recorded as though it were the only one. An
     * ambiguous judgment must fall to prose-only, not pick a side by position.
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private DebateVerdictParser() {
    }

    /**
     * Three-tier parse mirroring {@code TaskListParser} and
     * {@link ConvergenceDetector#parseJudgeVerdict}: strict JSON, then the object
     * embedded in prose or a markdown fence, then give up.
     * <p>
     * {@code existingDissents} are carried onto the returned record. The dissent
     * round normally runs after this and merges itself in, but a config that
     * produced dissents earlier must not have them dropped by a later verdict
     * overwriting the record wholesale.
     *
     * @param judgmentOutput
     *            the judge's raw response
     * @param phaseName
     *            the phase to attribute the decision to
     * @param existingDissents
     *            dissents already recorded on the discussion, may be {@code null}
     * @return always non-{@code null}; {@code type=VERDICT} on a clean parse,
     *         {@code type=NONE} with {@code raw} preserved otherwise
     */
    public static DecisionRecord parse(String judgmentOutput, String phaseName, List<Dissent> existingDissents) {
        List<Dissent> dissents = existingDissents != null ? List.copyOf(existingDissents) : List.of();

        JsonNode node = tryReadJson(judgmentOutput);
        if (node == null) {
            node = tryReadJson(extractBraces(judgmentOutput));
        }
        if (node == null) {
            LOGGER.debug("Debate judgment was not parseable as JSON — recording a prose-only conclusion");
            return proseOnly(judgmentOutput, phaseName, dissents);
        }

        String winner = normalizeWinner(node.path("winner").asText(null));
        if (winner == null) {
            LOGGER.debugf("Debate judgment declared no recognizable winner — recording a prose-only conclusion");
            return proseOnly(judgmentOutput, phaseName, dissents);
        }

        Map<String, Object> scores = readScores(node.get("scores"));
        String reasoning = node.hasNonNull("reasoning") ? node.get("reasoning").asText() : null;

        // A judge that names one side but scores the other higher has contradicted
        // itself. The declared winner stands — it is the judge's explicit answer to
        // the question asked, where the scores are supporting detail — but the
        // disagreement is worth a line when someone is debugging a surprising
        // verdict.
        warnIfScoresContradict(winner, scores);

        Map<String, Object> tally = scores.isEmpty() ? null : Map.copyOf(scores);
        return new DecisionRecord(DecisionType.VERDICT, renderOutcome(winner, scores, reasoning),
                // A tie has no winner, per DecisionRecord's contract. The tie itself is
                // not lost — it is stated in the outcome text.
                SIDE_TIE.equals(winner) ? null : winner,
                tally, dissents, METHOD, phaseName, judgmentOutput);
    }

    /**
     * Whether {@code decision} is a verdict this class parsed out of
     * {@code synthesisText} — the guard the answer-selection site needs before
     * preferring the rendered outcome over the transcript's own words. A later
     * SYNTHESIS phase that superseded the judgment will not match.
     */
    public static boolean isRenderedFrom(DecisionRecord decision, String synthesisText) {
        return decision != null
                && decision.type() == DecisionType.VERDICT
                && METHOD.equals(decision.method())
                && decision.outcome() != null
                && decision.raw() != null
                && decision.raw().equals(synthesisText);
    }

    private static DecisionRecord proseOnly(String judgmentOutput, String phaseName, List<Dissent> dissents) {
        return new DecisionRecord(DecisionType.NONE, null, null, null, dissents, METHOD, phaseName, judgmentOutput);
    }

    /** {@code null} for anything outside the three-value contract. */
    private static String normalizeWinner(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case SIDE_PRO, SIDE_CON, SIDE_TIE -> upper;
            default -> null;
        };
    }

    /**
     * Sides to 0..10 scores. An out-of-range or non-numeric score is dropped rather
     * than clamped — a returned 87 is as likely to be a percentage as a runaway 10,
     * and inventing which would put a number in the audit record the judge never
     * gave. Dropping the score does not drop the verdict: the winner is the
     * decision, the scores are detail.
     */
    private static Map<String, Object> readScores(JsonNode scoresNode) {
        Map<String, Object> scores = new LinkedHashMap<>();
        if (scoresNode == null || !scoresNode.isObject()) {
            return scores;
        }
        for (String side : List.of(SIDE_PRO, SIDE_CON)) {
            // Case-insensitive, because normalizeWinner already tolerates a
            // lowercase winner — a judge replying {"winner":"pro","scores":{"pro":8,
            // "con":6}} is a single consistent style, and accepting half of it
            // silently dropped the whole scoreboard.
            JsonNode value = findScore(scoresNode, side);
            if (value == null || !value.isNumber()) {
                LOGGER.debugf("Debate judgment returned no numeric %s score — dropping the scoreboard", side);
                continue;
            }
            double score = value.asDouble();
            if (score < 0.0 || score > 10.0) {
                LOGGER.debugf("Debate judgment returned an out-of-range %s score (%s) — dropping it", side, score);
                continue;
            }
            scores.put(side, score);
        }
        // Half a scoreboard is not a scoreboard: "PRO wins (PRO 8/10)" invites the
        // reader to assume CON scored lower when the judge never said so.
        return scores.size() == 2 ? scores : new LinkedHashMap<>();
    }

    /** Field lookup that tolerates the case the judge happened to use. */
    private static JsonNode findScore(JsonNode scoresNode, String side) {
        JsonNode exact = scoresNode.get(side);
        if (exact != null) {
            return exact;
        }
        var fields = scoresNode.fieldNames();
        while (fields.hasNext()) {
            String name = fields.next();
            if (side.equalsIgnoreCase(name)) {
                return scoresNode.get(name);
            }
        }
        return null;
    }

    private static void warnIfScoresContradict(String winner, Map<String, Object> scores) {
        if (scores.size() != 2) {
            return;
        }
        double pro = (double) scores.get(SIDE_PRO);
        double con = (double) scores.get(SIDE_CON);
        boolean contradicts = switch (winner) {
            case SIDE_PRO -> pro < con;
            case SIDE_CON -> con < pro;
            default -> pro != con;
        };
        if (contradicts) {
            LOGGER.debugf("Debate judgment declared '%s' but scored PRO %s / CON %s — keeping the declared winner", winner, pro, con);
        }
    }

    /**
     * The human-readable conclusion, which becomes the discussion's answer in place
     * of the raw JSON nobody asked to read.
     */
    private static String renderOutcome(String winner, Map<String, Object> scores, String reasoning) {
        var sb = new StringBuilder(SIDE_TIE.equals(winner) ? "Tie" : winner + " wins");
        if (scores.size() == 2) {
            sb.append(" (PRO ").append(formatScore(scores.get(SIDE_PRO)))
                    .append("/10, CON ").append(formatScore(scores.get(SIDE_CON))).append("/10)");
        }
        if (reasoning != null && !reasoning.isBlank()) {
            sb.append(" — ").append(reasoning.trim());
        }
        return sb.toString();
    }

    /** Locale-independent, and without the "8.0/10" that {@code %s} would give. */
    private static String formatScore(Object score) {
        double value = (double) score;
        return value == Math.rint(value)
                ? String.valueOf((long) value)
                : String.format(Locale.ROOT, "%.1f", value);
    }

    private static JsonNode tryReadJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(text);
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Tier 2: the JSON object embedded in prose or a markdown fence. */
    private static String extractBraces(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : null;
    }
}
