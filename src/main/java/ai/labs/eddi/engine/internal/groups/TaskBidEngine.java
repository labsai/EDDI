/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.AssignmentMode;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TaskDefinition;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.SharedTaskList.AwardedBid;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CNP-lite bid parsing and awarding (I18). The planner cannot know members'
 * actual fit or load; the Contract Net Protocol's announce-bid-award loop maps
 * onto the existing wave scheduler: unassigned BID-mode tasks are announced to
 * eligible members in blind, parallel bid turns, and each task is awarded to
 * the highest self-assessed confidence.
 * <p>
 * The award is deterministic and never stalls a wave: ties break by speaking
 * order, and a task nobody bid on falls back to the ROLE assignment path. Parse
 * discipline mirrors {@link VoteTallyEngine}: three tiers, {@code
 * FAIL_ON_TRAILING_TOKENS}, and an unreadable reply simply casts no bids.
 *
 * @author ginccc
 */
public final class TaskBidEngine {

    private static final Logger LOGGER = Logger.getLogger(TaskBidEngine.class);

    /**
     * The auction runs only when it can beat its own overhead — see
     * {@link #auctionWorthwhile}.
     */
    static final int MIN_BIDDERS = 2;
    static final int MIN_TASKS = 2;

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private TaskBidEngine() {
    }

    /** One member's bid on one announced task. */
    public record Bid(String agentId, Integer speakingOrder, String taskSubject, double confidence,
            String estimatedComplexity, String rationale) {
    }

    /**
     * The effective assignment mode of a task (I18): the task's own mode wins, then
     * the group's {@code taskListConfig.assignmentMode()}, then ROLE — every config
     * that predates I18 resolves to ROLE.
     */
    public static AssignmentMode effectiveMode(TaskDefinition td, AgentGroupConfiguration config) {
        if (td != null && td.assignmentMode() != null) {
            return td.assignmentMode();
        }
        if (config != null && config.getTaskListConfig() != null && config.getTaskListConfig().assignmentMode() != null) {
            return config.getTaskListConfig().assignmentMode();
        }
        return AssignmentMode.ROLE;
    }

    /**
     * Whether announcing is worth its own overhead: with one bidder the winner is
     * predetermined, and with one task a role/round-robin assignment costs zero LLM
     * calls. The caller logs the skip — a silent cap reads as coverage.
     */
    public static boolean auctionWorthwhile(int eligibleBidders, int unassignedTasks) {
        return eligibleBidders >= MIN_BIDDERS && unassignedTasks >= MIN_TASKS;
    }

    /**
     * The blind bid prompt for one member: the announced tasks and the JSON
     * contract — deliberately NO transcript and NO peer bids ({@code
     * ContextScope.NONE} semantics; blindness is what makes the confidences
     * comparable).
     */
    public static String buildBidPrompt(List<TaskItem> tasks, GroupMember member, String question) {
        var sb = new StringBuilder();
        sb.append("A task force is working on:\n\"").append(question).append("\"\n\n");
        sb.append("The following tasks are open for assignment. As ").append(member.displayName())
                .append(", bid ONLY on tasks you are genuinely well-suited to execute.\n\n");
        for (TaskItem task : tasks) {
            sb.append("- \"").append(task.subject()).append("\": ").append(task.description()).append('\n');
        }
        sb.append(
                """

                        Reply with ONLY this JSON:
                        {"bids": [{"subject": "<exact task subject>", "confidence": 0.0-1.0, "estimatedComplexity": "XS|S|M|L", "rationale": "<one sentence>"}]}

                        Bid only where you add real capability — an inflated confidence wins you work you will fail at, on the record.""");
        return sb.toString();
    }

    /**
     * Three-tier parse of one member's bid reply. Unknown subjects are dropped (an
     * out-of-contract bid is not guessed onto a task), confidence is clamped to [0,
     * 1], and an unreadable reply casts no bids — never a stalled wave.
     */
    public static List<Bid> parseBids(String content, GroupMember member, Set<String> announcedSubjects) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        JsonNode node = readJson(content);
        if (node == null) {
            node = readJson(embeddedJson(content));
        }
        if (node == null || !node.path("bids").isArray()) {
            return List.of();
        }
        Map<String, String> canonicalBySubject = announcedSubjects.stream()
                .collect(Collectors.toMap(s -> s.toLowerCase(Locale.ROOT), s -> s, (a, b) -> a));
        List<Bid> bids = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode b : node.path("bids")) {
            String subject = b.path("subject").isTextual() ? b.path("subject").asText().trim() : null;
            if (subject == null || subject.isBlank()) {
                continue;
            }
            String canonical = canonicalBySubject.get(subject.toLowerCase(Locale.ROOT));
            if (canonical == null) {
                LOGGER.debugf("Bid from '%s' on unannounced task '%s' — dropped", member.agentId(), subject);
                continue;
            }
            if (!seen.add(canonical)) {
                continue; // first bid per task wins within one reply
            }
            double confidence = b.path("confidence").isNumber()
                    ? Math.max(0.0, Math.min(1.0, b.path("confidence").asDouble()))
                    : 0.0;
            String complexity = b.path("estimatedComplexity").isTextual() ? b.path("estimatedComplexity").asText() : null;
            String rationale = b.path("rationale").isTextual() ? b.path("rationale").asText() : null;
            bids.add(new Bid(member.agentId(), member.speakingOrder(), canonical, confidence, complexity, rationale));
        }
        return bids;
    }

    /**
     * Awards each announced task: highest confidence wins; a tie breaks
     * deterministically by speaking order (unset orders last, then agent id so two
     * unordered members still resolve identically on every pod). Tasks nobody bid
     * on are absent from the result — the caller falls back to ROLE.
     */
    public static Map<String, AwardedBid> award(List<TaskItem> tasks, List<Bid> allBids) {
        Map<String, List<Bid>> bidsByTask = allBids.stream().collect(Collectors.groupingBy(Bid::taskSubject));
        Map<String, AwardedBid> awards = new LinkedHashMap<>();
        for (TaskItem task : tasks) {
            List<Bid> bids = bidsByTask.getOrDefault(task.subject(), List.of());
            bids.stream()
                    .max(Comparator.comparingDouble(Bid::confidence)
                            .thenComparing(b -> b.speakingOrder() != null ? b.speakingOrder() : Integer.MAX_VALUE,
                                    Comparator.reverseOrder())
                            .thenComparing(Bid::agentId, Comparator.reverseOrder()))
                    .ifPresent(winner -> awards.put(task.id(),
                            new AwardedBid(winner.agentId(), winner.confidence(), winner.estimatedComplexity(),
                                    winner.rationale())));
        }
        return awards;
    }

    private static JsonNode readJson(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(content.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** JSON embedded in prose or a code fence — first balanced object. */
    private static String embeddedJson(String content) {
        if (content == null) {
            return null;
        }
        int start = content.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < content.length(); i++) {
            char c = content.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                inString = !inString;
            } else if (!inString) {
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return content.substring(start, i + 1);
                    }
                }
            }
        }
        return null;
    }
}
