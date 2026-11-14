/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rules;

/**
 * Marks a ruleset write that carries a ruleset which already exists, rather
 * than one an author is writing now: an import, an instance-to-instance sync or
 * upgrade, a duplicate.
 * <p>
 * The save-time check in {@code RuleSetStore} refuses <em>retired</em>
 * condition configs (a {@code deploymentContext} with {@code tagMatches}, a
 * {@code dependency} without {@code reference}) so that no author creates a new
 * one. Stored rulesets keep deploying with those configs, logged as a WARN, and
 * evaluate exactly as they always did. A write inside this scope gets the same
 * treatment as a deploy: the retired config is accepted with a WARN, so an
 * export of a working agent still imports and a sync still copies it. Every
 * other check of the save path still applies.
 * <p>
 * A {@link ScopedValue}: the binding ends with the call and cannot leak onto a
 * pooled thread. The writes it wraps run synchronously on the caller's thread.
 */
public final class ExistingRuleSetWrite {

    private static final ScopedValue<Boolean> ACTIVE = ScopedValue.newInstance();

    private ExistingRuleSetWrite() {
    }

    /** Runs {@code write} as the write of an existing ruleset. */
    public static <T, X extends Throwable> T call(ScopedValue.CallableOp<T, X> write) throws X {
        return ScopedValue.where(ACTIVE, Boolean.TRUE).call(write);
    }

    /** True while a write of an existing ruleset is in progress on this thread. */
    public static boolean isActive() {
        return ACTIVE.isBound() && ACTIVE.get();
    }
}
