/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.api;

import ai.labs.eddi.configs.variables.IGlobalVariableStore;
import ai.labs.eddi.configs.variables.model.GlobalVariable;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.OptionalInt;

/**
 * Warns at startup when the Platform Operator on this deployment is older than
 * the Manager this build ships.
 * <p>
 * The operator is provisioned by the Manager, and everything the Manager bakes
 * into it — its instructions, its tool allow-list, its approval gate — is a
 * snapshot taken at activation. Upgrading EDDI upgrades the Manager but not the
 * operator it built, so without this an operator activated months ago keeps
 * running on months-old instructions and nothing anywhere says so. The Manager
 * shows the same notice on its own screens; this is the half an operator of the
 * <em>deployment</em> sees, in the log, without opening the UI.
 * <p>
 * Both halves read one number:
 * {@code ui/manager/src/lib/operator/operator-revision.json}, which the Maven
 * build also copies onto this classpath as {@value #REVISION_RESOURCE}. The
 * Manager stamps the revision it provisioned into the
 * {@value #OPERATOR_VARIABLE} global variable, which is compared here.
 * <p>
 * Purely advisory and never fatal: the check runs off the startup thread, and a
 * missing file (a build that skipped the Manager), an unreadable variable or a
 * store that is not up yet each end in a DEBUG line, not a failed boot.
 *
 * @since 6.5.0
 */
@ApplicationScoped
public class OperatorRevisionCheck {

    private static final Logger LOGGER = Logger.getLogger(OperatorRevisionCheck.class);

    /** Where the Maven build puts the Manager's revision file (see pom.xml). */
    static final String REVISION_RESOURCE = "eddi-manager/operator-revision.json";

    /** The global variable the Manager keeps the operator's configuration in. */
    static final String OPERATOR_VARIABLE = "platform.operator";

    /**
     * What a check concluded. Returned rather than only logged, so it can be
     * tested.
     */
    enum Outcome {
        /** This build carries no revision file — nothing to compare against. */
        NO_MANIFEST,
        /** No operator has been set up, or its configuration cannot be read. */
        NOT_CONFIGURED,
        /**
         * An operator is configured but switched off; activating it builds the current
         * one.
         */
        NOT_ACTIVE,
        /** The running operator was provisioned by this revision or a later one. */
        CURRENT,
        /** The running operator predates this build's Manager. */
        OUTDATED
    }

    private final IGlobalVariableStore variableStore;
    private final ObjectMapper objectMapper;

    @Inject
    public OperatorRevisionCheck(IGlobalVariableStore variableStore, ObjectMapper objectMapper) {
        this.variableStore = variableStore;
        this.objectMapper = objectMapper;
    }

    // CDI requires the @Observes parameter for event discovery; not read directly
    void onStart(@Observes StartupEvent event) {
        // Off the startup thread: a read of the variable store must never delay or
        // fail the boot for what is only a recommendation.
        Thread.ofVirtual().name("operator-revision-check").start(() -> {
            try {
                check();
            } catch (RuntimeException e) {
                LOGGER.debugf(e, "[OPERATOR] Could not check the Platform Operator's revision: %s", e.getMessage());
            }
        });
    }

    Outcome check() {
        OptionalInt shipped = shippedRevision();
        if (shipped.isEmpty()) {
            LOGGER.debugf("[OPERATOR] %s is not on the classpath (a build without the Manager); skipping the revision check.",
                    REVISION_RESOURCE);
            return Outcome.NO_MANIFEST;
        }

        GlobalVariable variable = variableStore.get(GlobalVariable.DEFAULT_TENANT, OPERATOR_VARIABLE);
        JsonNode config = parse(variable);
        if (config == null) {
            return Outcome.NOT_CONFIGURED;
        }
        boolean active = config.path("enabled").asBoolean(false) && config.path("agentId").isTextual()
                && !config.path("agentId").asText().isBlank();
        if (!active) {
            return Outcome.NOT_ACTIVE;
        }

        // Absent on a configuration the Manager wrote before revisions existed,
        // which is exactly the operator this check exists to find: read it as 0.
        int provisioned = config.path("provisionedRevision").asInt(0);
        if (provisioned >= shipped.getAsInt()) {
            LOGGER.debugf("[OPERATOR] The Platform Operator is current (revision %d).", provisioned);
            return Outcome.CURRENT;
        }
        LOGGER.warnf("[OPERATOR] The Platform Operator on this deployment was set up by an older Manager (revision %d; this build "
                + "ships revision %d), so its instructions and tools are out of date. Open the Manager at /manage/operator and "
                + "choose Upgrade — it rebuilds the operator with the same model and settings.", provisioned, shipped.getAsInt());
        return Outcome.OUTDATED;
    }

    OptionalInt shippedRevision() {
        try (InputStream in = openRevisionResource()) {
            if (in == null) {
                return OptionalInt.empty();
            }
            JsonNode revision = objectMapper.readTree(in).path("revision");
            return revision.canConvertToInt() ? OptionalInt.of(revision.asInt()) : OptionalInt.empty();
        } catch (IOException e) {
            LOGGER.debugf(e, "[OPERATOR] %s could not be read.", REVISION_RESOURCE);
            return OptionalInt.empty();
        }
    }

    /** Overridable so a test can supply the file without a Manager build. */
    InputStream openRevisionResource() {
        return Thread.currentThread().getContextClassLoader().getResourceAsStream(REVISION_RESOURCE);
    }

    private JsonNode parse(GlobalVariable variable) {
        if (variable == null || variable.value() == null || variable.value().isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(variable.value());
            return node != null && node.isObject() ? node : null;
        } catch (IOException e) {
            LOGGER.debugf("[OPERATOR] The %s variable is not valid JSON; skipping the revision check.", OPERATOR_VARIABLE);
            return null;
        }
    }
}
