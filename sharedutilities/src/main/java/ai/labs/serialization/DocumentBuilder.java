package ai.labs.serialization;

import lombok.extern.slf4j.Slf4j;
import org.bson.Document;

import javax.inject.Inject;
import java.io.IOException;
import java.util.Map;

/**
 * @author ginccc
 */
@Slf4j
public class DocumentBuilder implements IDocumentBuilder {
    private IJsonSerialization jsonSerialization;

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
