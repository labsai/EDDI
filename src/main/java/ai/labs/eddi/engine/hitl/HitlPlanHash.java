/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.TreeMap;

import static ai.labs.eddi.engine.memory.MemoryKeys.ACTIONS;
import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * The plan hash of a pending approval: a SHA-256 over a canonical description
 * of exactly what the approval will let run, so that an approval binds to that
 * plan and nothing else. The pattern is inspect → plan hash → apply only with
 * that hash.
 * <p>
 * The hash is computed when the pause is committed and stored with it
 * ({@code hitlPlanHash} on the conversation, {@code planHash} on a tool batch).
 * {@code approval-status} reports it; a decision may carry it back
 * ({@link HitlDecision#getPlanHash()}). The resume refuses a decision whose
 * hash differs from the stored one, and re-derives the hash from the plan it is
 * about to execute and refuses if THAT differs from the stored one — the two
 * checks together mean what runs is what the reviewer was shown.
 * <p>
 * <b>Format.</b> {@code v1:} followed by 64 lower-case hex digits. The prefix
 * names the canonicalization, so a future change to it can still verify pauses
 * committed under the old one.
 * <p>
 * <b>What is hashed</b> — a JSON document, canonicalized as below:
 * <ul>
 * <li><b>Tool-call pause</b>: {@code kind=TOOL_CALL}, the conversation id, the
 * agent id and version, the batch's {@code pauseEpoch} (a fresh id per pause,
 * which the tool loop still holds once the bookmark is cleared), a random
 * per-pause nonce, and for every pending call in batch order (the order
 * approved calls run in): {@code callId}, {@code toolName}, {@code source},
 * {@code argsTruncated}, the canonicalized arguments, and the resolved-request
 * fingerprint where the call was pinned to one.</li>
 * <li><b>Rule (turn-level) pause</b>: there are no arguments, so the plan is
 * what the rest of the turn acts on — {@code kind=RULE}, the conversation id,
 * agent id and version, the pause id, the paused workflow and task index, and
 * the paused step's actions in order, without {@code PAUSE_CONVERSATION} (the
 * resume strips that action itself).</li>
 * <li><b>Group pause</b> ({@link #forGroup}): the group conversation and group
 * ids, the pause id, type and phase, the paused phase's transcript entries, the
 * tasks awaiting approval and the synthesized answer.</li>
 * </ul>
 * <b>Canonicalization.</b> Object keys sorted by UTF-16 code unit (as RFC 8785
 * sorts them); arrays keep their order; no insignificant whitespace; strings as
 * parsed (so a JSON escape sequence and the literal character it stands for
 * hash alike, but no Unicode normalization is applied — a precomposed and a
 * decomposed character are different strings to the tool and therefore
 * different plans); numbers by VALUE: {@code 1}, {@code 1.0}, {@code 1e0} and
 * {@code 1.00} are all {@code 1}, {@code 0.10} is {@code 0.1}, {@code -0} is
 * {@code 0}. Value equality is deliberate: a store or a provider that
 * re-serializes the arguments must not turn a pure re-encoding into a refused
 * approval. Arguments that are not exactly one valid JSON document (they repeat
 * a key, or carry anything after the first value) are hashed as their exact
 * text instead.
 * <p>
 * <b>Why a nonce.</b> The tool-call hash covers the RAW arguments and is served
 * next to the REDACTED ones; a plain digest would let anyone holding both guess
 * a redacted value offline (the reason the batch {@code fingerprint} and the
 * per-call request fingerprint are never served). The nonce is stored on the
 * batch and never leaves the server. Rule and group plans contain nothing the
 * approver is not already shown, so they carry none.
 */
public final class HitlPlanHash {

    /** The current canonicalization version — the hash's prefix. */
    public static final String VERSION_PREFIX = "v1:";
    /**
     * Hard cap on what a caller may send as a plan hash; anything longer is wrong.
     */
    public static final int MAX_LENGTH = 128;

    private static final Logger LOGGER = Logger.getLogger(HitlPlanHash.class);

    private static final ObjectMapper PARSER = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build())
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            // One document, nothing after it: otherwise {"amount":1} {"amount":9999}
            // would parse as its first value and hash like {"amount":1}, leaving a
            // trailing change outside the plan.
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final ObjectMapper WRITER = new ObjectMapper();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final SecureRandom RANDOM = new SecureRandom();
    /**
     * Beyond this many digits either side of the point a number keeps its (still
     * canonical, after stripping trailing zeros) scientific form instead of being
     * expanded — {@code 1e999999} expanded would be a megabyte of zeros.
     */
    private static final int MAX_PLAIN_DIGITS = 400;

    private HitlPlanHash() {
    }

    /** A fresh per-pause nonce: 128 random bits, URL-safe base64. */
    public static String newNonce() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Binds the pause the memory holds to its plan, as the pause is committed: a
     * tool batch gets its nonce (once), then the hash is stored on the memory (what
     * approval-status reports and a decision is compared with) and, for a tool
     * pause, on the batch too (what the tool loop re-derives before it executes an
     * approved call — the bookmark is cleared by then).
     * <p>
     * Call it after the turn's secret scrub has rewritten the batch and before the
     * memory is stored: the hash must cover exactly what the store keeps. Never
     * throws — a pause whose hash cannot be computed is decided as before plan
     * hashing, and says so in the log.
     *
     * @return the stored plan hash, or null
     */
    public static String commit(IConversationMemory memory) {
        if (memory == null) {
            return null;
        }
        try {
            PendingToolCallBatch batch = memory.getHitlPendingToolCalls();
            boolean toolPause = "TOOL_CALL".equals(memory.getHitlPauseType()) && batch != null;
            if (toolPause && (batch.getPlanNonce() == null || batch.getPlanNonce().isBlank())) {
                batch.setPlanNonce(newNonce());
            }
            String planHash = forPause(memory);
            memory.setHitlPlanHash(planHash);
            if (toolPause) {
                batch.setPlanHash(planHash);
            }
            return planHash;
        } catch (RuntimeException failed) {
            LOGGER.warnf("Could not compute the plan hash for the pause of conversation %s: %s — it will be decided without plan "
                    + "binding", sanitize(memory.getConversationId()), sanitize(failed.getMessage()));
            memory.setHitlPlanHash(null);
            return null;
        }
    }

    /**
     * The plan hash of the pause the memory currently holds: a tool batch when the
     * pause type is {@code TOOL_CALL}, otherwise the rule pause's bookmark and
     * actions. Null when the memory holds no pause.
     */
    public static String forPause(IConversationMemory memory) {
        if (memory == null || memory.getHitlPausedAt() == null) {
            return null;
        }
        String pauseId = HitlDecision.pauseIdOf(memory.getHitlPausedAt());
        if ("TOOL_CALL".equals(memory.getHitlPauseType())) {
            return forToolBatch(memory.getConversationId(), memory.getAgentId(), memory.getAgentVersion(), memory.getHitlPendingToolCalls());
        }
        return forRulePause(memory.getConversationId(), memory.getAgentId(), memory.getAgentVersion(), pauseId,
                memory.getHitlPausedWorkflowId(), memory.getHitlPausedAbsoluteTaskIndex(), currentActions(memory));
    }

    /**
     * The hash of a tool-call batch. The batch's {@code pauseEpoch} identifies the
     * pause, so this can be re-derived after the bookmark is cleared.
     */
    public static String forToolBatch(String conversationId, String agentId, Integer agentVersion, PendingToolCallBatch batch) {
        if (batch == null) {
            return null;
        }
        ObjectNode doc = header("TOOL_CALL");
        doc.put("conversationId", conversationId);
        doc.put("agentId", agentId);
        putInteger(doc, "agentVersion", agentVersion);
        doc.put("pauseEpoch", batch.getPauseEpoch());
        doc.put("nonce", batch.getPlanNonce());
        ArrayNode calls = doc.putArray("calls");
        if (batch.getCalls() != null) {
            for (PendingToolCallBatch.PendingToolCall call : batch.getCalls()) {
                if (call == null) {
                    calls.addNull();
                    continue;
                }
                ObjectNode c = calls.addObject();
                c.put("callId", call.getCallId());
                c.put("toolName", call.getToolName());
                c.put("source", call.getSource());
                c.put("argsTruncated", call.isArgsTruncated());
                putArguments(c, call.getArgumentsRaw());
                c.put("requestFingerprint", call.getRequestFingerprint());
            }
        }
        return digest(doc);
    }

    /** The hash of a rule (turn-level) pause. */
    public static String forRulePause(String conversationId, String agentId, Integer agentVersion, String pauseId, String workflowId,
                                      int absoluteTaskIndex, List<String> actions) {
        ObjectNode doc = header("RULE");
        doc.put("conversationId", conversationId);
        doc.put("agentId", agentId);
        putInteger(doc, "agentVersion", agentVersion);
        doc.put("pauseId", pauseId);
        doc.put("workflowId", workflowId);
        doc.put("taskIndex", absoluteTaskIndex);
        ArrayNode list = doc.putArray("actions");
        if (actions != null) {
            for (String action : actions) {
                if (!IConversation.PAUSE_CONVERSATION.equals(action)) {
                    list.add(action);
                }
            }
        }
        return digest(doc);
    }

    /**
     * The hash of a group pause, derived from the stored group conversation. A
     * group plan is not stored separately: while the discussion is
     * {@code AWAITING_APPROVAL} nothing it covers can change except through the
     * approval itself, so the value read at approval-status time and the value
     * re-derived when the decision arrives agree exactly when the reviewer saw the
     * current plan. Null when the conversation is not paused.
     */
    public static String forGroup(GroupConversation gc) {
        if (gc == null || gc.getPausedAt() == null) {
            return null;
        }
        ObjectNode doc = header("GROUP");
        doc.put("groupConversationId", gc.getId());
        doc.put("groupId", gc.getGroupId());
        doc.put("pauseId", HitlDecision.pauseIdOf(gc.getPausedAt()));
        doc.put("pauseType", gc.getHitlPauseType() != null ? gc.getHitlPauseType().name() : null);
        doc.put("phaseIndex", gc.getPausedAtPhaseIndex());
        doc.put("phaseName", gc.getPausedPhaseName());
        ArrayNode transcript = doc.putArray("transcript");
        List<GroupConversation.TranscriptEntry> entries;
        synchronized (gc.getTranscript()) {
            entries = new ArrayList<>(gc.getTranscript());
        }
        for (GroupConversation.TranscriptEntry entry : entries) {
            if (entry == null || entry.phaseIndex() != gc.getPausedAtPhaseIndex()) {
                continue;
            }
            ObjectNode e = transcript.addObject();
            e.put("speaker", entry.speakerAgentId());
            e.put("type", entry.type() != null ? entry.type().name() : null);
            e.put("content", entry.content());
        }
        ArrayNode tasks = doc.putArray("tasks");
        if (gc.getTaskList() != null) {
            List<SharedTaskList.TaskItem> awaiting = new ArrayList<>();
            for (SharedTaskList.TaskItem task : gc.getTaskList().getTasks()) {
                if (task != null && task.status() == SharedTaskList.TaskStatus.AWAITING_APPROVAL) {
                    awaiting.add(task);
                }
            }
            awaiting.sort(Comparator.comparing(SharedTaskList.TaskItem::id, Comparator.nullsFirst(Comparator.naturalOrder())));
            for (SharedTaskList.TaskItem task : awaiting) {
                ObjectNode t = tasks.addObject();
                t.put("id", task.id());
                t.put("subject", task.subject());
                t.put("assignedAgentId", task.assignedAgentId());
                t.put("result", task.result());
            }
        }
        doc.put("synthesizedAnswer", gc.getSynthesizedAnswer());
        return digest(doc);
    }

    /**
     * Whether two plan hashes are the same, in constant time. Null or blank on
     * either side is never a match.
     */
    public static boolean matches(String expected, String presented) {
        if (expected == null || presented == null || expected.isBlank() || presented.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(expected.trim().getBytes(StandardCharsets.UTF_8),
                presented.trim().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The canonical form of a JSON text, as hashed: see the class comment.
     *
     * @throws JsonProcessingException
     *             when the text is not one JSON document, or repeats a key
     */
    public static String canonicalJson(String json) throws JsonProcessingException {
        JsonNode node = PARSER.readTree(json);
        if (node == null || node.isMissingNode()) {
            throw new JsonProcessingException("empty JSON text") {
            };
        }
        StringBuilder out = new StringBuilder(json.length());
        write(node, out);
        return out.toString();
    }

    private static ObjectNode header(String kind) {
        ObjectNode doc = NODES.objectNode();
        doc.put("v", 1);
        doc.put("kind", kind);
        return doc;
    }

    private static void putInteger(ObjectNode doc, String field, Integer value) {
        if (value == null) {
            doc.putNull(field);
        } else {
            doc.put(field, value.intValue());
        }
    }

    /**
     * The arguments as a canonical JSON value under {@code arguments}, or — when
     * they are not one valid JSON document — as their exact text under
     * {@code argumentsText}. Two keys, so a text that happens to look like the
     * canonical form of something else cannot collide with it.
     */
    private static void putArguments(ObjectNode call, String raw) {
        if (raw == null) {
            call.putNull("arguments");
            return;
        }
        try {
            JsonNode parsed = PARSER.readTree(raw);
            if (parsed != null && !parsed.isMissingNode()) {
                call.set("arguments", parsed);
                return;
            }
        } catch (JsonProcessingException notJson) {
            // fall through: hashed as text
        }
        call.put("argumentsText", raw);
    }

    private static List<String> currentActions(IConversationMemory memory) {
        try {
            var step = memory.getCurrentStep();
            if (step == null) {
                return List.of();
            }
            IData<List<String>> data = step.getLatestData(ACTIONS);
            return data != null && data.getResult() != null ? data.getResult() : List.of();
        } catch (RuntimeException noStep) {
            return List.of();
        }
    }

    private static String digest(JsonNode doc) {
        StringBuilder canonical = new StringBuilder(256);
        write(doc, canonical);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return VERSION_PREFIX + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static void write(JsonNode node, StringBuilder out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            out.append("null");
        } else if (node.isObject()) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                sorted.put(name, node.get(name));
            }
            out.append('{');
            boolean first = true;
            for (var entry : sorted.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeString(entry.getKey(), out);
                out.append(':');
                write(entry.getValue(), out);
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(node.get(i), out);
            }
            out.append(']');
        } else if (node.isNumber()) {
            out.append(canonicalNumber(node.decimalValue()));
        } else if (node.isBoolean()) {
            out.append(node.booleanValue() ? "true" : "false");
        } else if (node.isTextual()) {
            writeString(node.textValue(), out);
        } else {
            // Binary/POJO nodes never come out of a parse; hash their text form.
            writeString(node.asText(), out);
        }
    }

    private static void writeString(String value, StringBuilder out) {
        try {
            out.append(WRITER.writeValueAsString(TextNode.valueOf(value)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot encode a string", e);
        }
    }

    /** A number by value — see the class comment. */
    static String canonicalNumber(BigDecimal value) {
        if (value.signum() == 0) {
            return "0";
        }
        BigDecimal stripped = value.stripTrailingZeros();
        int integerDigits = stripped.precision() - stripped.scale();
        if (stripped.scale() <= MAX_PLAIN_DIGITS && integerDigits <= MAX_PLAIN_DIGITS) {
            return stripped.toPlainString();
        }
        return stripped.toString();
    }
}
