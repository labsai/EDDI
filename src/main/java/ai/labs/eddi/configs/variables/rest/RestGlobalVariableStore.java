/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.variables.rest;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.configs.variables.IGlobalVariableStore;
import ai.labs.eddi.configs.variables.model.GlobalVariable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
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

    @Inject
    public RestGlobalVariableStore(IGlobalVariableStore store, GlobalVariableResolver resolver) {
        this.store = store;
        this.resolver = resolver;
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

    private static void validateId(String value, String fieldName) {
        if (value == null || !ID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    fieldName + " must match [a-zA-Z0-9_.\\-]+ (letters, digits, dots, underscores, hyphens). Got: "
                            + sanitize(value));
        }
    }
}
