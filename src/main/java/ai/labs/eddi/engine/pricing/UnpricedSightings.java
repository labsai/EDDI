/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.pricing;

import io.micrometer.core.instrument.Metrics;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where unpriced model calls were seen on <em>this node</em> since it started:
 * provider, model, site, count, last seen — what the Manager's "Unpriced models
 * seen" panel lists, with an "add price" shortcut. Bounded (an LRU of
 * {@link #MAX_SIGHTINGS} rows) and node-local by design; the cluster-wide
 * signal is the {@code eddi.llm.calls.unpriced} counter, which every record
 * also increments.
 * <p>
 * Static, like {@link ModelPriceCatalogs}: it is fed from pricing sites that
 * are not CDI-injected.
 */
public final class UnpricedSightings {

    private static final Logger LOGGER = Logger.getLogger(UnpricedSightings.class);

    /** Rows kept. */
    public static final int MAX_SIGHTINGS = 200;
    /** Distinct model tag values on the counter before they fold into "other". */
    static final int MAX_MODEL_TAGS = 100;
    static final String OTHER = "other";

    /** One sighting row. */
    public record Sighting(String provider, String model, String site, long count, Instant lastSeen) {
    }

    private static final Map<String, Sighting> ROWS = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Sighting> eldest) {
            return size() > MAX_SIGHTINGS;
        }
    };
    private static final Set<String> MODEL_TAGS = ConcurrentHashMap.newKeySet();

    private UnpricedSightings() {
    }

    /**
     * Records {@code calls} unpriced calls of {@code pricingModel}
     * ({@code "<type>/<model>"}) at {@code site}. Never throws.
     */
    public static void record(String pricingModel, String site, int calls) {
        if (calls <= 0) {
            return;
        }
        try {
            String provider = "unknown";
            String model = "unknown";
            if (pricingModel != null && !pricingModel.isBlank()) {
                int slash = pricingModel.indexOf('/');
                provider = slash > 0 ? pricingModel.substring(0, slash) : pricingModel;
                model = slash >= 0 ? pricingModel.substring(slash + 1) : "unknown";
            }
            if (model.length() > 128) {
                model = model.substring(0, 128);
            }
            String key = provider + "\u0000" + model + "\u0000" + site;
            Instant now = Instant.now();
            synchronized (ROWS) {
                Sighting previous = ROWS.get(key);
                ROWS.put(key, new Sighting(provider, model, site, (previous != null ? previous.count() : 0) + calls, now));
            }
            Metrics.globalRegistry.counter("eddi.llm.calls.unpriced", "provider", provider, "model", tag(model), "site", site).increment(calls);
        } catch (RuntimeException e) {
            LOGGER.debugf("Unpriced sighting not recorded: %s", e.getMessage());
        }
    }

    /** The rows, most recently seen first. */
    public static List<Sighting> list() {
        List<Sighting> rows;
        synchronized (ROWS) {
            rows = new ArrayList<>(ROWS.values());
        }
        rows.sort((a, b) -> b.lastSeen().compareTo(a.lastSeen()));
        return rows;
    }

    /** Clears the rows (tests). */
    static void clear() {
        synchronized (ROWS) {
            ROWS.clear();
        }
    }

    private static String tag(String model) {
        if (MODEL_TAGS.contains(model)) {
            return model;
        }
        if (MODEL_TAGS.size() < MAX_MODEL_TAGS && MODEL_TAGS.add(model)) {
            return model;
        }
        return MODEL_TAGS.contains(model) ? model : OTHER;
    }
}
