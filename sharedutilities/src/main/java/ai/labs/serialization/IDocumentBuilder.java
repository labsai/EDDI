package ai.labs.serialization;

import java.io.IOException;

/**
 * @author ginccc
 */
public interface IDocumentBuilder {
    <T> T build(String doc, Class<T> type) throws IOException;

    String toString(Object document) throws IOException;
}
