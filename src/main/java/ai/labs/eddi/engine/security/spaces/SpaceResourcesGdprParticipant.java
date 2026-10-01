/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.configs.variables.IGlobalVariableStore;
import ai.labs.eddi.configs.variables.model.GlobalVariable;
import ai.labs.eddi.engine.gdpr.IGdprParticipant;
import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Erases and exports the secrets and variables in a person's <em>personal</em>
 * space — the {@code u.<slug>.<hash>} tenant they manage in
 * {@code /spacestore}.
 * <p>
 * Only the personal space. A team space's secrets and variables belong to the
 * team: erasing one member must not take the team's API key with them, and
 * exporting them would hand one member what the team owns.
 * <p>
 * The export lists secrets by name, description and dates, never by value: a
 * secret is a credential, not personal data, and its ciphertext means nothing
 * outside this deployment — the same line the connection-grant export draws.
 */
@ApplicationScoped
public class SpaceResourcesGdprParticipant implements IGdprParticipant {

    private final ISecretProvider secretProvider;
    private final IGlobalVariableStore variableStore;
    private final GlobalVariableResolver variableResolver;

    @Inject
    public SpaceResourcesGdprParticipant(ISecretProvider secretProvider, IGlobalVariableStore variableStore,
            GlobalVariableResolver variableResolver) {
        this.secretProvider = secretProvider;
        this.variableStore = variableStore;
        this.variableResolver = variableResolver;
    }

    @Override
    public String name() {
        return "personalSpaceResources";
    }

    @Override
    public long erase(String userId) throws Exception {
        String tenant = SpaceTenants.tenantFor(Subjects.personalSpace(userId));
        long removed = 0;
        if (secretProvider.isAvailable()) {
            for (SecretMetadata secret : secretProvider.listKeys(tenant)) {
                secretProvider.delete(new SecretReference(tenant, secret.keyName()));
                removed++;
            }
        }
        List<GlobalVariable> variables = variableStore.listAll(tenant);
        for (GlobalVariable variable : variables) {
            variableStore.delete(tenant, variable.key());
            removed++;
        }
        if (!variables.isEmpty()) {
            variableResolver.invalidateCache();
        }
        return removed;
    }

    @Override
    public Object export(String userId) throws Exception {
        String tenant = SpaceTenants.tenantFor(Subjects.personalSpace(userId));
        List<Map<String, Object>> secrets = secretProvider.isAvailable()
                ? secretProvider.listKeys(tenant).stream().map(SpaceResourcesGdprParticipant::describe).toList()
                : List.of();
        List<GlobalVariable> variables = variableStore.listAll(tenant);
        if (secrets.isEmpty() && variables.isEmpty()) {
            return null;
        }
        Map<String, Object> held = new LinkedHashMap<>();
        held.put("tenant", tenant);
        held.put("secrets", secrets);
        held.put("variables", variables);
        return held;
    }

    private static Map<String, Object> describe(SecretMetadata secret) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("keyName", secret.keyName());
        entry.put("description", secret.description());
        entry.put("createdAt", secret.createdAt() == null ? null : secret.createdAt().toString());
        return entry;
    }
}
