/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.model.HealthMarker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.util.UUID;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Writes the change markers Agent Health shows on its charts, from the few
 * places a change happens: a deployment that reached {@code READY}, a global
 * variable written or deleted, a prompt snippet saved or deleted.
 * <p>
 * <b>Never fails the change it records</b>: a marker is a chart annotation, so
 * a store error is logged at DEBUG and dropped. Labels name what changed, never
 * a value — and for global variables and snippets not even the name: both
 * concern every agent, and a viewer of one agent's health has no business
 * reading the deployment's configuration keys.
 */
@ApplicationScoped
public class HealthChangeMarkers {

    private static final Logger LOGGER = Logger.getLogger(HealthChangeMarkers.class);

    private final Instance<IHealthMarkerStore> store;
    private final Clock clock;

    @Inject
    public HealthChangeMarkers(Instance<IHealthMarkerStore> store) {
        this(store, Clock.systemUTC());
    }

    HealthChangeMarkers(Instance<IHealthMarkerStore> store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** An agent version was deployed and is {@code READY}. */
    public void deployed(String agentId, Integer version, String environment) {
        if (agentId == null) {
            return;
        }
        append(new HealthMarker(newId(), agentId, clock.instant(), HealthMarker.KIND_DEPLOYMENT,
                "v" + version + " deployed (" + (environment == null ? "production" : environment) + ")", version));
    }

    /** A global variable was written or deleted. */
    public void globalVariableChanged(boolean deleted) {
        append(new HealthMarker(newId(), null, clock.instant(), HealthMarker.KIND_GLOBAL_VARIABLE_CHANGE,
                deleted ? "A global variable was deleted" : "A global variable was changed", null));
    }

    /** A prompt snippet was created, updated or deleted. */
    public void snippetChanged(boolean deleted) {
        append(new HealthMarker(newId(), null, clock.instant(), HealthMarker.KIND_SNIPPET_CHANGE,
                deleted ? "A prompt snippet was deleted" : "A prompt snippet was saved", null));
    }

    private static String newId() {
        return "m-" + UUID.randomUUID();
    }

    private void append(HealthMarker marker) {
        try {
            store.get().append(marker);
        } catch (RuntimeException e) {
            LOGGER.debugf("Could not store an Agent Health change marker (%s): %s", marker.kind(), sanitize(e.getMessage()));
        }
    }
}
