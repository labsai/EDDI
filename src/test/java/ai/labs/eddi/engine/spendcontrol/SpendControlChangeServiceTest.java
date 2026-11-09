/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.spendcontrol;

import ai.labs.eddi.engine.pricing.ModelPriceCatalogException;
import ai.labs.eddi.engine.pricing.ModelPriceCatalogService;
import ai.labs.eddi.engine.pricing.PriceCatalogSnapshot;
import ai.labs.eddi.engine.pricing.model.ModelPriceCatalog;
import ai.labs.eddi.engine.pricing.model.ModelPriceEntry;
import ai.labs.eddi.engine.pricing.model.UnpricedPolicy;
import ai.labs.eddi.engine.spendcontrol.SpendControlChangeService.EntryKey;
import ai.labs.eddi.engine.spendcontrol.SpendControlChangeService.Outcome;
import ai.labs.eddi.engine.spendcontrol.SpendControlChangeService.Result;
import ai.labs.eddi.engine.spendcontrol.model.SpendControlChange;
import ai.labs.eddi.engine.spendcontrol.model.SpendControlChange.Status;
import ai.labs.eddi.engine.tenancy.InMemoryTenantQuotaStore;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.TenantQuota;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("SpendControlChangeService")
class SpendControlChangeServiceTest {

    private static final String SRC = "https://example.com/pricing";
    private static final String TENANT = "default";

    private InMemorySpendControlChangeStore store;
    private ModelPriceCatalogService catalogService;
    private InMemoryTenantQuotaStore quotaStore;
    private TenantQuotaService quotaService;
    private SpendControlAudit audit;
    private PriceCatalogSnapshot snapshot;
    private final AtomicReference<ModelPriceCatalog> stored = new AtomicReference<>();
    private MutableClock clock;
    private SpendControlChangeService service;

    @BeforeEach
    void setUp() {
        store = new InMemorySpendControlChangeStore();
        catalogService = mock(ModelPriceCatalogService.class);
        snapshot = mock(PriceCatalogSnapshot.class);
        when(snapshot.effectivePolicy()).thenReturn(UnpricedPolicy.ALLOW);
        when(catalogService.snapshot()).thenReturn(snapshot);
        when(catalogService.knownProviders()).thenReturn(Set.of("openai", "anthropic"));
        stored.set(new ModelPriceCatalog(4, UnpricedPolicy.ALLOW, null, null, List.of(entry("openai", "gpt-4o", 2.5, 10.0))));
        when(catalogService.read()).thenAnswer(inv -> stored.get());
        when(catalogService.replace(anyLong(), any(), anyString())).thenAnswer(inv -> {
            long expected = inv.getArgument(0);
            ModelPriceCatalog doc = inv.getArgument(1);
            if (stored.get().revision() != expected) {
                throw new ModelPriceCatalogException(ModelPriceCatalogException.CONFLICT, "changed", List.of(), stored.get().revision());
            }
            ModelPriceCatalog next = doc.stamped(expected + 1, "now", inv.getArgument(2));
            stored.set(next);
            return next;
        });
        quotaStore = new InMemoryTenantQuotaStore(TenantQuota.unlimited(TENANT));
        quotaService = new TenantQuotaService(quotaStore, new SimpleMeterRegistry(), TENANT);
        audit = mock(SpendControlAudit.class);
        clock = new MutableClock(Instant.parse("2026-11-09T10:00:00Z"));
        service = new SpendControlChangeService(store, catalogService, quotaService, audit, new SimpleMeterRegistry(), true, clock);
    }

    private static ModelPriceEntry entry(String provider, String model, Double in, Double out) {
        return new ModelPriceEntry(provider, model, in, out, null, null, null, null, SRC, null);
    }

    private static SecurityIdentity identity(String name, String... roles) {
        SecurityIdentity id = mock(SecurityIdentity.class);
        Set<String> granted = Set.of(roles);
        when(id.isAnonymous()).thenReturn(false);
        when(id.hasRole(anyString())).thenAnswer(inv -> granted.contains(inv.getArgument(0, String.class)));
        Principal principal = mock(Principal.class);
        when(principal.getName()).thenReturn(name);
        when(id.getPrincipal()).thenReturn(principal);
        return id;
    }

    private Outcome lowerGpt4o() {
        return service.changeModelPrices(4, List.of(entry("openai", "gpt-4o", 0.0, 0.0)), List.of(), null, "cheaper now", "assistant", "mcp");
    }

    @Nested
    @DisplayName("price catalog writes")
    class CatalogWrites {

