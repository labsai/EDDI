/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.pricing;

import ai.labs.eddi.engine.pricing.model.ModelPriceCatalog;
import ai.labs.eddi.engine.pricing.model.ModelPriceEntry;
import ai.labs.eddi.engine.pricing.model.UnpricedPolicy;
import ai.labs.eddi.modules.llm.impl.builder.ILanguageModelBuilder;
import ai.labs.eddi.modules.llm.pricing.UsageMetering;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Owns the deployment price catalog: reads and writes it (compare-and-set,
 * validated), compiles it into a {@link PriceCatalogSnapshot} and publishes
 * that through {@link ModelPriceCatalogs} for every pricing site.
 * <p>
 * <b>Convergence.</b> A local write publishes immediately; every node also
 * re-reads the store every 30 seconds, so other pods converge within that.
 * <b>Failure.</b> If the store cannot be read at boot the snapshot is empty —
 * pricing from task fields only, exactly as before the catalog — and later
 * failures keep the last good snapshot. Either way the policy never drops below
 * the operator's floor ({@code eddi.llm.pricing.unpriced-policy-floor}), which
 * an admin's catalog edit cannot loosen.
 */
@Startup
@ApplicationScoped
public class ModelPriceCatalogService {

    private static final Logger LOGGER = Logger.getLogger(ModelPriceCatalogService.class);
    static final String REFERENCE_RESOURCE = "pricing/reference-model-prices.json";
    /** Entries older than this many days are flagged stale in the Manager. */
    public static final int STALE_AFTER_DAYS = 120;
    private static final long WARN_INTERVAL_MS = 60_000;

    private final IModelPriceCatalogStore store;
    private final MeterRegistry meterRegistry;
    private final Set<String> knownProviders;
    private final UnpricedPolicy floor;
    private final boolean normalizeUsage;
    private final Clock clock;

    private Counter loadFailures;
    private volatile long lastWarnMs;
    private volatile ModelPriceCatalog reference;

    @Inject
    public ModelPriceCatalogService(IModelPriceCatalogStore store, MeterRegistry meterRegistry,
            Map<String, Provider<ILanguageModelBuilder>> languageModelBuilders,
            @ConfigProperty(name = "eddi.llm.pricing.unpriced-policy-floor", defaultValue = "ALLOW") String floor,
            @ConfigProperty(name = "eddi.llm.pricing.provider-normalization", defaultValue = "true") boolean normalizeUsage) {
        this(store, meterRegistry, languageModelBuilders != null ? languageModelBuilders.keySet() : null, parsePolicy(floor), normalizeUsage,
                Clock.systemUTC());
    }

    ModelPriceCatalogService(IModelPriceCatalogStore store, MeterRegistry meterRegistry, Set<String> knownProviders, UnpricedPolicy floor,
            boolean normalizeUsage, Clock clock) {
        this.store = store;
        this.meterRegistry = meterRegistry;
        this.knownProviders = knownProviders == null ? null : Set.copyOf(knownProviders);
        this.floor = floor == null ? UnpricedPolicy.ALLOW : floor;
        this.normalizeUsage = normalizeUsage;
        this.clock = clock;
    }

    @PostConstruct
    void init() {
        UsageMetering.setNormalize(normalizeUsage);
        if (meterRegistry != null) {
            loadFailures = meterRegistry.counter("eddi.llm.price.catalog.load.failures");
            Gauge.builder("eddi.llm.price.catalog.entries", () -> ModelPriceCatalogs.current().matcher().size())
                    .description("Entries in the deployment price catalog").register(meterRegistry);
            Gauge.builder("eddi.llm.price.catalog.oldest_asof_days", this::oldestAsOfDays)
                    .description("Age in days of the oldest asOf date in the price catalog").register(meterRegistry);
        }
        ModelPriceCatalogs.publish(PriceCatalogSnapshot.EMPTY.withFloor(floor));
        refresh();
        if (floor != UnpricedPolicy.ALLOW) {
            LOGGER.infof("Unpriced model calls: policy floor %s (eddi.llm.pricing.unpriced-policy-floor)", floor);
        }
    }

