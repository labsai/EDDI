package io.sls.serialization;

import java.io.IOException;

/**
 * @author ginccc
 */
public interface IDocumentBuilder<T> {
    T build(String doc) throws IOException;
}
