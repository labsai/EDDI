/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.documents.spend.DocumentSpendStoreContract;
import ai.labs.eddi.engine.documents.spend.IDocumentSpendStore;
import ai.labs.eddi.engine.documents.spend.IDocumentSpendStore.SubjectKind;
import ai.labs.eddi.engine.documents.spend.PostgresDocumentSpendStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PostgresDocumentSpendStore} against a real PostgreSQL
 * (Testcontainers): the shared {@link DocumentSpendStoreContract} (atomic
 * increments under concurrency, retention, erasure) plus the schema.
 */
@DisplayName("PostgresDocumentSpendStore")
class PostgresDocumentSpendStoreIT extends PostgresTestBase implements DocumentSpendStoreContract {

    private static final DataSource DATA_SOURCE = createDataSource();

    private IDocumentSpendStore store;

    @BeforeEach
    void setUp() {
        store = new PostgresDocumentSpendStore(createDataSourceInstance());
    }

    @Override
    public IDocumentSpendStore store() {
        return store;
    }

    @Test
    void createsTheKeyAndTheDayIndex() throws Exception {
        store.spent(SubjectKind.USER, "schema-probe", TODAY); // creates the schema
        List<String> indexes = new ArrayList<>();
        try (Connection conn = DATA_SOURCE.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT indexname FROM pg_indexes WHERE tablename = 'document_spend_days'")) {
            while (rs.next()) {
                indexes.add(rs.getString(1));
            }
        }
        assertTrue(indexes.containsAll(List.of("document_spend_days_pkey", "idx_document_spend_day")), indexes.toString());
    }
}
