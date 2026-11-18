/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.drive;

import ai.labs.eddi.connections.ResolvedCredential;
import ai.labs.eddi.modules.ingestion.connector.SourceException;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One authenticated GET to the Drive API — the seam a test replaces with a fake
 * Drive, so that everything above it (paging, recursion, error classification,
 * content handling) runs without a network.
 *
 * <p>
 * An HTTP error status is an answer, returned as it is; what it means is
 * decided above this seam, by Drive's error {@code reason}. Only a failure to
 * get any answer — a timeout, a refused connection, a blocked target — is
 * thrown.
 */
@FunctionalInterface
public interface DriveTransport {

    /**
     * @param credential
     *            the header to send, resolved for exactly this URI
     * @param maxBytes
     *            the largest body to accept; a larger one comes back as
     *            {@link DriveResponse#tooLarge()} rather than truncated
     * @throws SourceException
     *             classified, when no response arrived
     */
    DriveResponse get(URI uri, ResolvedCredential credential, long maxBytes) throws SourceException;

    /**
     * What Drive answered.
     *
     * @param tooLarge
     *            the body exceeded the cap and was not read; {@code body} is empty
     */
    record DriveResponse(int status, Map<String, List<String>> headers, byte[] body, boolean tooLarge) {

        public DriveResponse {
            headers = headers == null ? Map.of() : headers;
            body = body == null ? new byte[0] : body;
        }

        /** A response whose body was over the cap. */
        public static DriveResponse ofTooLarge(int status) {
            return new DriveResponse(status, Map.of(), new byte[0], true);
        }

        /** The first value of a header, matched case-insensitively. */
        public Optional<String> header(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                    return Optional.ofNullable(entry.getValue().get(0));
                }
            }
            return Optional.empty();
        }
    }
}
