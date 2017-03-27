package ai.labs.serialization;

import java.io.IOException;
import java.util.Map;

/**
 * @author ginccc
 */
public interface IDocumentBuilder {
    <T> T build(Map doc, Class<T> type) throws IOException;

    String toString(Object document) throws IOException;
}