        @Test
        @DisplayName("a stricter write applies at once with the caller as actor and an audit entry")
        void stricterApplies() {
            Outcome outcome = service.changeModelPrices(4, List.of(entry("openai", "gpt-4o", 3.0, 12.0)), List.of(), null, "list price went up",
                    "assistant", "mcp");

            assertEquals(Result.APPLIED, outcome.result());
            verify(catalogService).replace(eq(4L), any(), eq("assistant"));
            assertEquals(5, stored.get().revision());
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
            verify(audit).record(eq(SpendControlAudit.APPLIED), eq("assistant"), details.capture());
            assertEquals("list price went up", details.getValue().get("reason"));
            assertEquals(0, store.rows.size());
        }

        @Test
        @DisplayName("a loosening write never applies: it files a pending change")
        void looserIsProposed() {
            Outcome outcome = lowerGpt4o();

            assertEquals(Result.PROPOSED, outcome.result());
            verify(catalogService, never()).replace(anyLong(), any(), anyString());
            assertEquals(4, stored.get().revision());
            SpendControlChange change = outcome.proposal();
            assertEquals(Status.PENDING, change.status());
            assertEquals(4L, change.baseRevision());
            assertEquals("assistant", change.proposedBy());
            assertTrue(change.loosening().get(0).contains("gpt-4o"));
            verify(audit).record(eq(SpendControlAudit.PROPOSED), eq("assistant"), anyMap());
        }

        @Test
        @DisplayName("a write mixing a stricter and a looser part is proposed as a whole")
        void mixedIsProposed() {
            Outcome outcome = service.changeModelPrices(4, List.of(entry("anthropic", "claude-x", 3.0, 15.0)),
                    List.of(new EntryKey("openai", "gpt-4o")), null, "swap", "assistant", "mcp");
            assertEquals(Result.PROPOSED, outcome.result());
            verify(catalogService, never()).replace(anyLong(), any(), anyString());
        }

        @Test
        @DisplayName("a stale revision is refused with the current one")
        void staleRevision() {
            var e = assertThrows(SpendControlException.class, () -> service.changeModelPrices(3, List.of(entry("openai", "gpt-4o", 3.0, 12.0)),
                    List.of(), null, "r", "assistant", "mcp"));
            assertEquals(SpendControlException.CONFLICT, e.code());
            assertEquals(4L, e.details().get("currentRevision"));
            verify(catalogService, never()).replace(anyLong(), any(), anyString());
        }

        @Test
        @DisplayName("an entry without a source link, a missing reason or too many entries is refused")
        void boundedInput() {
            var noSource = new ModelPriceEntry("openai", "gpt-4o", 3.0, 12.0, null, null, null, null, null, null);
            assertEquals(SpendControlException.BAD_REQUEST, assertThrows(SpendControlException.class,
                    () -> service.changeModelPrices(4, List.of(noSource), List.of(), null, "r", "a", "mcp")).code());
            assertEquals(SpendControlException.BAD_REQUEST, assertThrows(SpendControlException.class,
                    () -> service.changeModelPrices(4, List.of(entry("openai", "gpt-4o", 3.0, 12.0)), List.of(), null, " ", "a", "mcp")).code());
            List<ModelPriceEntry> many = IntStream.range(0, 51).mapToObj(i -> entry("openai", "m" + i, 1.0, 1.0)).toList();
            assertEquals(SpendControlException.BAD_REQUEST, assertThrows(SpendControlException.class,
                    () -> service.changeModelPrices(4, many, List.of(), null, "r", "a", "mcp")).code());
            var tooDear = entry("openai", "gpt-4o", 20_000.0, 12.0);
            assertEquals(SpendControlException.BAD_REQUEST, assertThrows(SpendControlException.class,
                    () -> service.changeModelPrices(4, List.of(tooDear), List.of(), null, "r", "a", "mcp")).code());
        }

        @Test
        @DisplayName("repeating the same proposal returns the pending one; the pending queue is capped")
        void idempotentAndCapped() {
            String first = lowerGpt4o().proposal().id();
            assertEquals(first, lowerGpt4o().proposal().id());
            assertEquals(1, store.rows.size());
            for (int i = 1; i < SpendControlChangeService.MAX_PENDING; i++) {
                service.changeModelPrices(4, List.of(entry("openai", "gpt-4o", 0.0, (double) i / 1000)), List.of(), null, "r" + i, "a", "mcp");
            }
            var e = assertThrows(SpendControlException.class,
                    () -> service.changeModelPrices(4, List.of(entry("openai", "gpt-4o", 0.0, 9.0)), List.of(), null, "one more", "a", "mcp"));
            assertEquals(SpendControlException.TOO_MANY_PENDING, e.code());
        }
    }

