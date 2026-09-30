/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resource references held <em>inside</em> an extension document, as opposed to
 * the ones a workflow step holds.
 * <p>
 * A parser document names its dictionaries itself, by the id they have on the
 * instance the document was written on. Those dictionaries are matched like any
 * other resource — {@link WorkflowExtensions#scanDocument} gives each one a key
 * — so by the time a parser document is compared or written, every dictionary
 * it names has a known counterpart on the target, and the document only needs
 * its references swapped for those.
 * <p>
 * The swap is by source id, never by position: two documents that happen to
 * name the same number of dictionaries do not name the same dictionaries, and
 * pairing them by position would report a replaced dictionary as no change.
 */
final class NestedReferences {

    /** A versioned {@code eddi://} resource URI, as a config document stores it. */
    private static final Pattern RESOURCE_URI = Pattern
            .compile("eddi://(ai\\.labs\\.[A-Za-z]+)/[A-Za-z]+/[A-Za-z]+/([0-9A-Za-z-]+)\\?version=(\\d+)");

    private NestedReferences() {
    }

    /**
     * The document with each reference to a known source resource replaced by where
     * that resource lives on the target.
     * <p>
     * A reference to anything not in the map, or whose mapped URI is a different
     * kind of resource, is left as it is — so a document naming a dictionary with
     * no counterpart still reads as changed, which it is.
     *
     * @param onTargetBySourceId
     *            source resource id → the URI of its counterpart on the target
     * @return the rewritten document, or {@code json} itself when nothing changed
     */
    static String repointBySourceId(String json, Map<String, URI> onTargetBySourceId) {
        if (json == null || onTargetBySourceId == null || onTargetBySourceId.isEmpty()) {
            return json;
        }
        Matcher matcher = RESOURCE_URI.matcher(json);
        StringBuilder rewritten = new StringBuilder(json.length());
        boolean changed = false;
        while (matcher.find()) {
            URI counterpart = onTargetBySourceId.get(matcher.group(2));
            String replacement = matcher.group(0);
            if (counterpart != null && matcher.group(1).equals(counterpart.getHost())) {
                replacement = counterpart.toString();
                changed |= !replacement.equals(matcher.group(0));
            }
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
        }
        if (!changed) {
            return json;
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }

    /** Whether the document names any of these resource ids. */
    static boolean namesAny(String json, Iterable<String> resourceIds) {
        if (json == null) {
            return false;
        }
        List<String> named = new ArrayList<>();
        for (Matcher reference : referencesIn(json)) {
            named.add(reference.group(2));
        }
        for (String id : resourceIds) {
            if (named.contains(id)) {
                return true;
            }
        }
        return false;
    }

    private static List<Matcher> referencesIn(String json) {
        List<Matcher> found = new ArrayList<>();
        Matcher matcher = RESOURCE_URI.matcher(json);
        while (matcher.find()) {
            // A snapshot of this match; the matcher itself moves on.
            Matcher snapshot = RESOURCE_URI.matcher(matcher.group(0));
            if (snapshot.matches()) {
                found.add(snapshot);
            }
        }
        return found;
    }
}
