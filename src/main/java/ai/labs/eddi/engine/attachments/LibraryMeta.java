/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import java.util.regex.Pattern;

/**
 * What a caller says about a file it puts into a library — the part of a
 * {@link LibraryEntry} the store does not decide itself.
 * <p>
 * <b>Trusted callers only.</b> The title is the user's or the agent's input, or
 * the file name — never text taken from inside the document (a PDF's own title
 * is untrusted content). The caller sanitizes it; this record only bounds it.
 *
 * @param title
 *            the display title; at most {@value #MAX_TITLE_CHARS} characters
 * @param slug
 *            the wanted slug, the last part of the document's qualified name
 *            ({@code mine/<slug>}): lower-case letters, digits and hyphens, at
 *            most {@value #MAX_SLUG_CHARS} characters. The store keeps slugs
 *            unique per owner and appends {@code -2}, {@code -3}, … when the
 *            wanted one is taken
 * @param agentScope
 *            the agent the document is visible to ({@code self} scope), or
 *            {@code null} for all of the owner's agents ({@code global})
 * @param retentionDays
 *            days after the last access the document expires; {@code null} for
 *            the deployment default
 *            ({@code eddi.documents.library.retention-days}), {@code 0} for
 *            never
 * @since 6.8.0
 */
public record LibraryMeta(String title, String slug, String agentScope, Integer retentionDays) {

    /** The longest title a library entry keeps. */
    public static final int MAX_TITLE_CHARS = 200;
    /** The longest slug, before a collision suffix. */
    public static final int MAX_SLUG_CHARS = 80;
    /** The longest agent id an entry can be scoped to. */
    public static final int MAX_AGENT_SCOPE_CHARS = 200;
    /**
     * The longest retention a document can carry — a hundred years, far above any
     * operator bound, and small enough that the stores' date arithmetic never
     * leaves the range a database can hold.
     */
    public static final int MAX_RETENTION_DAYS = 36_500;

    private static final Pattern SLUG = Pattern.compile("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?");

    /**
     * @throws IllegalArgumentException
     *             for a blank or over-long title, a slug that is not lower-case
     *             letters, digits and inner hyphens, a blank or over-long agent
     *             scope, or a retention below 0 or above
     *             {@value #MAX_RETENTION_DAYS} days
     */
    public LibraryMeta {
        if (title == null || title.isBlank() || title.length() > MAX_TITLE_CHARS) {
            throw new IllegalArgumentException("A library title must be 1 to " + MAX_TITLE_CHARS + " characters");
        }
        if (slug == null || slug.length() > MAX_SLUG_CHARS || !SLUG.matcher(slug).matches()) {
            throw new IllegalArgumentException(
                    "A library slug must be 1 to " + MAX_SLUG_CHARS + " lower-case letters, digits and inner hyphens");
        }
        if (agentScope != null && (agentScope.isBlank() || agentScope.length() > MAX_AGENT_SCOPE_CHARS)) {
            throw new IllegalArgumentException("An agent scope must be an agent id, or null for all of the owner's agents");
        }
        if (retentionDays != null && (retentionDays < 0 || retentionDays > MAX_RETENTION_DAYS)) {
            throw new IllegalArgumentException("Retention days must be 0 to " + MAX_RETENTION_DAYS);
        }
    }

    /**
     * {@code wanted} for {@code n < 2}, else {@code wanted-n}: the slug a store
     * tries when the {@code n - 1} before it are taken.
     */
    public static String suffixed(String wanted, int n) {
        return n < 2 ? wanted : wanted + "-" + n;
    }
}
