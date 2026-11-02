/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tenancy;

import ai.labs.eddi.datastore.mongo.MongoTestBase;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import de.undercouch.bson4jackson.BsonFactory;
import de.undercouch.bson4jackson.BsonParser;
import ai.labs.eddi.datastore.mongo.codec.JacksonProvider;
import ai.labs.eddi.datastore.postgres.PostgresConversationMemoryStore;
import ai.labs.eddi.engine.memory.ConversationMemoryStore;
import ai.labs.eddi.engine.memory.ConversationMemoryUtilities;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import org.bson.codecs.configuration.CodecRegistries;
import ai.labs.eddi.datastore.postgres.PostgresTestBase;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.engine.tenancy.model.TenantQuota;
import ai.labs.eddi.modules.llm.impl.LlmSpendGate;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.DisplayName;
import org.mockito.AdditionalAnswers;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
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

    static Stream<Arguments> storePairs() {
        Supplier<ITenantQuotaStore> mongoQuota = () -> new MongoTenantQuotaStore(getDatabase(), FIXED_CLOCK);
        // The production codec (PersistenceModule) is BSON-aware; the shared test
        // ObjectMapper is not, and cannot read a stored snapshot back.
        Supplier<IConversationMemoryStore> mongoMemory = () -> new ConversationMemoryStore(getDatabase().withCodecRegistry(
                CodecRegistries.fromRegistries(getDatabase().getCodecRegistry(), CodecRegistries.fromProviders(new JacksonProvider(bsonMapper())))));
        Supplier<ITenantQuotaStore> postgresQuota = () -> new PostgresTenantQuotaStore(PostgresTestBase.createDataSourceInstance(), FIXED_CLOCK);
        Supplier<IConversationMemoryStore> postgresMemory = () -> new PostgresConversationMemoryStore(PostgresTestBase.createDataSourceInstance(),
                jsonSerialization);
        return Stream.of(Arguments.of("mongo", mongoQuota, mongoMemory), Arguments.of("postgres", postgresQuota, postgresMemory));
    }

    /**
     * Exactly-once across a pause, with the memory going through the real
     * conversation store in between — the way a HITL resume finds it: the paused
     * turn's settle charges the pre-pause cost and writes audit:metered_cost onto
     * the step, the step is persisted (AWAITING_HUMAN) and loaded back, the resume
     * accumulates further cost into the SAME step, and its settle may charge only
     * the difference. If the marker did not survive the store round trip, the
     * counter would read step total + pre-pause part.
     * <p>
     * The order "settle callback, then persist" that this relies on is pinned on
     * the service side by {@code ConversationServiceCostMeteringTest}, which
     * asserts the marker is on the snapshot handed to the store.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("storePairs")
    @DisplayName("pause -> persist -> reload -> resume: the tenant is charged the step total exactly once")
    void pausedTurnReloadedFromTheStoreIsChargedOnce(String storeName, Supplier<ITenantQuotaStore> quotaFactory,
                                                     Supplier<IConversationMemoryStore> memoryFactory)
            throws Exception {
        String tenant = "metering-" + storeName + "-" + TENANT_SEQ.incrementAndGet();
        ITenantQuotaStore quotaStore = quotaFactory.get();
        var quotaService = new TenantQuotaService(quotaStore, new SimpleMeterRegistry(), tenant);
        quotaService.setQuota(new TenantQuota(tenant, -1, -1, -1, 1.0, true));
        var meter = new TurnCostMeter(quotaService, new SimpleMeterRegistry());
        IConversationMemoryStore memoryStore = memoryFactory.get();

        // The turn spends $0.004 before it pauses for approval; the pause settles it.
        IConversationMemory paused = new ConversationMemory("0123456789abcdef01234567", 1, "user");
        paused.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_COST, 0.004));
        meter.meterSettledStep(paused);
        paused.setConversationState(ConversationState.AWAITING_HUMAN);
        String conversationId = memoryStore.storeConversationMemorySnapshot(ConversationMemoryUtilities.convertConversationMemory(paused));

        ConversationMemorySnapshot persistedPause = memoryStore.loadConversationMemorySnapshot(conversationId);
        assertEquals(0.004, meteredCostOn(persistedPause), 1e-12, storeName + ": the persisted step records what was charged");

        // Resume: loaded from the store, the same step accumulates to $0.007.
        IConversationMemory resumed = ConversationMemoryUtilities.convertConversationMemorySnapshot(persistedPause);
        resumed.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_COST, 0.007));
        meter.meterSettledStep(resumed);
        resumed.setConversationState(ConversationState.READY);
        memoryStore.storeConversationMemorySnapshot(ConversationMemoryUtilities.convertConversationMemory(resumed));

        assertEquals(0.007, quotaStore.getMonthlyCost(tenant), 1e-9,
                storeName + ": the step total exactly once, not total + the pre-pause part");
        assertEquals(0.007, meteredCostOn(memoryStore.loadConversationMemorySnapshot(conversationId)), 1e-12,
                storeName + ": the marker follows the step total after the resume");
    }

    /**
     * A charge the quota store could not record does not vanish when the next say
     * turn starts a new step. Say turns are persisted as appends — an earlier step
     * is never rewritten — so the pending amount must travel on the newest step;
     * this proves it does, through the real conversation store.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("storePairs")
    @DisplayName("a charge lost to a quota-store outage is recovered by the next turn, through the real conversation store")
    void outageChargeIsRecoveredByTheNextTurn(String storeName, Supplier<ITenantQuotaStore> quotaFactory,
                                              Supplier<IConversationMemoryStore> memoryFactory)
            throws Exception {
        String tenant = "metering-" + storeName + "-" + TENANT_SEQ.incrementAndGet();
        ITenantQuotaStore quotaStore = spy(quotaFactory.get());
        // The first charge hits an outage; the store answers normally afterwards.
        doReturn(ITenantQuotaStore.accountingUnavailable()).doCallRealMethod().when(quotaStore).tryAddCost(eq(tenant), anyDouble(),
                anyDouble());
        var quotaService = new TenantQuotaService(quotaStore, new SimpleMeterRegistry(), tenant);
        quotaService.setQuota(new TenantQuota(tenant, -1, -1, -1, 1.0, true));
        var meter = new TurnCostMeter(quotaService, new SimpleMeterRegistry());
        IConversationMemoryStore memoryStore = memoryFactory.get();

        IConversationMemory first = new ConversationMemory("0123456789abcdef01234567", 1, "user");
        first.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_COST, 0.004));
        meter.meterSettledStep(first);
        assertEquals(0.0, quotaStore.getMonthlyCost(tenant), 1e-12, storeName + ": nothing recorded during the outage");
        first.setConversationState(ConversationState.READY); // a settled say turn
        String conversationId = memoryStore.storeConversationMemorySnapshot(ConversationMemoryUtilities.convertConversationMemory(first));

        // Next say turn: reloaded, a new step, its own cost.
        IConversationMemory second = ConversationMemoryUtilities
                .convertConversationMemorySnapshot(memoryStore.loadConversationMemorySnapshot(conversationId));
        // As the service loads a turn: the append is checked against the stored
        // revision.
        second.setRevision(memoryStore.getRevision(conversationId));
        ((ConversationMemory) second).startNextStep();
        second.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_COST, 0.002));
        meter.meterSettledStep(second);
        memoryStore.storeConversationMemorySnapshot(ConversationMemoryUtilities.convertConversationMemory(second));

        assertEquals(0.006, quotaStore.getMonthlyCost(tenant), 1e-9, storeName + ": the lost $0.004 is charged with the next turn");
    }

    /**
     * say → undo → say through the real conversation store: the undo is persisted
     * as a full replace (the step moves into the stored redo cache), and the next
     * say discards it. The spend the discarded step carried — and a tenant charge
     * still pending on it — must reach the store on the new step, or a public
     * {@code /undo} resets the conversation's cost ceiling and loses the charge.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("storePairs")
    @DisplayName("say -> undo -> say, persisted between each: the spend and a pending charge survive on the new step")
    void undoThenSayKeepsSpendThroughTheStore(String storeName, Supplier<ITenantQuotaStore> quotaFactory,
                                              Supplier<IConversationMemoryStore> memoryFactory)
            throws Exception {
        String tenant = "metering-" + storeName + "-" + TENANT_SEQ.incrementAndGet();
        ITenantQuotaStore quotaStore = spy(quotaFactory.get());
        // The priced turn's charge hits an outage; the store answers normally
        // afterwards.
        doReturn(ITenantQuotaStore.accountingUnavailable()).doCallRealMethod().when(quotaStore).tryAddCost(eq(tenant), anyDouble(),
                anyDouble());
        var quotaService = new TenantQuotaService(quotaStore, new SimpleMeterRegistry(), tenant);
        quotaService.setQuota(new TenantQuota(tenant, -1, -1, -1, 1.0, true));
        var meter = new TurnCostMeter(quotaService, new SimpleMeterRegistry());
        IConversationMemoryStore memoryStore = memoryFactory.get();

        // Turn 1 (unpriced), then turn 2 spends $0.004 and its charge is left pending.
        IConversationMemory memory = new ConversationMemory("0123456789abcdef01234567", 1, "user");
        memory.setConversationState(ConversationState.READY);
        String conversationId = memoryStore.storeConversationMemorySnapshot(ConversationMemoryUtilities.convertConversationMemory(memory));
        memory = reload(memoryStore, conversationId);
        ((ConversationMemory) memory).startNextStep();
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_COST, 0.004));
        meter.meterSettledStep(memory);
        memoryStore.storeConversationMemorySnapshot(ConversationMemoryUtilities.convertConversationMemory(memory));

        // Undo, persisted.
        memory = reload(memoryStore, conversationId);
        memory.undoLastStep();
        memoryStore.storeConversationMemorySnapshot(ConversationMemoryUtilities.convertConversationMemory(memory));

        // The next say discards the undone step; an unpriced turn, the quota store is
        // back.
        memory = reload(memoryStore, conversationId);
        ((ConversationMemory) memory).startNextStep();
        meter.meterSettledStep(memory);
        memoryStore.storeConversationMemorySnapshot(ConversationMemoryUtilities.convertConversationMemory(memory));

        IConversationMemory stored = reload(memoryStore, conversationId);
        stored.setConversationCostCeilingUsd(0.004);
        LlmSpendGate.Refusal refusal = new LlmSpendGate(quotaService, new SimpleMeterRegistry()).check(stored);
        assertNotNull(refusal, storeName + ": the undone turn's spend still counts against the ceiling");
        assertEquals("conversation_ceiling", refusal.reason(), storeName);
        assertEquals(0.004, quotaStore.getMonthlyCost(tenant), 1e-9, storeName + ": the charge pending on the undone step was recorded");
    }

    private static IConversationMemory reload(IConversationMemoryStore memoryStore, String conversationId) throws Exception {
        IConversationMemory memory = ConversationMemoryUtilities
                .convertConversationMemorySnapshot(memoryStore.loadConversationMemorySnapshot(conversationId));
        memory.setRevision(memoryStore.getRevision(conversationId));
        return memory;
    }

    /**
     * A store that works until {@code down} is set, then fails the way its driver
     * does when the database goes away — after the schema is initialised and the
     * tenant's quota row is cached, which is the state a running deployment is in.
     */
    record BreakableStore(ITenantQuotaStore store, AtomicBoolean down) {
    }

    static Stream<Arguments> breakableStores() {
        Supplier<BreakableStore> mongo = () -> {
            AtomicBoolean down = new AtomicBoolean();
            MongoDatabase real = getDatabase();
            MongoDatabase database = mock(MongoDatabase.class, invocation -> {
                Object result = AdditionalAnswers.delegatesTo(real).answer(invocation);
                if (result instanceof MongoCollection<?> collection) {
                    return mock(MongoCollection.class, call -> {
                        if (down.get()) {
                            throw new MongoTimeoutException("Timed out while waiting for a server");
                        }
                        return AdditionalAnswers.delegatesTo(collection).answer(call);
                    });
                }
                return result;
            });
            return new BreakableStore(new MongoTenantQuotaStore(database, FIXED_CLOCK), down);
        };
        Supplier<BreakableStore> postgres = () -> {
            AtomicBoolean down = new AtomicBoolean();
            DataSource real = PostgresTestBase.createDataSourceInstance().get();
            DataSource dataSource = mock(DataSource.class, invocation -> {
                if (down.get() && "getConnection".equals(invocation.getMethod().getName())) {
                    throw new SQLException("Connection refused", "08001");
                }
                return AdditionalAnswers.delegatesTo(real).answer(invocation);
            });
            @SuppressWarnings("unchecked")
            Instance<DataSource> instance = mock(Instance.class);
            when(instance.get()).thenReturn(dataSource);
            return new BreakableStore(new PostgresTenantQuotaStore(instance, FIXED_CLOCK), down);
        };
        return Stream.of(Arguments.of("mongo", mongo), Arguments.of("postgres", postgres));
    }

    /**
     * The pre-call cost gate on a store that has lost its database. Both backends
     * must refuse as an accounting outage: PostgreSQL used to read it as $0 spent
     * and allow the call (fail open), MongoDB let the driver exception escape from
     * the gate, which LlmTask asks outside its guarded model phase — the turn
     * failed instead of serving the fallback answer.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("breakableStores")
    @DisplayName("a monthly-cost read that fails refuses the call as accounting-unavailable on both backends — no bypass, no throw")
    void lostDatabaseAtTheGateFailsClosed(String storeName, Supplier<BreakableStore> factory) {
        String tenant = "metering-" + storeName + "-" + TENANT_SEQ.incrementAndGet();
        BreakableStore breakable = factory.get();
        var quotaService = new TenantQuotaService(breakable.store(), new SimpleMeterRegistry(), tenant);
        quotaService.setQuota(new TenantQuota(tenant, -1, -1, -1, 1.0, true));
        var gate = new LlmSpendGate(quotaService, new SimpleMeterRegistry());
        assertTrue(quotaService.checkCostBudget(tenant).allowed(), storeName + ": healthy, and the quota row is now cached");

        breakable.down().set(true);

        QuotaCheckResult result = assertDoesNotThrow(() -> quotaService.checkCostBudget(tenant), storeName);
        assertFalse(result.allowed(), storeName + ": fail closed, not $0 spent");
        assertTrue(result.accountingUnavailable(), storeName + ": refused as an outage, not as a spent budget");
        assertEquals(ITenantQuotaStore.ACCOUNTING_UNAVAILABLE, result.reason(), storeName + ": the same answer on both backends");

        LlmSpendGate.Refusal refusal = assertDoesNotThrow(() -> gate.check(pricedTurn(0.0)), storeName);
        assertNotNull(refusal, storeName + ": the model call is refused");
        assertEquals("tenant_accounting_unavailable", refusal.reason(), storeName + ": and LlmTask serves its fallback answer");
    }

    private static double meteredCostOn(ConversationMemorySnapshot snapshot) {
        var lastStep = snapshot.getConversationSteps().getLast();
        return lastStep.getWorkflows().stream().flatMap(w -> w.getLifecycleTasks().stream())
                .filter(r -> MemoryKeys.AUDIT_METERED_COST.equals(r.getKey())).map(r -> ((Number) r.getResult()).doubleValue()).findFirst()
                .orElseThrow(() -> new AssertionError("no audit:metered_cost on the persisted step"));
    }

    private static ObjectMapper bsonMapper() {
        BsonFactory bsonFactory = new BsonFactory();
        bsonFactory.enable(BsonParser.Feature.HONOR_DOCUMENT_LENGTH);
        var mapper = new ObjectMapper(bsonFactory);
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        new SerializationCustomizer(false).customize(mapper);
        return mapper;
    }

    private static IConversationMemory pricedTurn(double cost) {
        IConversationMemory memory = new ConversationMemory("0123456789abcdef01234567", "agent", 1, "user");
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_COST, cost));
        return memory;
    }
}
