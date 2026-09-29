/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.variables.rest;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.configs.variables.IGlobalVariableStore;
import ai.labs.eddi.configs.variables.model.GlobalVariable;
import ai.labs.eddi.connections.model.ConnectionReference;
import ai.labs.eddi.secrets.model.SecretReference;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.regex.Pattern;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * REST implementation for global variable CRUD.
 * <p>
 * Writes invalidate the {@link GlobalVariableResolver} cache so that subsequent
 * reads pick up the new values immediately.
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
public class RestGlobalVariableStore implements IRestGlobalVariableStore {

    private static final Logger LOGGER = Logger.getLogger(RestGlobalVariableStore.class);
    private static final Pattern ID_PATTERN = Pattern.compile("[a-zA-Z0-9_.\\-]+");

    /** Only this role may store a global variable that resolves to a secret. */
    private static final String ADMIN_ROLE = "eddi-admin";

    private final IGlobalVariableStore store;
    private final GlobalVariableResolver resolver;
    private final SecurityIdentity identity;

    @Inject
    public RestGlobalVariableStore(IGlobalVariableStore store, GlobalVariableResolver resolver, SecurityIdentity identity) {
        this.store = store;
        this.resolver = resolver;
        this.identity = identity;
    }

    @Override
    public List<GlobalVariable> listVariables(String tenantId) {
        validateId(tenantId, "tenantId");
        return store.listAll(tenantId);
    }

    @Override
    public GlobalVariable getVariable(String tenantId, String key) {
        validateId(tenantId, "tenantId");
        validateId(key, "key");
        var variable = store.get(tenantId, key);
        if (variable == null) {
            throw new NotFoundException("Global variable not found: " + sanitize(tenantId) + "/" + sanitize(key));
        }
        return variable;
    }

    @Override
    public Response upsertVariable(String tenantId, String key, GlobalVariable variable) {
        validateId(tenantId, "tenantId");
        validateId(key, "key");
        if (variable == null) {
            throw new BadRequestException("Request body must not be empty");
        }

        // A global variable may legitimately hold a ${vault:...}/${connection:...}
        // reference, and CredentialReferenceResolver resolves variables FIRST, so a
        // config that reads ${vars:key} resolves through to whatever secret the
        // variable points at. Because agent-secret grants are checked at DEPLOY time
        // (VaultGrantChecker), a non-admin editor could otherwise point a deployed
        // agent's ${vars:key} at a secret it was never granted simply by editing this
        // variable after deployment — the grant check never re-runs. So only an admin
        // may store a variable that resolves to a secret. Anonymous callers (auth
        // disabled) are out of scope: there is no editor/admin distinction to enforce.
        if (referencesASecret(variable.value()) && identity != null && !identity.isAnonymous() && !identity.hasRole(ADMIN_ROLE)) {
            throw new ForbiddenException("Only an " + ADMIN_ROLE + " may store a global variable whose value resolves to a vault secret or a "
                    + "connection (it contains a ${vault:...}, ${eddivault:...} or ${connection:...} reference). This prevents redirecting a "
                    + "deployed agent's credentials past the deploy-time grant check.");
        }

        // Ensure the path params take precedence over anything in the body
        var toStore = new GlobalVariable(tenantId, key, variable.value(), variable.description(), variable.exportable());
        store.upsert(toStore);
        resolver.invalidateCache();

        LOGGER.infof("Global variable upserted: %s/%s", sanitize(tenantId), sanitize(key));
        return Response.ok().build();
    }

    @Override
    public Response deleteVariable(String tenantId, String key) {
        validateId(tenantId, "tenantId");
        validateId(key, "key");
        store.delete(tenantId, key);
        resolver.invalidateCache();

        LOGGER.infof("Global variable deleted: %s/%s", sanitize(tenantId), sanitize(key));
        return Response.noContent().build();
    }

    /**
     * Whether {@code value} contains a vault, legacy eddivault, or connection
     * reference — i.e. whether it resolves to a secret rather than being a plain
     * literal.
     */
    private static boolean referencesASecret(String value) {
        return value != null && (SecretReference.isVaultReference(value) || ConnectionReference.contains(value));
    }

    private static void validateId(String value, String fieldName) {
        if (value == null || !ID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    fieldName + " must match [a-zA-Z0-9_.\\-]+ (letters, digits, dots, underscores, hyphens). Got: "
                            + sanitize(value));
        }
    }
}
