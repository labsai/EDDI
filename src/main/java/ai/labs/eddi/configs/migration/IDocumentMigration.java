package ai.labs.eddi.configs.migration;

import org.bson.Document;

public interface IDocumentMigration {
    Document migrate(Document document);
}
