package ai.labs.eddi.datastore.serialization;

import org.bson.Document;

import java.io.IOException;
import java.util.Map;

/**
 * @author ginccc
 */
public interface IDocumentBuilder {
    <T> T build(Map doc, Class<T> type) throws IOException;

    String toString(Object document) throws IOException;

    Document toDocument(Object obj) throws IOException;
}
