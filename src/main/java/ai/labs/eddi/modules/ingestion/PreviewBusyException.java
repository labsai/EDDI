/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

/**
 * Too many previews are already crawling.
 *
 * <p>
 * A preview holds a request thread for the length of a crawl, so an unbounded
 * number of them takes the request pool with it. This is a "come back in a
 * moment", not a failure of the source, and the REST layer answers 429.
 */
public class PreviewBusyException extends RuntimeException {

    public PreviewBusyException(String message) {
        super(message);
    }
}