    /**
     * Re-reads the store and publishes the result. A failure keeps the snapshot in
     * force (empty at boot), counts {@code eddi.llm.price.catalog.load.failures}
     * and warns at most once a minute.
     */
    @Scheduled(every = "30s", delayed = "30s", identity = "model-price-catalog-refresh", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void refresh() {
        try {
            publish(store.read().orElse(ModelPriceCatalog.EMPTY));
        } catch (RuntimeException e) {
            if (loadFailures != null) {
                loadFailures.increment();
            }
            long now = clock.millis();
            if (now - lastWarnMs >= WARN_INTERVAL_MS) {
                lastWarnMs = now;
                LOGGER.warnf("Could not read the model price catalog; keeping the prices in force (revision %d): %s",
                        ModelPriceCatalogs.current().revision(), e.getMessage());
            }
        }
    }

    /** The stored catalog ({@link ModelPriceCatalog#EMPTY} when none). */
    public ModelPriceCatalog read() {
        return store.read().orElse(ModelPriceCatalog.EMPTY);
    }

    /**
     * Replaces the catalog if it is still at {@code expectedRevision}.
     *
     * @throws ModelPriceCatalogException
     *             400 with the violations, or 409 with the stored revision
     */
    public ModelPriceCatalog replace(long expectedRevision, ModelPriceCatalog document, String user) {
        if (document == null) {
            throw new ModelPriceCatalogException(ModelPriceCatalogException.INVALID, "A catalog document is required.", List.of(), null);
        }
        List<String> violations = ModelPriceCatalogValidator.validate(document, knownProviders);
        if (violations.isEmpty()
                && ModelPriceCatalogJson.write(document).getBytes(StandardCharsets.UTF_8).length > ModelPriceCatalogValidator.MAX_DOCUMENT_BYTES) {
            violations = List.of("the catalog is larger than " + ModelPriceCatalogValidator.MAX_DOCUMENT_BYTES / 1024 + " KB");
        }
        if (!violations.isEmpty()) {
            throw new ModelPriceCatalogException(ModelPriceCatalogException.INVALID, "The price catalog was rejected.", violations, null);
        }
        ModelPriceCatalog next = document.stamped(expectedRevision + 1, clock.instant().toString(), user);
        if (!store.compareAndSet(expectedRevision, next)) {
            long current = read().revision();
            throw new ModelPriceCatalogException(ModelPriceCatalogException.CONFLICT,
                    "The price catalog changed since revision " + expectedRevision + "; reload it and apply your change again.", List.of(),
                    current);
        }
        LOGGER.infof("Model price catalog revision %d saved by %s: %d entries, unpriced policy %s", next.revision(), user, next.entries().size(),
                next.unpricedPolicy());
        publish(next);
        return next;
    }

    /** The snapshot in force. */
    public PriceCatalogSnapshot snapshot() {
        return ModelPriceCatalogs.current();
    }

    /** The operator's floor policy. */
    public UnpricedPolicy floor() {
        return floor;
    }

    /** The model types this deployment can build, or null when unknown. */
    public Set<String> knownProviders() {
        return knownProviders == null ? null : new TreeSet<>(knownProviders);
    }

    /** The catalog entry pricing {@code model} on {@code provider}, if any. */
    public Optional<ModelPriceEntry> resolve(String provider, String model) {
        return ModelPriceCatalogs.current().match(provider, model);
    }

    /**
     * The bundled reference prices ({@value #REFERENCE_RESOURCE}) — read-only,
     * never active until an admin adopts them into the catalog.
     */
    public ModelPriceCatalog reference() {
        ModelPriceCatalog cached = reference;
        if (cached == null) {
            try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(REFERENCE_RESOURCE)) {
                cached = in == null ? ModelPriceCatalog.EMPTY : ModelPriceCatalogJson.read(in);
            } catch (IOException e) {
                LOGGER.warnf("Could not read the bundled reference prices: %s", e.getMessage());
                cached = ModelPriceCatalog.EMPTY;
            }
            reference = cached;
        }
        return cached;
    }

    /** Age in days of the oldest {@code asOf} in force, or 0 with none. */
    public double oldestAsOfDays() {
        LocalDate oldest = ModelPriceCatalogs.current().oldestAsOf();
        return oldest == null ? 0 : Math.max(0, ChronoUnit.DAYS.between(oldest, LocalDate.now(clock)));
    }

    private void publish(ModelPriceCatalog catalog) {
        ModelPriceCatalogs.publish(PriceCatalogSnapshot.of(catalog, floor));
    }

    static UnpricedPolicy parsePolicy(String value) {
        if (value == null || value.isBlank()) {
            return UnpricedPolicy.ALLOW;
        }
        try {
            return UnpricedPolicy.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // A typo in a safety floor must not silently disable it: the strictest
            // policy is the safe reading of a value we cannot parse.
            LOGGER.errorf("eddi.llm.pricing.unpriced-policy-floor=%s is not ALLOW, REFUSE_WHEN_BUDGETED or REFUSE; using REFUSE", value);
            return UnpricedPolicy.REFUSE;
        }
    }
}
