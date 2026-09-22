/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resource references held <em>inside</em> an extension document, as opposed to
 * the ones a workflow step holds.
 * <p>
 * A parser document names its dictionaries itself. Those ids belong to the
 * instance the document was written on, and the workflow-level matching that
 * repoints a step at the target's copy never looks inside the document. On a
 * repeated sync that left two defects: the comparison saw the source's ids
 * against the target's and reported a change on every run, and the write then
 * carried the source's ids onto an instance where they name nothing.
 * <p>
 * The pairing here is the one the workflow already uses, applied one level
 * down: a nested reference is matched to the reference at the same position in
 * the target's copy of the document, provided both name the same resource type.
 * When the two documents do not line up — a dictionary added or removed on one
 * side — nothing is paired and the source document is returned as it is, so the
 * change shows as a change instead of being papered over.
 */
final class NestedReferences {

    /** A versioned {@code eddi://} resource URI, as a config document stores it. */
    private static final Pattern RESOURCE_URI = Pattern
            .compile("eddi://(ai\\.labs\\.[A-Za-z]+)/[A-Za-z]+/[A-Za-z]+/([0-9A-Za-z-]+)\\?version=(\\d+)");

    private NestedReferences() {
    }

    /**
     * The source document with each nested reference replaced by its counterpart in
     * the target's document.
     *
     * @param sourceJson
     *            the document as the source holds it
     * @param targetJson
     *            the target's current copy of the same document; null when there is
     *            none, in which case nothing can be paired
     * @param writtenThisRun
     *            target resource id → the URI this run wrote it at; a paired
     *            reference to one of these is moved onto the new version, so the
     *            document does not name the version the run just superseded
     * @return the rewritten document, or {@code sourceJson} itself when there was
     *         nothing to pair
     */
    static String repointAgainst(String sourceJson, String targetJson, Map<String, URI> writtenThisRun) {
        if (sourceJson == null || targetJson == null) {
            return sourceJson;
        }
        List<Matcher> source = referencesIn(sourceJson);
        List<Matcher> target = referencesIn(targetJson);
        if (source.isEmpty() || source.size() != target.size()) {
            return sourceJson;
        }

        Map<String, String> replacements = new HashMap<>();
        for (int i = 0; i < source.size(); i++) {
            Matcher from = source.get(i);
            Matcher to = target.get(i);
            if (!from.group(1).equals(to.group(1))) {
                // Same position, different kind of resource: the documents do not line
                // up, and a partial pairing would be a guess.
                return sourceJson;
            }
            URI written = writtenThisRun != null ? writtenThisRun.get(to.group(2)) : null;
            String replacement = written != null ? written.toString() : to.group(0);
            String previous = replacements.putIfAbsent(from.group(0), replacement);
            if (previous != null && !previous.equals(replacement)) {
                // One source reference paired with two different targets.
                return sourceJson;
            }
        }

        Matcher matcher = RESOURCE_URI.matcher(sourceJson);
        StringBuilder rewritten = new StringBuilder(sourceJson.length());
        while (matcher.find()) {
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacements.get(matcher.group(0))));
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }

    /**
     * The document with each reference to a known source resource replaced by where
     * that resource lives on the target.
     * <p>
     * The pairing for a document that has no target copy to line up against — one
     * being created on the target — and for the references a workflow step names as
     * well, whose target counterpart the workflow-level matching already
     * established. A reference to anything not in the map, or whose mapped URI is a
     * different kind of resource, is left as it is.
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
