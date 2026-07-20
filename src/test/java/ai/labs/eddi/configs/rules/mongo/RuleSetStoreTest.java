/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rules.mongo;

import ai.labs.eddi.configs.rules.model.RuleConfiguration;
import ai.labs.eddi.configs.rules.model.RuleGroupConfiguration;
import ai.labs.eddi.configs.rules.model.RuleSetConfiguration;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RuleSetStore} — in particular the Task 15 save-time
 * reserved-action near-miss lint hooked into create/update. The lint is
 * strictly non-fatal: it must never prevent a legitimate save, even when the
 * ruleset contains an action name that happens to resemble a reserved action.
 */
@DisplayName("RuleSetStore")
class RuleSetStoreTest {

    private IResourceStorage<RuleSetConfiguration> resourceStorage;
    private RuleSetStore ruleSetStore;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        var storageFactory = mock(IResourceStorageFactory.class);
        var documentBuilder = mock(IDocumentBuilder.class);
        resourceStorage = mock(IResourceStorage.class);

        when(storageFactory.create(eq("rulesets"), any(), eq(RuleSetConfiguration.class), any(String[].class)))
                .thenReturn(resourceStorage);

        ruleSetStore = new RuleSetStore(storageFactory, documentBuilder);
    }

    private RuleSetConfiguration ruleSetWithActions(String... actions) {
        var rule = new RuleConfiguration();
        rule.setName("r1");
        rule.setActions(List.of(actions));

        var group = new RuleGroupConfiguration();
        group.setRules(List.of(rule));

        var ruleSet = new RuleSetConfiguration();
        ruleSet.setBehaviorGroups(List.of(group));
        return ruleSet;
    }

    @SuppressWarnings("unchecked")
    private void stubSuccessfulCreate() throws Exception {
        var createdResource = mock(IResourceStorage.IResource.class);
        when(resourceStorage.newResource(org.mockito.ArgumentMatchers.any(RuleSetConfiguration.class)))
                .thenReturn(createdResource);
    }

    @SuppressWarnings("unchecked")
    private void stubSuccessfulUpdate(String id, int version) throws Exception {
        var existingResource = mock(IResourceStorage.IResource.class);
        when(existingResource.getId()).thenReturn(id);
        when(existingResource.getVersion()).thenReturn(version);
        when(resourceStorage.read(id, version)).thenReturn(existingResource);

        var historyResource = mock(IResourceStorage.IHistoryResource.class);
        when(resourceStorage.newHistoryResourceFor(existingResource, false)).thenReturn(historyResource);

        var newResource = mock(IResourceStorage.IResource.class);
        when(resourceStorage.newResource(eq(id), eq(version + 1), org.mockito.ArgumentMatchers.any(RuleSetConfiguration.class)))
                .thenReturn(newResource);
    }

    @Nested
    @DisplayName("create — Task 15 near-miss lint")
    class CreateLint {

        @Test
        @DisplayName("near-miss action name does not prevent create from succeeding")
        void nearMissDoesNotBlockCreate() throws Exception {
            stubSuccessfulCreate();
            var ruleSet = ruleSetWithActions("PAUSE_CONVERSATON");

            assertDoesNotThrow(() -> ruleSetStore.create(ruleSet));
        }

        @Test
        @DisplayName("exact reserved action name does not prevent create from succeeding")
        void exactMatchDoesNotBlockCreate() throws Exception {
            stubSuccessfulCreate();
            var ruleSet = ruleSetWithActions("PAUSE_CONVERSATION");

            assertDoesNotThrow(() -> ruleSetStore.create(ruleSet));
        }

        @Test
        @DisplayName("unrelated action name does not prevent create from succeeding")
        void unrelatedActionDoesNotBlockCreate() throws Exception {
            stubSuccessfulCreate();
            var ruleSet = ruleSetWithActions("greet_user");

            assertDoesNotThrow(() -> ruleSetStore.create(ruleSet));
        }

        @Test
        @DisplayName("null behaviorGroups still throws the pre-existing validation error (unrelated to the lint)")
        void nullBehaviorGroupsStillRejected() {
            var ruleSet = new RuleSetConfiguration();
            ruleSet.setBehaviorGroups(null);

            assertThrows(IllegalArgumentException.class, () -> ruleSetStore.create(ruleSet));
        }
    }

    @Nested
    @DisplayName("update — Task 15 near-miss lint")
    class UpdateLint {

        @Test
        @DisplayName("near-miss action name does not prevent update from succeeding")
        void nearMissDoesNotBlockUpdate() throws Exception {
            stubSuccessfulUpdate("id1", 1);
            var ruleSet = ruleSetWithActions("pause_conversation");

            assertDoesNotThrow(() -> ruleSetStore.update("id1", 1, ruleSet));
        }

        @Test
        @DisplayName("unrelated action name does not prevent update from succeeding")
        void unrelatedActionDoesNotBlockUpdate() throws Exception {
            stubSuccessfulUpdate("id1", 1);
            var ruleSet = ruleSetWithActions("greet_user");

            assertDoesNotThrow(() -> ruleSetStore.update("id1", 1, ruleSet));
        }
    }
}
