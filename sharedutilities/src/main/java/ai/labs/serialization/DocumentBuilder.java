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
        String json = prepareJson(doc);
        return jsonSerialization.deserialize(json, type);
    }

    private String prepareJson(Document doc) {
        //workaround due to a bug in mongo java driver (https://jira.mongodb.org/browse/JAVA-2173)
        return doc.toJson().replaceAll("\\$numberLong", "$date");
    }

    @Override
    public String toString(Object document) throws IOException {
        return jsonSerialization.serialize(document);
    }
}
