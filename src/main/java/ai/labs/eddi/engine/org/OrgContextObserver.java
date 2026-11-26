/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

/**
 * Receives the events {@link OrgContext} and {@link OrgScope} count. The static
 * context cannot inject a {@code MeterRegistry}, so {@link OrgConfig} installs
 * an implementation at startup; until then, and in plain unit tests, events go
 * to {@link #NONE}.
 */
interface OrgContextObserver {

    OrgContextObserver NONE = new OrgContextObserver() {
    };

    /**
     * An unbound single-mode {@link OrgContext#require()} fell back to the default
     * org.
     */
    default void fallback(String site) {
    }

    /** {@link OrgScope#platform} was entered (outermost entry only). */
    default void platformScope(String reason) {
    }
}