    @Nested
    @DisplayName("approving")
    class Approving {

        @Test
        @DisplayName("an eddi-admin's approval applies the proposal with the approver as actor")
        void adminApplies() {
            String id = lowerGpt4o().proposal().id();

            SpendControlChange applied = service.approve(id, "ok", identity("alice", "eddi-admin"));

            assertEquals(Status.APPLIED, applied.status());
            assertEquals("alice", applied.decidedBy());
            verify(catalogService).replace(eq(4L), any(), eq("alice"));
            assertEquals(0.0, stored.get().entries().get(0).inputPricePer1M());
            verify(audit).record(eq(SpendControlAudit.APPROVED), eq("alice"), anyMap());
            assertEquals(Status.APPLIED, store.read(id).orElseThrow().status());
        }

        @Test
        @DisplayName("an approval by a non-admin applies nothing")
        void nonAdminAppliesNothing() {
            String id = lowerGpt4o().proposal().id();

            for (SecurityIdentity who : List.of(identity("bob", "eddi-approver"), identity("carol", "eddi-editor"),
                    identity("assistant", "eddi-user"))) {
                var e = assertThrows(SpendControlException.class, () -> service.approve(id, null, who));
                assertEquals(SpendControlException.FORBIDDEN, e.code());
            }
            SecurityIdentity anonymous = mock(SecurityIdentity.class);
            when(anonymous.isAnonymous()).thenReturn(true);
            assertEquals(SpendControlException.FORBIDDEN,
                    assertThrows(SpendControlException.class, () -> service.approve(id, null, anonymous)).code());

            verify(catalogService, never()).replace(anyLong(), any(), anyString());
            assertEquals(Status.PENDING, store.read(id).orElseThrow().status());
            assertEquals(4, stored.get().revision());
        }

        @Test
        @DisplayName("a proposal whose catalog moved on becomes STALE and applies nothing")
        void stale() {
            String id = lowerGpt4o().proposal().id();
            stored.set(stored.get().stamped(9, "later", "someone"));

            var e = assertThrows(SpendControlException.class, () -> service.approve(id, null, identity("alice", "eddi-admin")));

            assertEquals(SpendControlException.STALE, e.code());
            assertEquals(9, stored.get().revision());
            assertEquals(2.5, stored.get().entries().get(0).inputPricePer1M());
            assertEquals(Status.STALE, store.read(id).orElseThrow().status());
            verify(audit).record(eq(SpendControlAudit.STALE), eq("alice"), anyMap());
        }

        @Test
        @DisplayName("a decided change cannot be approved again, rejected or withdrawn")
        void decidedOnce() {
            String id = lowerGpt4o().proposal().id();
            service.reject(id, "no", identity("alice", "eddi-admin"));

            assertEquals(SpendControlException.ALREADY_DECIDED,
                    assertThrows(SpendControlException.class, () -> service.approve(id, null, identity("alice", "eddi-admin"))).code());
            assertEquals(SpendControlException.ALREADY_DECIDED,
                    assertThrows(SpendControlException.class, () -> service.withdraw(id, "r", "assistant")).code());
            verify(catalogService, never()).replace(anyLong(), any(), anyString());
        }

        @Test
        @DisplayName("an expired proposal cannot be approved")
        void expired() {
            String id = lowerGpt4o().proposal().id();
            clock.advance(SpendControlChangeService.PENDING_TTL.plusMinutes(1));

            assertEquals(SpendControlException.ALREADY_DECIDED,
                    assertThrows(SpendControlException.class, () -> service.approve(id, null, identity("alice", "eddi-admin"))).code());
            assertEquals(Status.EXPIRED, store.read(id).orElseThrow().status());
            verify(catalogService, never()).replace(anyLong(), any(), anyString());
        }

        @Test
        @DisplayName("only the proposer withdraws; someone else's change answers NOT_FOUND")
        void withdraw() {
            String id = lowerGpt4o().proposal().id();
            assertEquals(SpendControlException.NOT_FOUND,
                    assertThrows(SpendControlException.class, () -> service.withdraw(id, "mine now", "mallory")).code());
            assertEquals(Status.WITHDRAWN, service.withdraw(id, "not needed", "assistant").status());
        }
    }

