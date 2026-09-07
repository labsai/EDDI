/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rules;

import ai.labs.eddi.configs.rules.model.RuleSetConfiguration;
import ai.labs.eddi.datastore.IResourceStore;

import java.util.List;

/**
 * Persistence for behaviour rule sets, the rules that decide how an agent
 * reacts to what a user said. Consumed by the deployment machinery and the REST
 * resource; readActions() lists the actions a rule set version can trigger,
 * which the editor uses for completion.
 */
public interface IRuleSetStore extends IResourceStore<RuleSetConfiguration> {
    List<String> readActions(String id, Integer version, String filter, Integer limit)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;
}
