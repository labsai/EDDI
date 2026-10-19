/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import java.util.Locale;

/**
 * Resolves {@code eddi.conversation.max-in-flight-turns}: a number, or
 * {@code auto} (the default) to derive it from the JVM's maximum heap.
 * <p>
 * Turns run on virtual threads, so heap, not threads, bounds how many a node
 * can hold: each in-flight turn keeps its conversation memory and pipeline
 * data. A fixed default can only fit one container size. 1000 asked a 512 MB
 * heap (the Helm chart's 1 GiB limit at the image's default 50 %) for roughly
 * 1.2 GB, so the node would run out of memory before the limit refused
 * anything. {@code auto} scales with whatever heap the node actually has: the
 * maximum heap minus {@link #RESERVE_MB}, divided by the per-turn budget
 * {@code eddi.conversation.in-flight-turn-memory-mb}, and never below
 * {@link #MIN_AUTO}.
 * <p>
 * The per-turn budget defaults to 2 MB. Load tests measured about 1 MB per
 * in-flight turn for short conversations; the factor of two covers long
 * histories and tool payloads. {@link #RESERVE_MB} is what an idle node uses
 * (~180 MB measured) plus margin.
 */
public final class InFlightTurnLimit {

    /** The setting that derives the limit from the heap. */
    public static final String AUTO = "auto";
    /** Heap kept for everything that is not an in-flight turn. */
    static final long RESERVE_MB = 256;
    /** The smallest automatic limit, so a tiny heap still serves some turns. */
    static final int MIN_AUTO = 32;
    /**
     * Used when the JVM reports no heap bound (Runtime.maxMemory() ==
     * Long.MAX_VALUE).
     */
    static final int UNBOUNDED_HEAP_FALLBACK = 1000;

    private InFlightTurnLimit() {
    }

    /**
     * @param setting
     *            the property value: {@code auto} or an integer ({@code <= 0}
     *            disables the limit)
     * @param maxHeapBytes
     *            {@link Runtime#maxMemory()}
     * @param perTurnMb
     *            {@code eddi.conversation.in-flight-turn-memory-mb}
     * @throws IllegalArgumentException
     *             for a setting that is neither, or a per-turn budget below 1 MB
     */
    public static int resolve(String setting, long maxHeapBytes, int perTurnMb) {
        String value = setting == null ? AUTO : setting.trim();
        if (value.isEmpty() || AUTO.equals(value.toLowerCase(Locale.ROOT))) {
            return auto(maxHeapBytes, perTurnMb);
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "eddi.conversation.max-in-flight-turns must be 'auto' or an integer (<= 0 disables the limit), got '" + value + "'");
        }
    }

    static int auto(long maxHeapBytes, int perTurnMb) {
        if (perTurnMb < 1) {
            throw new IllegalArgumentException("eddi.conversation.in-flight-turn-memory-mb must be at least 1, got " + perTurnMb);
        }
        if (maxHeapBytes == Long.MAX_VALUE) {
            return UNBOUNDED_HEAP_FALLBACK;
        }
        long heapMb = maxHeapBytes / (1024L * 1024L);
        long limit = (heapMb - RESERVE_MB) / perTurnMb;
        return (int) Math.max(MIN_AUTO, Math.min(limit, Integer.MAX_VALUE));
    }
}
