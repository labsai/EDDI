/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections;

/**
 * One header, ready to put on an outbound request.
 * <p>
 * A header and not a bare string, because the header NAME is part of what a
 * connection knows: {@code Authorization} for one provider, {@code X-Api-Key}
 * for the next, and making the call site guess is how a credential ends up in
 * the wrong field and silently ignored by the provider.
 * <p>
 * Never logged, never persisted, never returned by a REST endpoint. Its
 * lifetime is one outbound request.
 *
 * @param headerName
 *            e.g. {@code Authorization}
 * @param headerValue
 *            the resolved value, e.g. {@code Bearer ya29.…}
 */
public record ResolvedCredential(String headerName, String headerValue) {

    /**
     * Deliberately does not include the value.
     * <p>
     * This record travels through debug logs, exception messages and IDE
     * inspections; the default record {@code toString} would print the credential
     * in all three.
     */
    @Override
    public String toString() {
        return "ResolvedCredential[headerName=" + headerName + ", headerValue=<REDACTED>]";
    }
}
