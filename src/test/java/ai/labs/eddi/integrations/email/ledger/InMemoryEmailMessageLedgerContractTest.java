/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.ledger;

import java.util.Set;

/**
 * {@link EmailMessageLedgerContract} against
 * {@link InMemoryEmailMessageLedger}: the fake the processor and sender tests
 * run on must behave like the stores they stand in for. The stores themselves
 * are held to it through a real database by {@link MongoEmailMessageLedgerIT}
 * and {@link PostgresEmailMessageLedgerIT}.
 */
class InMemoryEmailMessageLedgerContractTest implements EmailMessageLedgerContract {

    private InMemoryEmailMessageLedger ledger = new InMemoryEmailMessageLedger();

    @Override
    public IEmailMessageLedger ledger() {
        return ledger;
    }

    @Override
    public void clear() {
        ledger = new InMemoryEmailMessageLedger();
    }

    /** A record in memory has exactly the record's fields. */
    @Override
    public Set<String> storedFieldNames() {
        return ALLOWED_FIELDS;
    }
}
