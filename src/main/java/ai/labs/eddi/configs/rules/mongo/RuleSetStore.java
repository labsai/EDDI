/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rules.mongo;

import ai.labs.eddi.configs.rules.IRuleSetStore;
import ai.labs.eddi.configs.rules.model.RuleSetConfiguration;
import ai.labs.eddi.configs.rules.model.RuleGroupConfiguration;
import ai.labs.eddi.configs.rules.model.RuleConfiguration;
import ai.labs.eddi.datastore.AbstractResourceStore;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.engine.hitl.lint.ReservedActionLint;
import ai.labs.eddi.utils.RuntimeUtilities;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * @author ginccc
 */
@ApplicationScoped
public class RuleSetStore extends AbstractResourceStore<RuleSetConfiguration> implements IRuleSetStore {

    private static final Logger LOGGER = Logger.getLogger(RuleSetStore.class);

    @Inject
    public RuleSetStore(IResourceStorageFactory storageFactory, IDocumentBuilder documentBuilder) {
        super(storageFactory, "rulesets", documentBuilder, RuleSetConfiguration.class);
    }

    @Override
    public IResourceId create(RuleSetConfiguration behaviorConfiguration) throws ResourceStoreException {
        RuntimeUtilities.checkCollectionNoNullElements(behaviorConfiguration.getBehaviorGroups(), "behaviorGroups");
        lintReservedActionNearMisses(behaviorConfiguration);
        return super.create(behaviorConfiguration);
    }

    @Override
    @ConfigurationUpdate
    public synchronized Integer update(String id, Integer version, RuleSetConfiguration behaviorConfiguration)
            throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException {

        RuntimeUtilities.checkCollectionNoNullElements(behaviorConfiguration.getBehaviorGroups(), "behaviorGroups");
        lintReservedActionNearMisses(behaviorConfiguration);
        return super.update(id, version, behaviorConfiguration);
    }

    /**
     * Non-fatal save-time lint (Task 15): WARNs when an action name closely
     * resembles a reserved action (case-variant or Levenshtein distance &lt;= 2)
     * without being an exact match — almost always a typo, but never blocks the
     * save since a legitimate action may legally resemble the reserved name.
     */
    private void lintReservedActionNearMisses(RuleSetConfiguration behaviorConfiguration) {
        for (String warning : ReservedActionLint.checkReservedActionNearMisses(behaviorConfiguration)) {
            LOGGER.warn("ruleset save: " + sanitize(warning));
        }
    }

    @Override
    public List<String> readActions(String id, Integer version, String filter, Integer limit)
            throws ResourceStoreException, ResourceNotFoundException {

        List<String> actions = read(id, version).getBehaviorGroups().stream().map(RuleGroupConfiguration::getRules).flatMap(Collection::stream)
                .map(RuleConfiguration::getActions).flatMap(Collection::stream).collect(Collectors.toList());

        return limit > 0 ? actions.subList(0, limit) : actions;
    }
}
