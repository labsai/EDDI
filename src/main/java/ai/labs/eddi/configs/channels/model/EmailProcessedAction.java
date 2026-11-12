/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels.model;

import java.util.Locale;

/**
 * What happens to a mail once its ledger row is written — the email channel's
 * {@code processedAction} (email plan §7, §10). Never a delete.
 * <ul>
 * <li>{@code flag} (the default): mark it — IMAP sets the keyword
 * {@code $EddiProcessed}.</li>
 * <li>{@code move:<folder>}: flag it and move it to {@code <folder>}.</li>
 * <li>{@code label:<name>}: put the label {@code <name>} on it, for a transport
 * with labels (the Gmail API). IMAP has none, so an IMAP integration refuses it
 * at save time.</li>
 * </ul>
 * The kind is matched without regard to case ({@code FLAG}, {@code Move:x});
 * the target is kept as written.
 *
 * @param kind
 *            flag, move or label
 * @param target
 *            the folder ({@code move}) or label ({@code label}); null for
 *            {@code flag}
 * @since 6.6.0
 */
public record EmailProcessedAction(Kind kind, String target) {

    public static final String FLAG_ID = "flag";
    public static final String MOVE_PREFIX = "move:";
    public static final String LABEL_PREFIX = "label:";

    /** The default. */
    public static final EmailProcessedAction FLAG = new EmailProcessedAction(Kind.FLAG, null);

    /** The three kinds of the vocabulary. */
    public enum Kind {
        FLAG, MOVE, LABEL
    }

    /**
     * Reads a {@code processedAction} value: null or blank is {@link #FLAG}. A
     * value outside the vocabulary — or {@code move:} / {@code label:} with no
     * target — is null; {@code EmailChannelSettings.validate} says why.
     */
    public static EmailProcessedAction parse(String value) {
        if (value == null || value.isBlank()) {
            return FLAG;
        }
        String trimmed = value.trim();
        String lowered = trimmed.toLowerCase(Locale.ROOT);
        if (FLAG_ID.equals(lowered)) {
            return FLAG;
        }
        if (lowered.startsWith(MOVE_PREFIX)) {
            return withTarget(Kind.MOVE, trimmed.substring(MOVE_PREFIX.length()));
        }
        if (lowered.startsWith(LABEL_PREFIX)) {
            return withTarget(Kind.LABEL, trimmed.substring(LABEL_PREFIX.length()));
        }
        return null;
    }

    private static EmailProcessedAction withTarget(Kind kind, String target) {
        return target.isBlank() ? null : new EmailProcessedAction(kind, target.trim());
    }

    /**
     * The configuration value: {@code flag}, {@code move:<folder>} or
     * {@code label:<name>}.
     */
    public String id() {
        return switch (kind) {
            case FLAG -> FLAG_ID;
            case MOVE -> MOVE_PREFIX + target;
            case LABEL -> LABEL_PREFIX + target;
        };
    }
}
