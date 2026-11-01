/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import java.util.Locale;
import java.util.Set;

/**
 * Says whether a message was sent by a machine — an out-of-office reply, a
 * bounce, a mailing list — and why (email plan §13).
 * <p>
 * The first line of loop prevention: an agent that answers an auto-reply gets
 * an auto-reply back, forever. A later {@code EmailLoopGuard} adds what only it
 * knows (the integration's own address, ids from other integrations' ledgers,
 * budgets); this class looks at the message alone. The signals, each enough on
 * its own:
 * <ul>
 * <li>a {@code multipart/report} — a DSN, read receipt or abuse report;</li>
 * <li>{@code Auto-Submitted} with any value but {@code no} (RFC 3834);</li>
 * <li>{@code X-Autoreply} / {@code X-Autorespond} with any value but
 * {@code no};</li>
 * <li>{@code Precedence: bulk|junk|list|auto_reply};</li>
 * <li>{@code List-Id} or {@code List-Unsubscribe};</li>
 * <li>a null {@code Return-Path} ({@code <>}), or one from
 * {@code MAILER-DAEMON} / {@code postmaster};</li>
 * <li>a From of {@code MAILER-DAEMON}.</li>
 * </ul>
 * These are the sender's own headers, so they can only make the guard refuse
 * more, never less: a forged {@code Auto-Submitted: no} on a bounce changes
 * nothing, because the report part still says what it is.
 */
public final class AutoReplyDetector {

    /** Why a message counts as automated. */
    public enum Reason {
        /** Not automated, as far as the headers say. */
        NONE,
        /** A {@code multipart/report}. */
        REPORT,
        /** {@code Auto-Submitted} other than {@code no}. */
        AUTO_SUBMITTED,
        /** {@code X-Autoreply}. */
        X_AUTOREPLY,
        /** {@code X-Autorespond}. */
        X_AUTORESPOND,
        /** {@code Precedence: bulk|junk|list|auto_reply}. */
        PRECEDENCE,
        /** {@code List-Id}. */
        LIST_ID,
        /** {@code List-Unsubscribe}. */
        LIST_UNSUBSCRIBE,
        /** {@code Return-Path: <>}. */
        NULL_RETURN_PATH,
        /**
         * A {@code Return-Path} or {@code From} of {@code MAILER-DAEMON} or
         * {@code postmaster}.
         */
        MAILER_DAEMON
    }

    /**
     * The verdict.
     *
     * @param automated
     *            whether the message must not be answered
     * @param reason
     *            the first signal found, {@link Reason#NONE} when none
     * @param detail
     *            the header value that decided it, capped, for logs and audit —
     *            never for a reply
     * @param deliveryReport
     *            the parsed DSN when the message is a bounce, for suppression
     */
    public record Verdict(boolean automated, Reason reason, String detail, DeliveryReport deliveryReport) {

        /** Whether this is a delivery status notification with recipients. */
        public boolean isBounce() {
            return deliveryReport != null;
        }
    }

    private static final Set<String> BULK_PRECEDENCE = Set.of("bulk", "junk", "list", "auto_reply");
    private static final Set<String> DAEMON_LOCAL_PARTS = Set.of("mailer-daemon", "postmaster");
    private static final int MAX_DETAIL = 200;

    private AutoReplyDetector() {
    }

    /** Looks at one parsed message. */
    public static Verdict detect(InboundMail mail) {
        LoopSignals signals = mail.loopSignals();
        if (signals.reportType() != null || mail.deliveryReport() != null) {
            return automated(Reason.REPORT, signals.reportType(), mail.deliveryReport());
        }
        if (isSet(signals.autoSubmitted())) {
            return automated(Reason.AUTO_SUBMITTED, signals.autoSubmitted(), null);
        }
        if (isSet(signals.xAutoreply())) {
            return automated(Reason.X_AUTOREPLY, signals.xAutoreply(), null);
        }
        if (isSet(signals.xAutorespond())) {
            return automated(Reason.X_AUTORESPOND, signals.xAutorespond(), null);
        }
        if (signals.precedence() != null && BULK_PRECEDENCE.contains(firstToken(signals.precedence()))) {
            return automated(Reason.PRECEDENCE, signals.precedence(), null);
        }
        if (signals.listId() != null && !signals.listId().isBlank()) {
            return automated(Reason.LIST_ID, signals.listId(), null);
        }
        if (signals.listUnsubscribe() != null && !signals.listUnsubscribe().isBlank()) {
            return automated(Reason.LIST_UNSUBSCRIBE, signals.listUnsubscribe(), null);
        }
        if (signals.returnPath() != null) {
            String path = signals.returnPath().strip();
            if (path.isEmpty() || path.replace(" ", "").equals("<>")) {
                return automated(Reason.NULL_RETURN_PATH, path, null);
            }
            if (DAEMON_LOCAL_PARTS.contains(localPart(path))) {
                return automated(Reason.MAILER_DAEMON, path, null);
            }
        }
        if (mail.from() != null && "mailer-daemon".equals(mail.from().localPart())) {
            return automated(Reason.MAILER_DAEMON, mail.from().address(), null);
        }
        return new Verdict(false, Reason.NONE, null, null);
    }

    /** Present, and not {@code no} (parameters after a {@code ;} ignored). */
    private static boolean isSet(String value) {
        if (value == null) {
            return false;
        }
        String token = firstToken(value);
        return !token.isEmpty() && !"no".equals(token);
    }

    private static String firstToken(String value) {
        String stripped = value.strip().toLowerCase(Locale.ROOT);
        int end = stripped.length();
        for (int i = 0; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            if (c == ';' || c == ' ' || c == '\t' || c == '(') {
                end = i;
                break;
            }
        }
        return stripped.substring(0, end);
    }

    private static String localPart(String path) {
        String address = path.replace("<", "").replace(">", "").strip().toLowerCase(Locale.ROOT);
        int at = address.lastIndexOf('@');
        return at < 0 ? address : address.substring(0, at);
    }

    private static Verdict automated(Reason reason, String detail, DeliveryReport report) {
        String capped = detail == null ? null : detail.length() > MAX_DETAIL ? detail.substring(0, MAX_DETAIL) : detail;
        return new Verdict(true, reason, capped, report);
    }
}
