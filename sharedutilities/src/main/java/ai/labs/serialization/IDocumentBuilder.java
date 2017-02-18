package ai.labs.serialization;

import org.bson.Document;

import java.io.IOException;

/**
 * @author ginccc
 */
public interface IDocumentBuilder {
    <T> T build(Document doc, Class<T> type) throws IOException;

    String toString(Object document) throws IOException;
}
