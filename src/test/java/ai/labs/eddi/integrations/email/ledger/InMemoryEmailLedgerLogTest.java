/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.ledger;

/**
 * {@link EmailLedgerLogContract} against {@link InMemoryEmailMessageLedger}:
 * the fake the processor and REST tests use must list and count the way the
 * stores do.
 */
class InMemoryEmailLedgerLogTest implements EmailLedgerLogContract {

    private InMemoryEmailMessageLedger ledger = new InMemoryEmailMessageLedger();

    @Override
    public IEmailMessageLedger ledger() {
        return ledger;
    }

    @Override
    public void clear() {
        ledger = new InMemoryEmailMessageLedger();
    }
}
