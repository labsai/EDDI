/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.parser;

import ai.labs.eddi.configs.parser.model.ParserConfiguration;
import ai.labs.eddi.datastore.IResourceStore;

/**
 * Store for parser configurations. Deliberately declares no extra methods:
 * keeping a dedicated interface lets the generic configuration machinery
 * resolve the parser store by type, the same way it does for the other
 * configuration kinds.
 */
public interface IParserStore extends IResourceStore<ParserConfiguration> {
    // class for reflection purposes
}