    @Nested
    @DisplayName("tenant quota")
    class Quota {

        private String fingerprint() {
            return SpendControlChangeService.quotaFingerprint(quotaStore.getQuota(TENANT));
        }

        @BeforeEach
        void configured() {
            quotaStore.setQuota(new TenantQuota(TENANT, 100, -1, 60, 50.0, true));
        }

        @Test
        @DisplayName("lowering a limit applies at once")
        void lowerApplies() {
            Outcome outcome = service.changeTenantQuota(null, fingerprint(), null, null, null, 20.0, null, "tighten", "assistant", "mcp");
            assertEquals(Result.APPLIED, outcome.result());
            assertEquals(20.0, quotaStore.getQuota(TENANT).maxMonthlyCostUsd());
        }

        @Test
        @DisplayName("raising, making unlimited or disabling never applies without approval")
        void looserIsProposed() {
            String before = fingerprint();
            assertEquals(Result.PROPOSED, service.changeTenantQuota(null, before, null, null, null, 500.0, null, "r1", "a", "mcp").result());
            assertEquals(Result.PROPOSED, service.changeTenantQuota(null, before, -1, null, null, null, null, "r2", "a", "mcp").result());
            assertEquals(Result.PROPOSED, service.changeTenantQuota(null, before, null, null, null, null, false, "r3", "a", "mcp").result());
            assertEquals(new TenantQuota(TENANT, 100, -1, 60, 50.0, true), quotaStore.getQuota(TENANT));
        }

        @Test
        @DisplayName("a stale fingerprint is refused")
        void staleFingerprint() {
            var e = assertThrows(SpendControlException.class,
                    () -> service.changeTenantQuota(null, "none", null, null, null, 20.0, null, "r", "a", "mcp"));
            assertEquals(SpendControlException.CONFLICT, e.code());
            assertEquals(fingerprint(), e.details().get("currentFingerprint"));
        }

        @Test
        @DisplayName("an approved raise is applied by compare-and-set, and becomes STALE if the quota moved")
        void approve() {
            String id = service.changeTenantQuota(null, fingerprint(), null, null, null, 500.0, null, "bigger month", "a", "mcp").proposal().id();
            assertEquals(Status.APPLIED, service.approve(id, null, identity("alice", "eddi-admin")).status());
            assertEquals(500.0, quotaStore.getQuota(TENANT).maxMonthlyCostUsd());

            String second = service.changeTenantQuota(null, fingerprint(), 1000, null, null, null, null, "more", "a", "mcp").proposal().id();
            quotaStore.setQuota(new TenantQuota(TENANT, 10, -1, 60, 500.0, true));
            var e = assertThrows(SpendControlException.class, () -> service.approve(second, null, identity("alice", "eddi-admin")));
            assertEquals(SpendControlException.STALE, e.code());
            assertEquals(10, quotaStore.getQuota(TENANT).maxConversationsPerDay());
        }

        @Test
        @DisplayName("no stored quota: the fingerprint is 'none' and a first quota that only limits applies")
        void none() {
            quotaStore.deleteQuota(TENANT);
            assertEquals("none", fingerprint());
            assertEquals(Result.APPLIED, service.changeTenantQuota(null, "none", null, null, null, 25.0, true, "budget", "a", "mcp").result());
            assertNotNull(quotaStore.getQuota(TENANT));
        }

        @Test
        @DisplayName("bounded input")
        void bounded() {
            assertThrows(SpendControlException.class,
                    () -> service.changeTenantQuota(null, fingerprint(), -2, null, null, null, null, "r", "a", "mcp"));
            assertThrows(SpendControlException.class,
                    () -> service.changeTenantQuota(null, fingerprint(), null, null, null, Double.NaN, null, "r", "a", "mcp"));
            assertThrows(SpendControlException.class,
                    () -> service.changeTenantQuota("bad tenant!", fingerprint(), 1, null, null, null, null, "r", "a", "mcp"));
            assertThrows(SpendControlException.class,
                    () -> service.changeTenantQuota(null, fingerprint(), null, null, null, null, null, "r", "a", "mcp"));
        }
    }

    @Test
    @DisplayName("listing expires what is past its time")
    void listExpires() {
        lowerGpt4o();
        clock.advance(Duration.ofDays(8));
        assertTrue(service.list(Status.PENDING, null).isEmpty());
        assertEquals(Status.EXPIRED, service.list(null, null).get(0).status());
        assertNull(service.list(null, null).get(0).decisionNote());
    }

    /** A clock the test moves. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
