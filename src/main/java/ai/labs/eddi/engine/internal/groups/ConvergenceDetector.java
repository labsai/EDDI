/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ConvergenceConfig;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Decides whether a phase's repeats have stopped producing new positions (I2).
 * <p>
 * Two mechanisms, one exit path — see {@link ConvergenceConfig}'s Javadoc for
 * why they are gated differently. This class owns the <em>judgment</em>:
 * parsing a judge's verdict and applying the threshold. It does not make the
 * LLM call (that is {@code PhaseExecutionEngine}'s, which owns the member-turn
 * machinery) and it does not touch the discussion loop.
 * <p>
 * <b>The parse never converges on doubt.</b> Every failure mode — unparseable
 * output, a missing score, a score outside 0..1, a judge that errored — returns
 * "not converged", so the phase runs its remaining repeats exactly as it would
 * have without the feature. Converging on a malformed verdict would silently
 * truncate a discussion the operator paid for and asked to run; running one
 * extra round costs one round. The asymmetry is deliberate and is the reason
 * this class has no "assume converged" path anywhere.
 *
 * @author ginccc
 */
public final class ConvergenceDetector {

    private static final Logger LOGGER = Logger.getLogger(ConvergenceDetector.class);
    /**
     * {@code FAIL_ON_TRAILING_TOKENS} is load-bearing, not hygiene. Jackson's
     * default is to parse the first complete JSON value and ignore whatever follows
     * — so a judge returning two verdicts
     * ({@code [{"agreementScore":0.9},{"agreementScore":0.1}]}, whose brace
     * extraction yields two objects in a row) would silently converge on the first
     * and truncate the discussion, with no signal that a second, opposite verdict
     * was discarded. Failing the parse sends it down the "not converged" path
     * instead, which is the only defensible reading of an ambiguous verdict.
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /**
     * The judge's instruction. States the anti-sycophancy rule the plan calls for:
     * agreement means members hold the same position, not that they are being
     * polite to each other. Without that line an LLM judge reliably scores "we all
     * agree this is a great point" as convergence.
     */
    public static final String TEMPLATE_CONVERGENCE_JUDGE = """
            You are assessing whether a group discussion has CONVERGED.

            Compare the PREVIOUS round of contributions with the CURRENT round.

            Score substantive positional agreement ONLY:
            - Do members now hold the same position on the substantive question?
            - Have the disagreements from the previous round been resolved, or merely
              restated more politely?

            Explicitly DO NOT score:
            - stylistic similarity, tone, or politeness
            - members thanking, praising, or agreeing-to-agree with each other
            - repetition of the same disagreement in softer words

            Two members who still disagree have NOT converged, however courteously
            they express it.

            PREVIOUS ROUND:
            {previousRound}

            CURRENT ROUND:
            {currentRound}

            Respond with ONLY this JSON, no other text:
            {"agreementScore": <number between 0 and 1>, "converged": <true|false>, "summary": "<one sentence>"}
            """;

    private ConvergenceDetector() {
    }

    /**
     * The verdict for one repeat.
     *
     * @param converged
     *            whether the phase should stop repeating
     * @param agreementScore
     *            the judge's score, or {@code -1} when no judge ran (the
     *            deterministic path, or a parse failure)
     * @param summary
     *            one-line reason, suitable for the {@code CONVERGENCE} transcript
     *            entry and the SSE payload
     */
    public record ConvergenceVerdict(boolean converged, double agreementScore, String summary) {

        static ConvergenceVerdict notConverged(String summary) {
            return new ConvergenceVerdict(false, -1, summary);
        }
    }

