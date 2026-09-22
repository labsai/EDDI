/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which prompt snippets a set of configuration documents actually names.
 * <p>
 * One definition for both directions of a transfer. The export has always
 * carried only the snippets an agent references; the live sync read the remote
 * instance's <em>entire</em> snippet store and offered every one of them, so
 * promoting a single agent proposed writing staging's whole snippet library —
 * drafts included — onto production. Both now ask the same question of the same
 * documents.
 *
 * @since 6.4.1
 */
final class SnippetReferences {

    /**
     * Matches a snippet reference in a template string: {@code {snippets.name}} or
     * {@code {{snippets.name}}}. Captures the snippet name (group 1).
     * <p>
     * Deliberately matches the bare {@code snippets.<name>} token rather than a
     * whole Qute expression: a reference can sit inside a larger expression
     * ({@code {snippets.greeting ?: 'hi'}}), and the surrounding syntax is not what
     * identifies it.
     */
    private static final Pattern SNIPPET_REF_PATTERN = Pattern.compile("snippets\\.([a-zA-Z0-9_\\-]+)");

    private SnippetReferences() {
    }

    /**
     * Every snippet name referenced by any of these documents, in first-seen order.
     *
     * @param configDocuments
     *            serialized configurations — nulls and blanks are ignored so
     *            callers do not have to filter first
     */
    static Set<String> namesIn(Collection<String> configDocuments) {
        Set<String> names = new LinkedHashSet<>();
        if (configDocuments == null) {
            return names;
        }
        for (String document : configDocuments) {
            if (document == null || document.isEmpty()) {
                continue;
            }
            Matcher matcher = SNIPPET_REF_PATTERN.matcher(document);
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
        }
        return names;
    }
}
