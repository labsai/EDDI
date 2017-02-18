package ai.labs.serialization;

import lombok.extern.slf4j.Slf4j;
import org.bson.Document;

import javax.inject.Inject;
import java.io.IOException;

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
    public <T> T build(Document doc, Class<T> type) throws IOException {
        return jsonSerialization.deserialize(toString(doc), type);
    }

    @Override
    public String toString(Object document) throws IOException {
        return jsonSerialization.serialize(document);
    }
}
