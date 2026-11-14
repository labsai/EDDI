/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.ledger;

import ai.labs.eddi.integrations.email.transport.ImapCursor;
import ai.labs.eddi.integrations.email.transport.MailboxCursor;

import java.util.Optional;

/**
 * How a {@link MailboxCursor} maps onto the stored fields, the same for MongoDB
 * and PostgreSQL: the serialised cursor, plus the two legacy IMAP fields.
 * <p>
 * <b>Write:</b> the serialised cursor always; for an IMAP cursor also its
 * {@code UIDVALIDITY} and last UID in the legacy fields, and for any other
 * cursor 0 and 0 there ("no IMAP cursor", what they always meant when unset).
 * <p>
 * <b>Read:</b> a non-IMAP serialised cursor is the cursor. Otherwise a non-zero
 * legacy {@code UIDVALIDITY} is: it is what every version writes for IMAP, so
 * state written before opaque cursors (no serialised cursor at all) loads as
 * the same IMAP cursor, and a row last written by an older node during a
 * rolling upgrade or after a rollback — legacy fields fresh, serialised cursor
 * stale — still reads its fresh values. Otherwise there is no cursor yet.
 */
final class StoredCursor {

    private StoredCursor() {
    }

    /** The cursor the stored fields describe, or null for none yet. */
    static MailboxCursor read(String serialized, long legacyUidValidity, long legacyLastUid) {
        Optional<MailboxCursor> parsed = MailboxCursor.parse(serialized);
        if (parsed.isPresent() && !parsed.get().belongsTo(ImapCursor.TRANSPORT)) {
            return parsed.get();
        }
        if (legacyUidValidity > 0 && legacyLastUid >= 0) {
            return new ImapCursor(legacyUidValidity, legacyLastUid).toCursor();
        }
        return null;
    }

    /** The serialised field, or null for no cursor. */
    static String serialized(MailboxCursor cursor) {
        return cursor == null ? null : cursor.serialize();
    }

    /** The legacy {@code UIDVALIDITY} field: the IMAP cursor's, else 0. */
    static long legacyUidValidity(MailboxCursor cursor) {
        return ImapCursor.from(cursor).map(ImapCursor::uidValidity).orElse(0L);
    }

    /** The legacy last-UID field: the IMAP cursor's, else 0. */
    static long legacyLastUid(MailboxCursor cursor) {
        return ImapCursor.from(cursor).map(ImapCursor::lastUid).orElse(0L);
    }
}
