/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces.rest;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.configs.variables.IGlobalVariableStore;
import ai.labs.eddi.configs.variables.model.GlobalVariable;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.spaces.SpaceTenants;
import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.regex.Pattern;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * @see IRestSpaceResources
 */
@ApplicationScoped
public class RestSpaceResources implements IRestSpaceResources {

    private static final Logger LOGGER = Logger.getLogger(RestSpaceResources.class);

    /** The vault's own key rule. */
    private static final Pattern KEY = Pattern.compile("[a-zA-Z0-9._-]{1,128}");

    /** The variable store's key rule. */
    private static final Pattern VARIABLE_KEY = Pattern.compile("[a-zA-Z0-9_.\\-]{1,128}");

    private final ResourceAccessGuard accessGuard;
    private final ISecretProvider secretProvider;
    private final SecretResolver secretResolver;
    private final IGlobalVariableStore variableStore;
    private final GlobalVariableResolver variableResolver;

    @Inject
    public RestSpaceResources(ResourceAccessGuard accessGuard, ISecretProvider secretProvider, SecretResolver secretResolver,
            IGlobalVariableStore variableStore, GlobalVariableResolver variableResolver) {
        this.accessGuard = accessGuard;
        this.secretProvider = secretProvider;
        this.secretResolver = secretResolver;
        this.variableStore = variableStore;
        this.variableResolver = variableResolver;
    }

    @Override
    public SpaceTenant readTenant(String space) {
        return new SpaceTenant(space, tenantOf(space));
    }

    // --- Secrets ---------------------------------------------------------------

    @Override
    public List<SpaceSecret> listSecrets(String space) {
        String tenant = tenantOf(space);
        requireVault();
        try {
            return secretProvider.listKeys(tenant).stream().map(RestSpaceResources::toSecret).toList();
        } catch (ISecretProvider.SecretProviderException e) {
            LOGGER.errorf(e, "Could not list the secrets of space tenant %s", sanitize(tenant));
            throw new InternalServerErrorException("The space's secrets could not be listed.");
        }
    }

    @Override
    public Response storeSecret(String space, String keyName, SecretWrite body) {
        String tenant = tenantOf(space);
        requireVault();
        requireKey(keyName, KEY);
        if (body == null || body.value() == null || body.value().isBlank()) {
            throw new BadRequestException("A secret value is required.");
        }
        var reference = new SecretReference(tenant, keyName);
        try {
            secretProvider.store(reference, body.value(), body.description(), body.allowedAgents());
            // A model built with the old key, or with the unresolved reference, is cached
            // downstream — the same reason RestSecretStore always invalidates.
            secretResolver.invalidateCache(reference);
            LOGGER.infof("Space secret stored: %s/%s by '%s'", sanitize(tenant), sanitize(keyName), sanitize(accessGuard.currentPrincipal()));
            return Response.ok(toSecret(secretProvider.getMetadata(reference))).build();
        } catch (ISecretProvider.SecretNotFoundException e) {
            // Stored a moment ago and gone already: a concurrent delete won. Say what
            // happened rather than claiming success.
            throw new InternalServerErrorException("The secret was stored but could not be read back; it may have been deleted meanwhile.");
        } catch (ISecretProvider.SecretProviderException e) {
            LOGGER.errorf(e, "Could not store space secret %s/%s", sanitize(tenant), sanitize(keyName));
            throw new InternalServerErrorException("The secret could not be stored.");
        }
    }

    @Override
    public Response deleteSecret(String space, String keyName) {
        String tenant = tenantOf(space);
        requireVault();
        requireKey(keyName, KEY);
        var reference = new SecretReference(tenant, keyName);
        try {
            secretProvider.delete(reference);
            secretResolver.invalidateCache(reference);
            LOGGER.infof("Space secret deleted: %s/%s by '%s'", sanitize(tenant), sanitize(keyName), sanitize(accessGuard.currentPrincipal()));
            return Response.noContent().build();
        } catch (ISecretProvider.SecretNotFoundException e) {
            throw new NotFoundException("No secret '" + sanitize(keyName) + "' in this space.");
        } catch (ISecretProvider.SecretProviderException e) {
            LOGGER.errorf(e, "Could not delete space secret %s/%s", sanitize(tenant), sanitize(keyName));
            throw new InternalServerErrorException("The secret could not be deleted.");
        }
    }

    // --- Variables -------------------------------------------------------------

    @Override
    public List<SpaceVariable> listVariables(String space) {
        String tenant = tenantOf(space);
        return variableStore.listAll(tenant).stream()
                .map(variable -> new SpaceVariable(variable.key(), variable.value(), variable.description(),
                        variableReference(tenant, variable.key())))
                .toList();
    }

    @Override
    public Response storeVariable(String space, String key, VariableWrite body) {
        String tenant = tenantOf(space);
        requireKey(key, VARIABLE_KEY);
        if (body == null || body.value() == null) {
            throw new BadRequestException("A value is required.");
        }
        // Not exportable: a space's variables are the space's, and must not travel in
        // an agent export to a deployment where the same tenant id means nothing.
        variableStore.upsert(new GlobalVariable(tenant, key, body.value(), body.description(), false));
        variableResolver.invalidateCache();
        LOGGER.infof("Space variable set: %s/%s by '%s'", sanitize(tenant), sanitize(key), sanitize(accessGuard.currentPrincipal()));
        return Response.ok(new SpaceVariable(key, body.value(), body.description(), variableReference(tenant, key))).build();
    }

    @Override
    public Response deleteVariable(String space, String key) {
        String tenant = tenantOf(space);
        requireKey(key, VARIABLE_KEY);
        variableStore.delete(tenant, key);
        variableResolver.invalidateCache();
        LOGGER.infof("Space variable deleted: %s/%s by '%s'", sanitize(tenant), sanitize(key), sanitize(accessGuard.currentPrincipal()));
        return Response.noContent().build();
    }

    // --- Helpers ---------------------------------------------------------------

    /**
     * The tenant of a space the caller belongs to.
     * <p>
     * Membership, not administration: an administrator writes the deployment-wide
     * vault through {@code /secretstore}, and seeing every space does not make one
     * a member of a team whose secrets these are. Refusing here keeps "who can read
     * the finance team's variables" answerable by looking at the finance team.
     */
    private String tenantOf(String space) {
        if (space == null || space.isBlank()) {
            throw new BadRequestException("space is required — one of the spaces GET /workspaces lists.");
        }
        String trimmed = space.trim();
        if (!accessGuard.callerSpaces().spaces().contains(trimmed)) {
            throw new ForbiddenException("You are not a member of that space.");
        }
        return SpaceTenants.tenantFor(trimmed);
    }

    private void requireVault() {
        if (!secretProvider.isAvailable()) {
            throw new ServiceUnavailableException("The secrets vault is not configured on this deployment (EDDI_VAULT_MASTER_KEY is not set).");
        }
    }

    private static void requireKey(String key, Pattern rule) {
        if (key == null || !rule.matcher(key).matches()) {
            throw new BadRequestException("The name must be 1-128 letters, digits, dots, underscores or hyphens.");
        }
    }

    private static SpaceSecret toSecret(SecretMetadata metadata) {
        return new SpaceSecret(metadata.keyName(), new SecretReference(metadata.tenantId(), metadata.keyName()).toReferenceString(),
                metadata.description(), metadata.allowedAgents(), metadata.createdAt() == null ? null : metadata.createdAt().toString());
    }

    private static String variableReference(String tenant, String key) {
        return "${vars:" + tenant + "/" + key + "}";
    }
}
