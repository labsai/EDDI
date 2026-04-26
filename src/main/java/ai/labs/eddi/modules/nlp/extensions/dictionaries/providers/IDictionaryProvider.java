/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.extensions.dictionaries.providers;

import ai.labs.eddi.modules.nlp.extensions.IParserExtensionProvider;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;

/**
 * @author ginccc
 */
public interface IDictionaryProvider extends IParserExtensionProvider<IDictionary> {
    String ID = "ai.labs.parser.dictionaries";
}