    /**
     * The deterministic mechanism: every participant abstained this repeat, so
     * nobody had anything left to add.
     * <p>
     * <b>Cannot fire in production yet.</b> {@code ABSTAINED} entries are produced
     * by I4 (abstention), which has not landed — F4 added the type, not a producer.
     * This returns {@code false} for every real discussion today. Built and tested
     * with I2 rather than deferred to I4 because the two are independently useful,
     * and bolting a second exit mechanism onto a live one later is how the two end
     * up disagreeing about what "converged" means.
     * <p>
     * Requires an exact match against the participant count rather than "every
     * entry in the slice is an ABSTAINED" — a slice can be short (a member turn
     * that errored produces no entry; a mid-phase HITL resume only covers the
     * entries from this leg), and "all 1 of the 1 entries we happen to see is a
     * PASS" is not unanimity among five members.
     * <p>
     * Returns {@code false} for an empty roster: no participants cannot be
     * unanimous, and treating it as converged would let a misconfigured phase exit
     * as though it had achieved something.
     *
     * @param repeatEntries
     *            the transcript entries this repeat produced
     * @param participantCount
     *            how many speakers the phase resolved
     */
    public static boolean allParticipantsAbstained(List<TranscriptEntry> repeatEntries, int participantCount) {
        if (participantCount <= 0 || repeatEntries == null || repeatEntries.isEmpty()) {
            return false;
        }
        long abstained = repeatEntries.stream()
                .filter(e -> e != null && e.type() == TranscriptEntryType.ABSTAINED)
                .count();
        return abstained == participantCount;
    }

    /**
     * Renders {@link #TEMPLATE_CONVERGENCE_JUDGE} for one comparison. Plain string
     * substitution rather than the templating engine: the two substitutions are
     * transcript text this class already holds, and routing agent-authored
     * contributions through Qute would let a member's own output be interpreted as
     * template syntax.
     */
    public static String buildJudgeInput(List<TranscriptEntry> previousRound, List<TranscriptEntry> currentRound) {
        return TEMPLATE_CONVERGENCE_JUDGE
                .replace("{previousRound}", renderRound(previousRound))
                .replace("{currentRound}", renderRound(currentRound));
    }

    private static String renderRound(List<TranscriptEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "(no contributions)";
        }
        var sb = new StringBuilder();
        for (TranscriptEntry e : entries) {
            if (e == null || e.content() == null || e.content().isBlank()) {
                continue;
            }
            sb.append(e.speakerDisplayName() != null ? e.speakerDisplayName() : e.speakerAgentId())
                    .append(": ").append(e.content()).append("\n\n");
        }
        return sb.isEmpty() ? "(no contributions)" : sb.toString().stripTrailing();
    }

    /**
     * Three-tier parse of the judge's response, mirroring {@code TaskListParser}'s
     * structure: strict JSON, then brace extraction for output wrapped in prose or
     * a markdown fence, then give up — and giving up means <em>not converged</em>.
     *
     * @param threshold
     *            score at or above which the phase converges; compared with
     *            {@code >=} so a judge returning exactly the configured threshold
     *            converges, which is what an operator configuring 0.8 means
     */
    public static ConvergenceVerdict parseJudgeVerdict(String judgeOutput, double threshold) {
        if (judgeOutput == null || judgeOutput.isBlank()) {
            LOGGER.debug("Convergence judge returned no output — treating as not converged");
            return ConvergenceVerdict.notConverged("Convergence judge returned no output");
        }

        JsonNode node = tryReadJson(judgeOutput);
        if (node == null) {
            node = tryReadJson(extractBraces(judgeOutput));
        }
        if (node == null) {
            LOGGER.debugf("Convergence judge output was not parseable as JSON — treating as not converged");
            return ConvergenceVerdict.notConverged("Convergence judge output was not parseable");
        }

        JsonNode scoreNode = node.get("agreementScore");
        if (scoreNode == null || !scoreNode.isNumber()) {
            return ConvergenceVerdict.notConverged("Convergence judge returned no agreementScore");
        }
        double score = scoreNode.asDouble();
        if (score < 0.0 || score > 1.0) {
            // Out of contract. Clamping would invent a verdict the judge did not
            // give: a returned 4.2 is as likely to be a 0-10 scale as a runaway 1.0.
            LOGGER.debugf("Convergence judge returned an out-of-range agreementScore (%s) — treating as not converged", score);
            return ConvergenceVerdict.notConverged("Convergence judge returned an out-of-range score");
        }

        String summary = node.hasNonNull("summary") ? node.get("summary").asText() : null;
        // The score is authoritative, not the judge's own "converged" boolean: the
        // threshold is the operator's setting, and a model that returns
        // {"agreementScore": 0.3, "converged": true} must not override it.
        boolean converged = score >= threshold;
        String reason = "Agreement score %.2f (threshold %.2f)%s".formatted(score, threshold,
                summary != null && !summary.isBlank() ? " — " + summary : "");
        return new ConvergenceVerdict(converged, score, reason);
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
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : null;
    }
}
