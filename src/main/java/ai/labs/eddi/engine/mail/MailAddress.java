/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import java.util.Objects;

/**
 * One mailbox: a normalised address and the display name that came with it.
 * <p>
 * Instances are built by {@link AddressNormalizer}, so {@link #address()} is
 * always lower-case with an ASCII (IDNA) domain — the form identity, allowlists
 * and the message ledger compare. The display name is decoded text written by
 * the sender, never authenticated, and only ever for showing.
 * <p>
 * {@link #toString()} prints the domain only: addresses are personal data, and
 * records end up in log lines.
 *
 * @param address
 *            the normalised addr-spec, never null
 * @param displayName
 *            the decoded display name, or null when there was none
 */
public record MailAddress(String address, String displayName) {

    public MailAddress {
        Objects.requireNonNull(address, "address");
        if (displayName != null && displayName.isBlank()) {
            displayName = null;
        }
    }

    /** The part after the last {@code @}. */
    public String domain() {
        return address.substring(address.lastIndexOf('@') + 1);
    }

    /** The part before the last {@code @}. */
    public String localPart() {
        return address.substring(0, address.lastIndexOf('@'));
    }

    @Override
    public String toString() {
        return "MailAddress[domain=" + domain() + "]";
    }
}
