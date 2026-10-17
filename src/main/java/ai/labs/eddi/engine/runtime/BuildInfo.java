/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.engine.api.model.PlatformBuildInfo;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The single answer to "which EDDI is this?". The REST endpoint, the MCP tool,
 * and the startup log line all read it, so they cannot disagree.
 * <p>
 * The commit arrives as {@code eddi.build.commit} (environment variable
 * {@code EDDI_BUILD_COMMIT}), which the Dockerfile bakes in from the
 * {@code EDDI_COMMIT} build argument. It is deliberately not stamped into the
 * jar: that would make the artifact differ per commit beyond its content, and
 * an operator upgrade swaps the image, not the config.
 * <p>
 * The value is accepted only if it is a hex string. It is handed to an LLM by
 * the operator's version tool, and an environment variable is not a channel
 * that should be able to carry free text into a prompt.
 *
 * @since 6.5.0
 */
@ApplicationScoped
public class BuildInfo {

    public static final String UNKNOWN = "unknown";

    private static final Logger LOGGER = Logger.getLogger(BuildInfo.class);
    private static final Pattern HEX_COMMIT = Pattern.compile("[0-9a-f]{7,64}");
    private static final int SHORT_LENGTH = 12;

    private final PlatformBuildInfo info;

    @Inject
    public BuildInfo(@ConfigProperty(name = "systemRuntime.projectVersion") String version,
            @ConfigProperty(name = "eddi.build.commit", defaultValue = UNKNOWN) String commit) {
        this.info = of(version, commit);
    }

    static PlatformBuildInfo of(String version, String commit) {
        String normalised = commit == null ? "" : commit.strip().toLowerCase(Locale.ROOT);
        if (!HEX_COMMIT.matcher(normalised).matches()) {
            return new PlatformBuildInfo(version, UNKNOWN, UNKNOWN);
        }
        return new PlatformBuildInfo(version, normalised, normalised.substring(0, Math.min(SHORT_LENGTH, normalised.length())));
    }

    public PlatformBuildInfo get() {
        return info;
    }

    void onStart(@Observes StartupEvent event) {
        LOGGER.infov("EDDI {0} (commit {1})", info.version(), info.commitShort());
    }
}
