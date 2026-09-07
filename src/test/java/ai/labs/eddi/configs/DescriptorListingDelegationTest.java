/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.dictionary.IDictionaryStore;
import ai.labs.eddi.configs.dictionary.rest.RestDictionaryStore;
import ai.labs.eddi.configs.rules.IRuleSetStore;
import ai.labs.eddi.configs.rules.rest.RestRuleSetStore;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * The runtime half of the D1 regression net: the descriptor store must be
 * queried with the store's own URI namespace, not a legacy name.
 * <p>
 * {@code DescriptorTypeConsistencyTest} sweeps the <em>sources</em>, which
 * kills hard-coded literals — but it cannot see a bug in the derivation itself,
 * and it proves nothing about what reaches the descriptor store at runtime.
 * These two stores are the ones that shipped broken (queried
 * {@code ai.labs.behavior} and {@code ai.labs.regulardictionary}, matched
 * nothing, and listed {@code []} forever while their resources existed); the
 * apicalls store — the third — has this same pin in
 * {@code RestApiCallsStoreDelegationTest}.
 */
@DisplayName("descriptor listings query the derived namespace at runtime")
class DescriptorListingDelegationTest {

    @Test
    @DisplayName("rulesets list under ai.labs.rules — the namespace their URIs actually carry")
    void ruleSetListingUsesTheRulesNamespace() throws Exception {
        var documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        var store = new RestRuleSetStore(mock(IRuleSetStore.class), documentDescriptorStore, mock(IJsonSchemaCreator.class),
                mock(ResourceAccessGuard.class));
        List<DocumentDescriptor> expected = List.of(new DocumentDescriptor());
        doReturn(expected).when(documentDescriptorStore).readDescriptors(eq("ai.labs.rules"), eq("f"), eq(0), eq(10), eq(false), any());

        assertEquals(expected, store.readBehaviorDescriptors("f", 0, 10),
                "querying any other namespace returns an empty list forever, with no error");
    }

    @Test
    @DisplayName("dictionaries list under ai.labs.dictionary")
    void dictionaryListingUsesTheDictionaryNamespace() throws Exception {
        var documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        var store = new RestDictionaryStore(mock(IDictionaryStore.class), documentDescriptorStore,
                mock(IJsonSchemaCreator.class), mock(ResourceAccessGuard.class));
        List<DocumentDescriptor> expected = List.of(new DocumentDescriptor());
        doReturn(expected).when(documentDescriptorStore).readDescriptors(eq("ai.labs.dictionary"), eq("f"), eq(0), eq(10), eq(false), any());

        assertEquals(expected, store.readRegularDictionaryDescriptors("f", 0, 10));
    }
}
