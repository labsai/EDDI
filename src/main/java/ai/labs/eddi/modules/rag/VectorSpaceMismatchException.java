/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rag;

/**
 * A knowledge base was asked to search or write in a vector space other than
 * the one its vector store holds, or an embedding does not have the size the
 * store was made for.
 *
 * <p>
 * Refused rather than carried out because the alternative is silent: old and
 * new vectors would sit side by side, every search would return plausible hits
 * ranked by meaningless scores, and a vector of another size would only surface
 * as a driver error with SQL in it. The message is fixed text built from
 * provider and model names — safe to show to a client, and stable enough to
 * match on ({@link #CODE}).
 */
public class VectorSpaceMismatchException extends IllegalStateException {

    /** What a REST answer carries as {@code code} for this refusal. */
    public static final String CODE = "VECTOR_SPACE_MISMATCH";

    public VectorSpaceMismatchException(String message) {
        super(message);
    }
}
