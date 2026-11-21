/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import ai.labs.eddi.engine.attachments.Derivation.Kind;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.event.Event;
import org.jboss.logging.Logger;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * What both attachment stores do the same way around derived blobs: the content
 * hash, the metrics and the lifecycle event.
 *
 * @since 6.8.0
 */
public final class DerivedBlobs {

    /** Counter of derived blobs stored; tag {@code kind}. */
    public static final String METRIC_STORED = "eddi.attachment.derived.stored";
    /** Counter of derived bytes stored; tag {@code kind}. */
    public static final String METRIC_BYTES = "eddi.attachment.derived.bytes";

    private static final Logger LOGGER = Logger.getLogger(DerivedBlobs.class);

    private DerivedBlobs() {
        // utility class
    }

    /** Lower-case hex SHA-256 of {@code bytes}. */
    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * Counts one newly stored derived blob. A deduplicated write stores nothing and
     * is not counted. Tags carry the kind only — never a reference or a name.
     */
    public static void recordStored(MeterRegistry registry, Kind kind, long bytes) {
        if (registry == null) {
            return;
        }
        String tag = kind.name();
        registry.counter(METRIC_STORED, "kind", tag).increment();
        registry.counter(METRIC_BYTES, "kind", tag).increment(bytes);
    }

    /**
     * Announces each deleted root, after the delete succeeded. An observer that
     * throws is logged and does not undo the delete, nor stop the other
     * announcements.
     */
    public static void fireRootDeleted(Event<AttachmentRootDeleted> event, List<AttachmentRootDeleted> roots) {
        if (event == null || roots.isEmpty()) {
            return;
        }
        for (AttachmentRootDeleted root : roots) {
            try {
                event.fire(root);
            } catch (RuntimeException e) {
                LOGGER.warnf("An observer of a deleted attachment failed; what it keeps for that file is left for its own cleanup: %s",
                        sanitize(e.getMessage()));
            }
        }
    }
}
