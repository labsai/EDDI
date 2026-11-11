/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The oversight half of the self-ungating guard: what an agent's own approved
 * tool call may never do to the oversight of agents.
 * <ol>
 * <li>Write to the oversight API at all — sample verdicts, apply, dismiss,
 * suspend, lift and rebuild are human actions ({@code /hitl/samples},
 * {@code /hitl/autonomy}, {@code /hitl/analytics/rebuild}), and deciding a
 * paused conversation ({@code /agents/{id}/resume}, a group's {@code approve}
 * and {@code human-input}): a resume verdict is evidence toward earned
 * autonomy.</li>
 * <li>Change an agent's {@code hitlConfig.oversight} (sampled audit and
 * autonomy policy) through a full-document write: an agent could otherwise
 * lower its own sample rate or loosen its own policy. A JSON-patch naming
 * {@code hitlConfig} is already refused by the guard as a whole.</li>
 * </ol>
 * Called from {@code SelfUngatingGuard.refusal} for non-read methods only.
 *
 * @since 6.7.0
 */
public final class OversightWriteGuard {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern OVERSIGHT_API = Pattern.compile("/hitl/(?:samples|autonomy|analytics/rebuild)(?:[/?#]|$)");
    /** The endpoints that record a human's decision on a HITL pause. */
    private static final Pattern DECISION_API = Pattern
            .compile("/(?:agents/[^?#]+/resume(?:/stream)?|groups/[^?#]+/conversations/[^?#]+/(?:approve(?:/stream)?|human-input))(?:[/?#;]|$)");
    private static final String HITL_CONFIG_KEY = "hitlConfig";
    private static final String OVERSIGHT_KEY = "oversight";

    public static final String OVERSIGHT_API_REASON = "an agent may not review audit samples or change earned autonomy (/hitl/samples, "
            + "/hitl/autonomy) — those are human actions";
    public static final String DECISION_API_REASON = "an agent may not decide a paused conversation (resume, approve, human-input) "
            + "— that is a human action";
    public static final String OVERSIGHT_CONFIG_REASON = "an agent may not change an agent's oversight policy (hitlConfig.oversight)";

    private OversightWriteGuard() {
    }

    /** Looks up the stored {@code hitlConfig} of an agent (null when none). */
    @FunctionalInterface
    public interface StoredHitlConfig {
        JsonNode hitlConfigOf(String agentId) throws Exception;
    }

    /**
     * The refusal of a non-read request, or null.
     *
     * @param decodedUri
     *            the request URI, decoded and lower-cased
     * @param body
     *            the request body, or null
     * @param agentStoreTarget
     *            the pattern of an agent-store target; group 1 is the agent id
     * @param stored
     *            how to read the target's stored {@code hitlConfig}; null when it
     *            cannot be read (the caller then refuses any body naming
     *            {@code hitlConfig} already)
     */
    public static String refusal(String decodedUri, String body, Pattern agentStoreTarget, StoredHitlConfig stored) {
        if (decodedUri == null) {
            return null;
        }
        if (OVERSIGHT_API.matcher(decodedUri).find()) {
            return OVERSIGHT_API_REASON;
        }
        if (DECISION_API.matcher(decodedUri).find()) {
            return DECISION_API_REASON;
        }
        Matcher target = agentStoreTarget != null ? agentStoreTarget.matcher(decodedUri) : null;
        String agentStoreTargetId = target != null && target.find() ? target.group(1) : null;
        if (agentStoreTargetId == null || stored == null || body == null || body.isBlank()) {
            return null;
        }
        JsonNode tree;
        try {
            tree = MAPPER.readTree(body);
        } catch (JsonProcessingException e) {
            return null;
        }
        if (tree == null || !tree.isObject()) {
            return null;
        }
        JsonNode written = tree.path(HITL_CONFIG_KEY).path(OVERSIGHT_KEY);
        JsonNode storedOversight;
        try {
            JsonNode storedHitl = stored.hitlConfigOf(agentStoreTargetId);
            storedOversight = storedHitl == null ? null : storedHitl.path(OVERSIGHT_KEY);
        } catch (Exception e) {
            return written.isMissingNode() || written.isNull() ? null : OVERSIGHT_CONFIG_REASON;
        }
        return same(storedOversight, written) ? null : OVERSIGHT_CONFIG_REASON;
    }

    private static boolean same(JsonNode stored, JsonNode written) {
        boolean storedAbsent = stored == null || stored.isMissingNode() || stored.isNull();
        boolean writtenAbsent = written == null || written.isMissingNode() || written.isNull();
        if (storedAbsent || writtenAbsent) {
            return storedAbsent && writtenAbsent;
        }
        return stored.equals(written);
    }
}
