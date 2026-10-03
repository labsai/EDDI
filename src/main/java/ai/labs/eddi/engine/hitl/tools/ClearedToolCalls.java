/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

/**
 * The tool calls a human has already decided on during one resumed turn, so the
 * gate does not pause on them a second time if the model reissues them.
 * <p>
 * A clearance used to be the bare call id. Ids are minted by the model
 * provider, and some OpenAI-compatible servers reuse them ({@code call_1} on
 * every turn) or send an empty string, so "this id was approved" let a
 * <em>different</em> call that happened to carry the same id run without anyone
 * seeing it. A clearance now binds to the triple a human actually approved —
 * call id, tool name and a canonical fingerprint of the arguments — and is
 * consumed by the first call that matches it: the approval was for one
 * execution, not for a standing permission. A blank id never clears anything.
 * <p>
 * Not thread-safe; one instance lives for one resumed tool loop.
 */
public final class ClearedToolCalls {

    private static final ObjectMapper CANONICAL = JsonMapper.builder()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build();

    /** Remaining clearances: key = id + name + args fingerprint, value = count. */
    private final Map<String, Integer> remaining = new HashMap<>();

    /** A set that clears nothing — the live path and every non-resume caller. */
    public static ClearedToolCalls none() {
        return new ClearedToolCalls();
    }

    /**
     * Records that a human approved (or the journal replayed) exactly this call.
     * Ignored for a blank id: such a call cannot be addressed again safely.
     */
    public void clear(String callId, String toolName, String arguments) {
        if (callId == null || callId.isBlank() || toolName == null) {
            return;
        }
        remaining.merge(key(callId, toolName, arguments), 1, Integer::sum);
    }

    /**
     * Whether {@code request} is one a human already cleared — and if so, uses the
     * clearance up. A request that differs in id, tool name or arguments from every
     * clearance is not cleared.
     */
    public boolean consume(ToolExecutionRequest request) {
        if (request == null || request.id() == null || request.id().isBlank() || request.name() == null) {
            return false;
        }
        String key = key(request.id(), request.name(), request.arguments());
        Integer count = remaining.get(key);
        if (count == null) {
            return false;
        }
        if (count <= 1) {
            remaining.remove(key);
        } else {
            remaining.put(key, count - 1);
        }
        return true;
    }

    /** Whether any clearance is left. */
    public boolean isEmpty() {
        return remaining.isEmpty();
    }

    private static String key(String callId, String toolName, String arguments) {
        return callId + '\u0000' + toolName + '\u0000' + argumentsFingerprint(arguments);
    }

    /**
     * SHA-256 over the arguments in canonical form: parsed as JSON and
     * re-serialized with sorted keys, so key order and whitespace — neither of
     * which changes what the call does — cannot make a reissued call look new.
     * Non-JSON arguments are hashed as given; null and blank are the same empty
     * argument list.
     */
    static String argumentsFingerprint(String arguments) {
        String canonical;
        if (arguments == null || arguments.isBlank()) {
            canonical = "";
        } else {
            try {
                JsonNode tree = CANONICAL.readTree(arguments);
                canonical = tree == null ? arguments : CANONICAL.writeValueAsString(CANONICAL.treeToValue(tree, Object.class));
            } catch (JsonProcessingException e) {
                canonical = arguments;
            }
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
