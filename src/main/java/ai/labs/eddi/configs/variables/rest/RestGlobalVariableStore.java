/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.variables.rest;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.configs.variables.IGlobalVariableStore;
import ai.labs.eddi.configs.variables.model.GlobalVariable;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.spaces.SpaceTenants;
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

    private final IGlobalVariableStore store;
    private final GlobalVariableResolver resolver;
    private final ResourceAccessGuard accessGuard;

    @Inject
    public RestGlobalVariableStore(IGlobalVariableStore store, GlobalVariableResolver resolver, ResourceAccessGuard accessGuard) {
        this.store = store;
        this.resolver = resolver;
        this.accessGuard = accessGuard;
    }

    /** Without workspace checks. Test seam. */
    public RestGlobalVariableStore(IGlobalVariableStore store, GlobalVariableResolver resolver) {
        this(store, resolver, null);
    }

    @Override
    public List<GlobalVariable> listVariables(String tenantId) {
        validateId(tenantId, "tenantId");
        requireRead(tenantId);
        return store.listAll(tenantId);
    }

    @Override
    public GlobalVariable getVariable(String tenantId, String key) {
        validateId(tenantId, "tenantId");
        validateId(key, "key");
        requireRead(tenantId);
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
        requireWrite(tenantId);
        if (variable == null) {
            throw new BadRequestException("Request body must not be empty");
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
        requireWrite(tenantId);
        store.delete(tenantId, key);
        resolver.invalidateCache();

        LOGGER.infof("Global variable deleted: %s/%s", sanitize(tenantId), sanitize(key));
        return Response.noContent().build();
    }

    /**
     * Under workspace enforcement, a non-administrator may read the deployment-wide
     * {@code default} tenant and their own spaces' tenants — not another team's.
     * Without enforcement nothing changes.
     */
    private void requireRead(String tenantId) {
        if (!restricted() || GlobalVariable.DEFAULT_TENANT.equals(tenantId) || ownsSpaceTenant(tenantId)) {
            return;
        }
        throw new ForbiddenException("You may read only the deployment-wide variables and those of your own spaces.");
    }

    /**
     * Under workspace enforcement, deployment-wide variables are an administrator's
     * to change: any editor could otherwise overwrite a value every other team's
     * agents read. A space's own variables are its members' — through
     * {@code /spacestore/variables}, or here by tenant id.
     */
    private void requireWrite(String tenantId) {
        if (!restricted() || ownsSpaceTenant(tenantId)) {
            return;
        }
        throw new ForbiddenException("Deployment-wide variables can only be changed by an administrator while workspaces are enforced. "
                + "Put a variable that belongs to you or your team in that space instead (PUT /spacestore/variables/{key}?space=...).");
    }

    private boolean restricted() {
        return accessGuard != null && accessGuard.settings().isEnforcing() && !accessGuard.isAdmin();
    }

    private boolean ownsSpaceTenant(String tenantId) {
        if (!SpaceTenants.isSpaceTenant(tenantId)) {
            return false;
        }
        for (String space : accessGuard.callerSpaces().spaces()) {
            if (SpaceTenants.tenantFor(space).equals(tenantId)) {
                return true;
            }
        }
        return false;
    }

    private static void validateId(String value, String fieldName) {
        if (value == null || !ID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    fieldName + " must match [a-zA-Z0-9_.\\-]+ (letters, digits, dots, underscores, hyphens). Got: "
                            + sanitize(value));
        }
    }
}
