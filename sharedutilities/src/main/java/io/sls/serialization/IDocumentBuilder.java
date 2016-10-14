package io.sls.serialization;

import java.io.IOException;

/**
 * User: jarisch
 * Date: 20.11.12
 * Time: 14:14
 */
public interface IDocumentBuilder<T> {
    T build(String doc) throws IOException;
}
