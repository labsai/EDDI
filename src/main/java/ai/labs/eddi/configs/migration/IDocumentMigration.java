/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.migration;

import org.bson.Document;

public interface IDocumentMigration {
    Document migrate(Document document);
}
