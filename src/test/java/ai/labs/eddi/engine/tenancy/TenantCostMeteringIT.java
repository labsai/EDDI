/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tenancy;

import ai.labs.eddi.datastore.mongo.MongoTestBase;
import ai.labs.eddi.datastore.postgres.PostgresTestBase;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.tenancy.model.TenantQuota;
import ai.labs.eddi.modules.llm.impl.LlmSpendGate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tenant monthly cost budget end to end on both production datastores
 * (Testcontainers): settled priced turns are charged through
 * {@link TurnCostMeter}, and once the month's total reaches
 * {@code maxMonthlyCostUsd} the pre-call gate every tool call and LLM task asks
 * ({@link TenantQuotaService#checkCostBudget}) refuses.
 * <p>
 * Reuses the containers shared by {@link MongoTestBase} and
 * {@link PostgresTestBase}, like {@code TenantQuotaStoreParityTest}.
 */
class TenantCostMeteringIT extends MongoTestBase {

    private static final AtomicInteger TENANT_SEQ = new AtomicInteger();
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-06-15T12:30:30Z"), ZoneOffset.UTC);

    static Stream<Arguments> stores() {
        Supplier<ITenantQuotaStore> mongo = () -> new MongoTenantQuotaStore(getDatabase(), FIXED_CLOCK);
        Supplier<ITenantQuotaStore> postgres = () -> new PostgresTenantQuotaStore(PostgresTestBase.createDataSourceInstance(), FIXED_CLOCK);
        return Stream.of(Arguments.of("mongo", mongo), Arguments.of("postgres", postgres));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    @DisplayName("two priced turns push the counter past maxMonthlyCostUsd; the next call is refused")
    void twoPricedTurnsExhaustTheBudget(String storeName, Supplier<ITenantQuotaStore> factory) {
        String tenant = "metering-" + storeName + "-" + TENANT_SEQ.incrementAndGet();
        ITenantQuotaStore store = factory.get();
        var quotaService = new TenantQuotaService(store, new SimpleMeterRegistry(), tenant);
        quotaService.setQuota(new TenantQuota(tenant, -1, -1, -1, 0.01, true));
        var meter = new TurnCostMeter(quotaService, new SimpleMeterRegistry());

        assertTrue(quotaService.checkCostBudget(tenant).allowed(), storeName + ": a fresh month allows");

        IConversationMemory first = pricedTurn(0.006);
        meter.meterSettledStep(first);
        // Settling the same step again (a HITL resume with nothing new) charges
        // nothing.
        meter.meterSettledStep(first);
        assertEquals(0.006, store.getMonthlyCost(tenant), 1e-9, storeName + ": one turn charged once");
        assertTrue(quotaService.checkCostBudget(tenant).allowed(), storeName + ": still under the budget");

        meter.meterSettledStep(pricedTurn(0.006));

        assertEquals(0.012, store.getMonthlyCost(tenant), 1e-9, storeName + ": the crossing turn is charged in full");
        assertFalse(quotaService.checkCostBudget(tenant).allowed(), storeName + ": the next call is refused");
        assertNotNull(new LlmSpendGate(quotaService, new SimpleMeterRegistry()).check(first), storeName + ": and so is the next model call");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    @DisplayName("an unpriced turn writes nothing")
    void unpricedTurnWritesNothing(String storeName, Supplier<ITenantQuotaStore> factory) {
        String tenant = "metering-" + storeName + "-" + TENANT_SEQ.incrementAndGet();
        ITenantQuotaStore store = factory.get();
        var quotaService = new TenantQuotaService(store, new SimpleMeterRegistry(), tenant);
        quotaService.setQuota(new TenantQuota(tenant, -1, -1, -1, 0.01, true));

        new TurnCostMeter(quotaService, new SimpleMeterRegistry())
                .meterSettledStep(new ConversationMemory("0123456789abcdef01234567", "agent", 1, "u"));

        assertEquals(0.0, store.getMonthlyCost(tenant), storeName);
    }

    private static IConversationMemory pricedTurn(double cost) {
        IConversationMemory memory = new ConversationMemory("0123456789abcdef01234567", "agent", 1, "user");
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_COST, cost));
        return memory;
    }
}
