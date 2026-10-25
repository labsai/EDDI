/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.ledger;

import java.util.HashSet;
import java.util.Set;

/**
 * Holds the in-memory double the reply tests use to the stores' contract, so a
 * unit test cannot pass on semantics the real stores do not have.
 */
class InMemoryEmailSuppressionStoreTest implements EmailSuppressionStoreContract {

    private InMemoryEmailSuppressionStore store = new InMemoryEmailSuppressionStore();

    @Override
    public IEmailSuppressionStore store() {
        return store;
    }

    @Override
    public void clear() {
        store = new InMemoryEmailSuppressionStore();
    }

    @Override
    public Set<String> storedValues() {
        Set<String> values = new HashSet<>();
        for (var suppression : store.list("int-1", 100)) {
            values.add(suppression.toString());
        }
        for (var suppression : store.list("int-2", 100)) {
            values.add(suppression.toString());
        }
        return values;
    }
}
