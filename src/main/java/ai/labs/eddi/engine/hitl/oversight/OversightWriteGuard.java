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
 * {@code /hitl/autonomy}, {@code /hitl/analytics/rebuild}) — and so is deciding
 * a spend-control change ({@code /administration/spend-control-changes}).</li>
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
    private static final Pattern SPEND_CONTROL_API = Pattern.compile("/administration/spend-control-changes(?:[/?#]|$)");
    private static final Pattern MATRIX_PARAMS = Pattern.compile(";[^/?#]*");
    private static final String HITL_CONFIG_KEY = "hitlConfig";
    private static final String OVERSIGHT_KEY = "oversight";

    public static final String OVERSIGHT_API_REASON = "an agent may not review audit samples or change earned autonomy (/hitl/samples, "
            + "/hitl/autonomy) — those are human actions";
    public static final String SPEND_CONTROL_API_REASON = "an agent may not decide a spend-control change "
            + "(/administration/spend-control-changes) — approving a looser price or quota is a human action";
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
        String routed = withoutMatrixParams(decodedUri);
        if (OVERSIGHT_API.matcher(routed).find()) {
            return OVERSIGHT_API_REASON;
        }
        if (SPEND_CONTROL_API.matcher(routed).find()) {
            return SPEND_CONTROL_API_REASON;
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

    /**
     * A path or URI without the matrix parameters of its segments ({@code /a;x=1/b}
     * → {@code /a/b}). JAX-RS routes on the segments alone, so a guard that matched
     * the raw path would let
     * {@code /administration/spend-control-changes;x=1/c1/approve} through to the
     * method it guards.
     */
    public static String withoutMatrixParams(String path) {
        return path == null ? null : MATRIX_PARAMS.matcher(path).replaceAll("");
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
