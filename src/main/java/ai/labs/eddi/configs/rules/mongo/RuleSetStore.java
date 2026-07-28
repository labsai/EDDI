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
import ai.labs.eddi.datastore.serialization.DeserializationException;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.engine.hitl.lint.ReservedActionLint;
import ai.labs.eddi.modules.rules.impl.IRuleDeserialization;
import ai.labs.eddi.utils.RuntimeUtilities;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * @author ginccc
 */
@ApplicationScoped
public class RuleSetStore extends AbstractResourceStore<RuleSetConfiguration> implements IRuleSetStore {

    private static final Logger LOGGER = Logger.getLogger(RuleSetStore.class);

    private final IDocumentBuilder documentBuilder;
    private final IRuleDeserialization ruleDeserialization;

    @Inject
    public RuleSetStore(IResourceStorageFactory storageFactory, IDocumentBuilder documentBuilder, IRuleDeserialization ruleDeserialization) {
        super(storageFactory, "rulesets", documentBuilder, RuleSetConfiguration.class);
        this.documentBuilder = documentBuilder;
        this.ruleDeserialization = ruleDeserialization;
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
     * Hoists the structural rule-condition checks from agent-deploy time to save
     * time.
     * <p>
     * The checks themselves are not reimplemented here: the ruleset is run through
     * the very same {@link IRuleDeserialization} the pipeline uses at
     * {@code configure()} time, so an empty {@code actionmatcher}, an unknown
     * {@code occurrence} value, a {@code contextmatcher} whose {@code contextType}
     * does not match its value field, an unknown condition {@code type} and an
     * unknown {@code executionStrategy} are all reported by the existing
     * validation, with the offending rule named. What changes is only <em>when</em>
     * the author hears about it: a 400 on save instead of a 201 followed by an
     * agent that cannot be deployed.
     */
    @Override
    protected void validate(RuleSetConfiguration behaviorConfiguration) {
        if (behaviorConfiguration == null || isNullOrEmpty(behaviorConfiguration.getBehaviorGroups())) {
            return;
        }

        String json;
        try {
            json = documentBuilder.toString(behaviorConfiguration);
        } catch (IOException e) {
            throw new IllegalArgumentException("Ruleset could not be serialized for validation: " + e.getMessage(), e);
        }

        try {
            ruleDeserialization.deserialize(json);
        } catch (DeserializationException e) {
            throw new IllegalArgumentException("Invalid ruleset: " + e.getMessage(), e);
        }
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

        return limit > 0 ? actions.subList(0, Math.min(limit, actions.size())) : actions;
    }
}
