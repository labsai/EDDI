/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.pricing;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Delivers a {@link SpendEvent} to every {@link ISpendSink}, each in its own
 * try/catch. Fed by {@code TurnCostMeter}, {@code DreamService} and
 * {@link SystemSpendMeter}.
 */
@ApplicationScoped
public class SpendSinks {

    private static final Logger LOGGER = Logger.getLogger(SpendSinks.class);

    /** Delivers nothing — for code built without CDI (tests). */
    public static final SpendSinks NONE = new SpendSinks(List.of());

    private final Iterable<ISpendSink> sinks;

    @Inject
    public SpendSinks(Instance<ISpendSink> sinks) {
        this.sinks = sinks;
    }

    public SpendSinks(List<ISpendSink> sinks) {
        this.sinks = sinks;
    }

    /** Delivers {@code event}; ignores a null or non-positive one. Never throws. */
    public void fire(SpendEvent event) {
        if (event == null || !Double.isFinite(event.usd()) || !(event.usd() > 0.0)) {
            return;
        }
        try {
            for (ISpendSink sink : sinks) {
                try {
                    sink.onSpend(event);
                } catch (RuntimeException e) {
                    LOGGER.warnf("Spend sink %s failed for a %s charge of $%.6f: %s", sink.getClass().getSimpleName(), event.kind(), event.usd(),
                            e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            LOGGER.warnf("Spend sinks could not be resolved: %s", e.getMessage());
        }
    }
}
