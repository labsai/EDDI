/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces.settings;

import ai.labs.eddi.datastore.mongo.MongoTestBase;
import ai.labs.eddi.datastore.postgres.PostgresTestBase;
import ai.labs.eddi.engine.security.spaces.settings.IWorkspaceSettingsStore.StoredWorkspaceSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Cross-store parity for {@link IWorkspaceSettingsStore}: the MongoDB and
 * PostgreSQL implementations must read back exactly what was written.
 * <p>
 * They encode "not set" differently — Mongo leaves the key out of a replaced
 * document, Postgres writes a NULL column — and
 * {@code PUT /workspaces/settings} relies on a write clearing a field it omits.
 * A store that kept the old value would leave a policy in force that the
 * settings page says is gone. Only running the same sequence through both can
 * show they agree.
 * <p>
 * Runs the real adapters against the Testcontainers already shared by
 * {@link MongoTestBase} and {@link PostgresTestBase}, with a fresh tenant per
 * case so the cases cannot see each other.
 */
class WorkspaceSettingsStoreParityTest extends MongoTestBase {

    private static final AtomicInteger TENANT_SEQ = new AtomicInteger();

    /** A millisecond instant: the precision both stores keep. */
    private static final Instant WRITTEN_AT = Instant.parse("2026-11-06T10:15:30.123Z");

    /**
     * Store factories, invoked inside each test so that container startup never
     * happens during argument resolution.
     */
    static Stream<Arguments> stores() {
        Supplier<IWorkspaceSettingsStore> mongo = () -> new MongoWorkspaceSettingsStore(getDatabase());
        Supplier<IWorkspaceSettingsStore> postgres = () -> new PostgresWorkspaceSettingsStore(PostgresTestBase.createDataSourceInstance());
        return Stream.of(Arguments.of("mongo", mongo), Arguments.of("postgres", postgres));
    }

    private static String freshTenant(String storeName) {
        return "parity-" + storeName + "-" + TENANT_SEQ.incrementAndGet();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    @DisplayName("a tenant with nothing stored reads as empty")
    void unknownTenantIsEmpty(String storeName, Supplier<IWorkspaceSettingsStore> factory) {
        assertEquals(Optional.empty(), factory.get().read(freshTenant(storeName)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    @DisplayName("every field round-trips unchanged")
    void fullWriteRoundTrips(String storeName, Supplier<IWorkspaceSettingsStore> factory) {
        IWorkspaceSettingsStore store = factory.get();
        String tenant = freshTenant(storeName);
        var written = new StoredWorkspaceSettings("engineering", "admin-only", WRITTEN_AT, "admin");

        store.write(tenant, written);

        assertEquals(Optional.of(written), store.read(tenant), storeName);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    @DisplayName("an unset field reads back as null, not as an empty string")
    void unsetFieldsReadBackAsNull(String storeName, Supplier<IWorkspaceSettingsStore> factory) {
        IWorkspaceSettingsStore store = factory.get();
        String tenant = freshTenant(storeName);

        store.write(tenant, new StoredWorkspaceSettings(null, null, null, null));

        assertEquals(Optional.of(new StoredWorkspaceSettings(null, null, null, null)), store.read(tenant), storeName);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    @DisplayName("writing null over a stored value clears it, which is how PUT unsets a setting")
    void overwritingWithNullClears(String storeName, Supplier<IWorkspaceSettingsStore> factory) {
        IWorkspaceSettingsStore store = factory.get();
        String tenant = freshTenant(storeName);
        store.write(tenant, new StoredWorkspaceSettings("engineering", "admin-only", WRITTEN_AT, "admin"));

        store.write(tenant, new StoredWorkspaceSettings(null, null, WRITTEN_AT.plusSeconds(60), "root"));

        StoredWorkspaceSettings read = store.read(tenant).orElseThrow();
        assertNull(read.defaultSpace(), storeName + ": the default space must be gone");
        assertNull(read.legacyVisibility(), storeName + ": a cleared admin-only must not stay in force");
        assertEquals(WRITTEN_AT.plusSeconds(60), read.updatedAt(), storeName);
        assertEquals("root", read.updatedBy(), storeName);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    @DisplayName("tenants do not see each other's settings")
    void tenantsAreIsolated(String storeName, Supplier<IWorkspaceSettingsStore> factory) {
        IWorkspaceSettingsStore store = factory.get();
        String first = freshTenant(storeName);
        String second = freshTenant(storeName);

        store.write(first, new StoredWorkspaceSettings("engineering", "admin-only", WRITTEN_AT, "admin"));
        store.write(second, new StoredWorkspaceSettings(null, "shared", WRITTEN_AT, "other"));

        assertEquals("admin-only", store.read(first).orElseThrow().legacyVisibility(), storeName);
        assertEquals("shared", store.read(second).orElseThrow().legacyVisibility(), storeName);
        assertEquals("engineering", store.read(first).orElseThrow().defaultSpace(), storeName);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    @DisplayName("updatedAt keeps the millisecond on both, and only Postgres keeps more")
    void timestampPrecision(String storeName, Supplier<IWorkspaceSettingsStore> factory) {
        // Mongo stores a Date (milliseconds), Postgres a TIMESTAMPTZ (microseconds).
        // The
        // millisecond is what they share, which is why the settings endpoint writes its
        // timestamp truncated to it: what this instance adopts is then what any replica
        // reads back, on either backend.
        IWorkspaceSettingsStore store = factory.get();
        String tenant = freshTenant(storeName);
        // Sub-microsecond digits below one half, so rounding and truncating to the
        // microsecond agree and the assertion does not depend on which the driver does.
        Instant fine = Instant.parse("2026-11-06T10:15:30.123456400Z");

        store.write(tenant, new StoredWorkspaceSettings(null, null, fine, "admin"));

        Instant read = store.read(tenant).orElseThrow().updatedAt();
        assertEquals(fine.truncatedTo(ChronoUnit.MILLIS), read.truncatedTo(ChronoUnit.MILLIS), storeName);
        assertEquals("mongo".equals(storeName) ? fine.truncatedTo(ChronoUnit.MILLIS) : fine.truncatedTo(ChronoUnit.MICROS), read,
                storeName);
    }
}
