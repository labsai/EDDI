/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import java.time.Instant;
import java.util.Optional;

/**
 * The shared cooldown rows: until when a provider quota refuses calls, as the
 * provider last said, for every run on every node.
 *
 * <p>
 * A throttle belongs to the provider's quota, not to the document or the run
 * that met it: every call against the same project and model fails in that
 * window — from this run, from other runs, from other knowledge bases. One row
 * per quota ({@link ProviderThrottleKey}) lets the first refusal stop all of
 * them, instead of each one paying a call to hear the same answer.
 *
 * <p>
 * Rows only ever move later: a refusal that names an earlier time than the one
 * recorded leaves the row as it is. A row whose time has passed is simply
 * ignored; nothing deletes it.
 */
public interface IProviderCooldownStore {

    /**
     * One quota's cooldown.
     *
     * @param daily
     *            the quota is a daily one, which no wait inside a run refills
     */
    record Cooldown(String throttleKey, Instant until, boolean daily) {
    }

    /** The recorded cooldown of a quota, whether or not it has passed. */
    Optional<Cooldown> read(String throttleKey);

    /**
     * Records a refusal: the row's time becomes {@code until} unless it already
     * holds a later one.
     */
    void extend(String throttleKey, Instant until, boolean daily);
}
