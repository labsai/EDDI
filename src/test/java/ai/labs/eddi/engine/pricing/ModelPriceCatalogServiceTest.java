/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.pricing;

import ai.labs.eddi.engine.pricing.model.ModelPriceCatalog;
import ai.labs.eddi.engine.pricing.model.ModelPriceEntry;
import ai.labs.eddi.engine.pricing.model.UnpricedPolicy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelPriceCatalogServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-02T10:00:00Z"), ZoneOffset.UTC);

    /** An in-memory CAS store that can be made to fail. */
    static final class MemoryStore implements IModelPriceCatalogStore {
        ModelPriceCatalog doc;
        final AtomicBoolean failing = new AtomicBoolean();

        @Override
        public Optional<ModelPriceCatalog> read() {
            if (failing.get()) {
                throw new IllegalStateException("store down");
            }
            return Optional.ofNullable(doc);
        }

        @Override
        public synchronized boolean compareAndSet(long expectedRevision, ModelPriceCatalog next) {
            long current = doc == null ? 0 : doc.revision();
            if (current != expectedRevision) {
                return false;
            }
            doc = next;
            return true;
        }
    }

    private final MemoryStore store = new MemoryStore();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private ModelPriceCatalogService service(UnpricedPolicy floor) {
        var service = new ModelPriceCatalogService(store, registry, Set.of("openai", "anthropic"), floor, true, CLOCK);
        service.init();
        return service;
    }

    private static ModelPriceCatalog catalog(UnpricedPolicy policy, ModelPriceEntry... entries) {
        return new ModelPriceCatalog(0, policy, null, null, List.of(entries));
    }

    @AfterEach
    void reset() {
        ModelPriceCatalogs.publish(null);
    }

    @Test
    void anEmptyStorePublishesAnEmptyCatalogUnderTheFloor() {
        service(UnpricedPolicy.ALLOW);
        assertEquals(0, ModelPriceCatalogs.current().matcher().size());
        assertEquals(UnpricedPolicy.ALLOW, ModelPriceCatalogs.current().effectivePolicy());
    }

    @Test
    void aWritePublishesImmediatelyAndStampsTheRevision() {
        var service = service(UnpricedPolicy.ALLOW);
        ModelPriceCatalog stored = service.replace(0, catalog(UnpricedPolicy.REFUSE, ModelPriceEntry.of("openai", "gpt-4o", 2.5, 10.0)), "admin");
        assertEquals(1, stored.revision());
        assertEquals("admin", stored.updatedBy());
        assertEquals("2026-11-02T10:00:00Z", stored.updatedAt());
        assertTrue(ModelPriceCatalogs.current().match("openai", "gpt-4o").isPresent());
        assertEquals(UnpricedPolicy.REFUSE, ModelPriceCatalogs.current().effectivePolicy());
    }

    @Test
    void aStaleRevisionIsAConflictWithTheCurrentRevision() {
        var service = service(UnpricedPolicy.ALLOW);
        service.replace(0, catalog(UnpricedPolicy.ALLOW), "a");
        var e = assertThrows(ModelPriceCatalogException.class, () -> service.replace(0, catalog(UnpricedPolicy.ALLOW), "b"));
        assertEquals(409, e.status());
        assertEquals(1L, e.currentRevision());
    }

    @Test
    void anOutOfRangeExpectedRevisionIsRefused() {
        var service = service(UnpricedPolicy.ALLOW);
        assertEquals(400, assertThrows(ModelPriceCatalogException.class, () -> service.replace(-1, catalog(UnpricedPolicy.ALLOW), "a")).status());
        assertEquals(400,
                assertThrows(ModelPriceCatalogException.class, () -> service.replace(Long.MAX_VALUE, catalog(UnpricedPolicy.ALLOW), "a")).status());
    }

    @Test
    void anInvalidCatalogIsRefusedWithViolations() {
        var service = service(UnpricedPolicy.ALLOW);
        var e = assertThrows(ModelPriceCatalogException.class,
                () -> service.replace(0, catalog(UnpricedPolicy.ALLOW, ModelPriceEntry.of("unknown-type", "x", 1.0, 1.0)), "a"));
        assertEquals(400, e.status());
        assertEquals(1, e.violations().size());
        assertTrue(store.read().isEmpty());
    }

    @Test
    void theFloorWinsOverALooserDocumentPolicy() {
        var service = service(UnpricedPolicy.REFUSE_WHEN_BUDGETED);
        service.replace(0, catalog(UnpricedPolicy.ALLOW), "a");
        assertEquals(UnpricedPolicy.REFUSE_WHEN_BUDGETED, ModelPriceCatalogs.current().effectivePolicy());
        service.replace(1, catalog(UnpricedPolicy.REFUSE), "a");
        assertEquals(UnpricedPolicy.REFUSE, ModelPriceCatalogs.current().effectivePolicy(), "a stricter document still applies");
    }

    @Test
    void aStoreFailureKeepsTheLastGoodSnapshotAndCountsTheFailure() {
        var service = service(UnpricedPolicy.ALLOW);
        service.replace(0, catalog(UnpricedPolicy.ALLOW, ModelPriceEntry.of("openai", "gpt-4o", 2.5, 10.0)), "a");
        store.failing.set(true);
        service.refresh();
        assertTrue(ModelPriceCatalogs.current().match("openai", "gpt-4o").isPresent());
        assertEquals(1.0, registry.counter("eddi.llm.price.catalog.load.failures").count());
    }

    @Test
    void anotherNodesWriteIsPickedUpOnRefresh() {
        var service = service(UnpricedPolicy.ALLOW);
        store.doc = new ModelPriceCatalog(5, UnpricedPolicy.ALLOW, null, null, List.of(ModelPriceEntry.of("anthropic", "claude*", 3.0, 15.0)));
        assertFalse(ModelPriceCatalogs.current().match("anthropic", "claude-x").isPresent());
        service.refresh();
        assertEquals(5, ModelPriceCatalogs.current().revision());
        assertTrue(ModelPriceCatalogs.current().match("anthropic", "claude-x").isPresent());
    }

    @Test
    void anUnreadableFloorIsTheStrictestPolicy() {
        assertEquals(UnpricedPolicy.REFUSE, ModelPriceCatalogService.parsePolicy("REFUSE-ish"));
        assertEquals(UnpricedPolicy.ALLOW, ModelPriceCatalogService.parsePolicy(null));
        assertEquals(UnpricedPolicy.REFUSE_WHEN_BUDGETED, ModelPriceCatalogService.parsePolicy(" refuse_when_budgeted "));
    }

    @Test
    void theBundledReferenceCatalogIsValidAndSourced() {
        var service = new ModelPriceCatalogService(store, null, null, UnpricedPolicy.ALLOW, true, CLOCK);
        ModelPriceCatalog reference = service.reference();
        assertFalse(reference.entries().isEmpty());
        assertEquals(List.of(), ModelPriceCatalogValidator.validate(reference, null));
        for (ModelPriceEntry entry : reference.entries()) {
            assertTrue(entry.source() != null && entry.source().startsWith("https://"), entry.model());
            assertTrue(entry.asOf() != null, entry.model());
        }
    }

    @Test
    void oldestAsOfIsMeasuredInDays() {
        var service = service(UnpricedPolicy.ALLOW);
        service.replace(0, catalog(UnpricedPolicy.ALLOW,
                new ModelPriceEntry("openai", "a", 1.0, 1.0, null, null, null, "2026-06-25", null, null),
                new ModelPriceEntry("openai", "b", 1.0, 1.0, null, null, null, "2026-10-01", null, null)), "a");
        assertEquals(130, service.oldestAsOfDays());
    }
}
