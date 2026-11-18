/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs;

/**
 * Marks a write of a configuration that exists already, rather than one an
 * author is saving now: an import, an instance-to-instance sync or upgrade, a
 * duplicate.
 * <p>
 * The save-time checks that were added after such configurations may have been
 * written (a ruleset's dependency graph, an output set's {@code timesOccurred})
 * refuse an interactive save (REST, MCP, the Manager). A document that imported
 * and ran before must still import, so inside this scope the same checks only
 * log a WARN naming the problem.
 * <p>
 * A {@link ScopedValue}: the binding ends with the call and cannot leak onto a
 * pooled thread; the writes it wraps run synchronously on the caller's thread.
 * Open PR #1417 adds {@code ExistingRuleSetWrite}, the same idea for its own
 * retired-config checks; once both exist, {@link #isActive()} should also
 * answer for it.
 */
public final class LenientConfigWrite {

    private static final ScopedValue<Boolean> ACTIVE = ScopedValue.newInstance();

    private LenientConfigWrite() {
    }

    /** Runs {@code write} as the write of an existing configuration. */
    public static <T, X extends Throwable> T call(ScopedValue.CallableOp<T, X> write) throws X {
        return ScopedValue.where(ACTIVE, Boolean.TRUE).call(write);
    }

    /**
     * True while a write of an existing configuration is in progress on this
     * thread.
     */
    public static boolean isActive() {
        return ACTIVE.isBound() && ACTIVE.get();
    }
}
