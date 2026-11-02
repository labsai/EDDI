/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.audit;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import org.jboss.logging.Logger;

import java.util.function.Consumer;

/**
 * Fires {@link HitlDecisionRecorded} asynchronously through the CDI container.
 * <p>
 * Resolved lazily from {@link Arc#container()} rather than injected, because
 * the HITL collaborators that write decisions are plain classes built inside
 * their facades' constructors. Outside a running container (plain unit tests)
 * firing is a no-op. Never throws.
 *
 * @since 6.7.0
 */
public final class HitlDecisionEvents {

    private static final Logger LOGGER = Logger.getLogger(HitlDecisionEvents.class);

    /** The default publisher: async CDI event when a container runs. */
    public static final Consumer<HitlDecisionRecorded> CDI_ASYNC = HitlDecisionEvents::fireAsync;

    private HitlDecisionEvents() {
    }

    private static void fireAsync(HitlDecisionRecorded event) {
        try {
            ArcContainer container = Arc.container();
            if (container == null || !container.isRunning()) {
                return;
            }
            container.beanManager().getEvent().select(HitlDecisionRecorded.class).fireAsync(event);
        } catch (RuntimeException e) {
            LOGGER.warnf("Could not fire HitlDecisionRecorded for audit entry %s: %s", event.auditEntryId(), e.getMessage());
        }
    }
}
