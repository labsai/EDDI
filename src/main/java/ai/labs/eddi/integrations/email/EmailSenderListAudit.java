/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.gdpr.GdprPseudonymizer;
import ai.labs.eddi.utils.LogSanitizer;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes one audit-ledger entry per change of an email integration's sender
 * lists (email plan v3 §5.1): who, which integration, which versions, what was
 * added and removed, and why.
 * <p>
 * {@code AdminActionAuditFilter} records the request (method, path, status) but
 * never its body, so the diff would otherwise be lost. A diff names people: an
 * exact address is written as its keyed pseudonym — the token the inbound
 * refusals and GDPR erasure use for that address — and only a domain pattern
 * ({@code *@domain}, {@code *@*.domain}) is written in clear. The ledger is
 * immutable, so an address in clear could never be erased from it.
 * <p>
 * Recording never fails the change it describes.
 */
@ApplicationScoped
public class EmailSenderListAudit {

    private static final Logger LOGGER = Logger.getLogger(EmailSenderListAudit.class);

    /** Task id of a sender-list change entry. */
    public static final String TASK_ID = "email.senders";

    /** The action recorded for a sender-list change. */
    public static final String ACTION = "EMAIL_SENDERS_CHANGED";

    /** Task id of a sender-role change entry. */
    public static final String ROLES_TASK_ID = "email.roles";

    /** The action recorded for a sender-role change. */
    public static final String ROLES_ACTION = "EMAIL_SENDER_ROLES_CHANGED";

    private final Instance<AuditLedgerService> ledger;
    private final Instance<GdprPseudonymizer> pseudonymizer;
    private final Instance<SecurityIdentity> identity;

    @Inject
    public EmailSenderListAudit(Instance<AuditLedgerService> ledger, Instance<GdprPseudonymizer> pseudonymizer,
            Instance<SecurityIdentity> identity) {
        this.ledger = ledger;
        this.pseudonymizer = pseudonymizer;
        this.identity = identity;
    }

    /**
     * Records one change.
     *
     * @param added
     *            entries added, as {@code allow:<entry>} / {@code block:<entry>}
     * @param removed
     *            entries removed, in the same form
     */
    public void changed(String integrationId, Integer previousVersion, Integer newVersion, List<String> added, List<String> removed,
                        String reason, boolean allowlistEmptied) {
        try {
            if (ledger == null || !ledger.isResolvable() || !ledger.get().isEnabled()) {
                return;
            }
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("integrationId", integrationId);
            output.put("previousVersion", previousVersion);
            output.put("version", newVersion);
            output.put("added", recorded(added));
            output.put("removed", recorded(removed));
            output.put("reason", scrubAddresses(reason));
            output.put("allowlistEmptied", allowlistEmptied);
            ledger.get().submit(new AuditEntry(UUID.randomUUID().toString(), null, null, null, actor(), null, 0, TASK_ID, "email", 0, 0,
                    Map.of("action", ACTION), output, null, null, List.of(ACTION), 0.0, Instant.now(), null, null));
        } catch (RuntimeException e) {
            LOGGER.warnf("Could not write the sender-list audit entry of email integration %s: %s", LogSanitizer.sanitize(integrationId),
                    LogSanitizer.sanitize(e.getMessage()));
        }
    }

    /**
     * Records one change of the integration's sender roles (email plan v3 §9):
     * which roles and policies were added, removed or changed — by role name, so no
     * address reaches the immutable ledger — and why.
     */
    public void rolesChanged(String integrationId, Integer previousVersion, Integer newVersion, List<String> changes, String reason) {
        try {
            if (ledger == null || !ledger.isResolvable() || !ledger.get().isEnabled()) {
                return;
            }
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("integrationId", integrationId);
            output.put("previousVersion", previousVersion);
            output.put("version", newVersion);
            output.put("changes", changes == null ? List.of() : List.copyOf(changes));
            output.put("reason", scrubAddresses(reason));
            ledger.get().submit(new AuditEntry(UUID.randomUUID().toString(), null, null, null, actor(), null, 0, ROLES_TASK_ID, "email", 0,
                    0, Map.of("action", ROLES_ACTION), output, null, null, List.of(ROLES_ACTION), 0.0, Instant.now(), null, null));
        } catch (RuntimeException e) {
            LOGGER.warnf("Could not write the sender-role audit entry of email integration %s: %s", LogSanitizer.sanitize(integrationId),
                    LogSanitizer.sanitize(e.getMessage()));
        }
    }

    /** An address-shaped token inside free text; bounded classes, no nesting. */
    private static final Pattern ADDRESS_IN_TEXT = Pattern
            .compile("(?:\"[^\"\\r\\n]{1,64}\"|[^\\s@<>,;\"'()]{1,64})@[^\\s@<>,;\"'()]{1,255}");

    /**
     * The free-text reason with every exact address replaced by its pseudonym, as
     * the added and removed lists are: the ledger is immutable, so an address typed
     * into the reason must not be written to it in clear.
     */
    String scrubAddresses(String text) {
        if (text == null || text.indexOf('@') < 0) {
            return text;
        }
        Matcher m = ADDRESS_IN_TEXT.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String hit = m.group();
            m.appendReplacement(out, Matcher.quoteReplacement(hit.startsWith("*@") ? hit : pseudonym(hit)));
        }
        m.appendTail(out);
        return out.toString();
    }
    /** {@code list:entry} with an exact address replaced by its pseudonym. */
    List<String> recorded(List<String> entries) {
        List<String> out = new ArrayList<>();
        if (entries == null) {
            return out;
        }
        for (String entry : entries) {
            int colon = entry.indexOf(':');
            String list = colon > 0 ? entry.substring(0, colon + 1) : "";
            String value = colon > 0 ? entry.substring(colon + 1) : entry;
            out.add(list + (value.startsWith("*@") ? value : pseudonym(value)));
        }
        return out;
    }

    private String pseudonym(String address) {
        if (pseudonymizer == null || !pseudonymizer.isResolvable()) {
            // No pseudonym: the domain only, never the address.
            int at = address.lastIndexOf('@');
            return at > 0 ? "?@" + address.substring(at + 1) : "?";
        }
        return pseudonymizer.get().pseudonymFor(address.toLowerCase(Locale.ROOT));
    }

    private String actor() {
        try {
            if (identity != null && identity.isResolvable()) {
                SecurityIdentity current = identity.get();
                if (current != null && !current.isAnonymous() && current.getPrincipal() != null) {
                    return current.getPrincipal().getName();
                }
            }
        } catch (RuntimeException e) {
            // No request-scoped identity (a call outside a request).
        }
        return "anonymous";
    }
}
