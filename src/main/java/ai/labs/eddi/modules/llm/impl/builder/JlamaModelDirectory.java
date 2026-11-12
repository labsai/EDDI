/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The operator's base directory for Jlama ({@value #PROPERTY}), and the rule
 * that confines a per-agent {@code modelCachePath} or {@code workingDirectory}
 * to it.
 * <p>
 * Jlama downloads model weights inside the EDDI JVM and writes them where the
 * agent's parameters say. Without a base directory an editor could make the
 * server write gigabytes to any path it can write. With one set, a path counts
 * only when it resolves under the base after lexical normalisation — a relative
 * path against the base, an absolute one as given, {@code ..} segments
 * collapsed. No filesystem access and no symlink resolution: the decision is
 * the same at save time, when the directory may not exist yet, as at build
 * time.
 */
public final class JlamaModelDirectory {

    /** The operator property naming the base directory; unset means none. */
    public static final String PROPERTY = "eddi.llm.jlama.model-dir";

    private JlamaModelDirectory() {
    }

    /**
     * The normalised absolute base directory, or empty when none (or unusable) is
     * set.
     */
    public static Optional<Path> base(String baseDir) {
        if (baseDir == null || baseDir.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Path.of(baseDir.trim()).toAbsolutePath().normalize());
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    /**
     * {@code raw} resolved and normalised against {@code base}, or empty when it is
     * not a usable path or lies outside {@code base}.
     */
    public static Optional<Path> confine(String raw, Path base) {
        if (raw == null || raw.isBlank() || base == null) {
            return Optional.empty();
        }
        try {
            Path path = Path.of(raw.trim());
            Path resolved = (path.isAbsolute() ? path : base.resolve(path)).normalize();
            return resolved.startsWith(base) ? Optional.of(resolved) : Optional.empty();
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }
}
