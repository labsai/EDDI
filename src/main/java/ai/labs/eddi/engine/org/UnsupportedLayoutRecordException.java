/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

/**
 * The layout record was read, but this binary cannot interpret it: an unknown
 * marker state (one a later release may have added), a layout level that is not
 * a whole number in range, or a negative one.
 * <p>
 * {@link DataLayoutGuard} refuses to start on it at once instead of retrying: a
 * record that cannot be interpreted is not an outage, and treating it as level
 * 0 would fail open.
 */
public class UnsupportedLayoutRecordException extends OrgSecurityException {

    public UnsupportedLayoutRecordException(String message) {
        super(message);
    }
}
