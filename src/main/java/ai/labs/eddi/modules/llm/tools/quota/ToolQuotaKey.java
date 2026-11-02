/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.quota;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Identifies one counter: one agent, one tool or pool, one subject, one window.
 *
 * @param agentId
 *            the agent (not its version: a new version continues the count)
 * @param counter
 *            {@code pool:<name>} for a pooled rule, otherwise
 *            {@code tool:<rule key>}
 * @param subject
 *            who is counted — normally the end user's id
 * @param windowStart
 *            start of the fixed window, in epoch seconds, aligned to the UTC
 *            epoch
 */
public record ToolQuotaKey(String agentId, String counter, String subject, long windowStart) {

    /**
     * A stable, bounded identifier for the key, for a store that wants one primary
     * key column. The parts are length-prefixed, so {@code ("a", "bc")} and
     * {@code ("ab", "c")} can never collide.
     */
    public String digest() {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (String part : new String[]{agentId, counter, subject, Long.toString(windowStart)}) {
                byte[] bytes = String.valueOf(part).getBytes(StandardCharsets.UTF_8);
                sha.update((bytes.length + ":").getBytes(StandardCharsets.US_ASCII));
                sha.update(bytes);
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
