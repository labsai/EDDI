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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
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
 * concern many agents, and a viewer of one agent's health has no business
 * reading the deployment's configuration keys.
 * <p>
 * <b>Deployment-wide markers are scoped and coalesced.</b> A global-variable
 * marker carries the variable's tenant ({@code default}, or a space's tenant)
 * and is shown only with the agents of that tenant (every agent sees
 * {@code default}; an agent in a space also sees its space's tenant). Snippets
 * are not tenant-scoped, so their markers are {@code default}. And their id
 * names the kind, the tenant and the UTC hour, so a script saving a thousand
 * variables leaves one marker per hour, not a thousand that crowd the agent's
 * deployments out of the chart.
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

    /** A global variable of {@code tenantId} was written or deleted. */
    public void globalVariableChanged(String tenantId) {
        appendCoalesced(HealthMarker.KIND_GLOBAL_VARIABLE_CHANGE, tenantId, "Global variables changed");
    }

    /** A prompt snippet was created, updated or deleted. */
    public void snippetChanged() {
        appendCoalesced(HealthMarker.KIND_SNIPPET_CHANGE, HealthMarker.DEFAULT_TENANT, "Prompt snippets changed");
    }

    private void appendCoalesced(String kind, String tenantId, String label) {
        String tenant = tenantId == null || tenantId.isBlank() ? HealthMarker.DEFAULT_TENANT : tenantId;
        Instant hour = clock.instant().truncatedTo(ChronoUnit.HOURS);
        append(new HealthMarker(coalescedId(kind, tenant, hour), null, hour, kind, label, null, tenant));
    }

    /**
     * The id of a coalesced marker: {@code g-} and 40 hex characters of the SHA-256
     * of kind, tenant and hour — the same for every change of one kind in one
     * tenant and hour (that is the coalescing), and 42 characters whatever the
     * tenant id's length, so it always fits the stores' id column.
     */
    static String coalescedId(String kind, String tenant, Instant hour) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((kind + "\n" + tenant + "\n" + hour.getEpochSecond()).getBytes(StandardCharsets.UTF_8));
            return "g-" + HexFormat.of().formatHex(digest).substring(0, 40);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
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
