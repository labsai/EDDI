/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.gdpr.ErasedSubjectStoreContract;
import ai.labs.eddi.engine.gdpr.IErasedSubjectStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

import javax.sql.DataSource;
import java.util.List;

/**
 * {@link PostgresErasedSubjectStore} against a real PostgreSQL
 * (Testcontainers).
 */
@DisplayName("Erased-subject store (PostgreSQL)")
class PostgresErasedSubjectStoreIT extends PostgresTestBase implements ErasedSubjectStoreContract {

    private static final DataSource DATA_SOURCE = createDataSource();

    private PostgresErasedSubjectStore store;

    @BeforeEach
    void setUp() throws Exception {
        store = new PostgresErasedSubjectStore(createDataSourceInstance());
        // The first call creates the schema; then start from an empty table.
        store.erasedAt(List.of("init"));
        truncateTables(DATA_SOURCE, "gdpr_erased_subjects");
    }

    @Override
    public IErasedSubjectStore erasedSubjects() {
        return store;
    }
}
