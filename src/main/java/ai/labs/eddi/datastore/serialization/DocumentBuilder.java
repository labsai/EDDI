package ai.labs.eddi.datastore.serialization;

import org.bson.Document;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.Map;

/**
 * @author ginccc
 */

@ApplicationScoped
public class DocumentBuilder implements IDocumentBuilder {
    private final IJsonSerialization jsonSerialization;

    @Inject
    public DocumentBuilder(IJsonSerialization jsonSerialization) {
        this.jsonSerialization = jsonSerialization;
    }

    @Override
    public <T> T build(Map doc, Class<T> type) throws IOException {
        return jsonSerialization.deserialize(toString(doc), type);
    }

    @Override
    public String toString(Object document) throws IOException {
        return jsonSerialization.serialize(document);
    }

    @Override
    public Document toDocument(Object obj) throws IOException {
        return jsonSerialization.deserialize(toString(obj), Document.class);
    }
}
