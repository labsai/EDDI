/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.migration;

import org.bson.Document;

public interface IDocumentMigration {
    Document migrate(Document document);
}
