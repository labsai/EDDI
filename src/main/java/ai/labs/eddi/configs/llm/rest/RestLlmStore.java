/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.llm.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.configs.llm.ILlmStore;
import ai.labs.eddi.configs.llm.IRestLlmStore;
import ai.labs.eddi.configs.rest.RestVersionInfo;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * @author ginccc
 */
@ApplicationScoped
public class RestLlmStore implements IRestLlmStore {
    private static final Logger LOGGER = Logger.getLogger(RestLlmStore.class);
    private final ILlmStore httpCallsStore;
    private final IJsonSchemaCreator jsonSchemaCreator;
    private final RestVersionInfo<LlmConfiguration> restVersionInfo;

    @Inject
    public RestLlmStore(ILlmStore httpCallsStore, IDocumentDescriptorStore documentDescriptorStore, IJsonSchemaCreator jsonSchemaCreator,
            ResourceAccessGuard resourceAccessGuard) {
        restVersionInfo = new RestVersionInfo<>(resourceURI, httpCallsStore, documentDescriptorStore, resourceAccessGuard);
        this.httpCallsStore = httpCallsStore;
        this.jsonSchemaCreator = jsonSchemaCreator;
    }

    @Override
    public Response readJsonSchema() {
        try {
            return Response.ok(jsonSchemaCreator.generateSchema(LlmConfiguration.class)).build();
        } catch (Exception e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public List<DocumentDescriptor> readLlmDescriptors(String filter, Integer index, Integer limit) {
        return restVersionInfo.readDescriptors(filter, index, limit);
    }

    @Override
    public LlmConfiguration readLlm(String id, Integer version) {
        return restVersionInfo.read(id, version);
    }

    @Override
    public Response updateLlm(String id, Integer version, LlmConfiguration llmConfiguration) {
        warnOnPlaintextSecrets(llmConfiguration);
        return restVersionInfo.update(id, version, llmConfiguration);
    }

    @Override
    public Response createLlm(LlmConfiguration llmConfiguration) {
        warnOnPlaintextSecrets(llmConfiguration);
        return restVersionInfo.create(llmConfiguration);
    }

    /**
     * Task parameter names that carry a provider credential, compared
     * case-insensitively. Beyond the generic names: {@code accessToken} is the
     * Hugging Face key and {@code nonAzureApiKey} the OpenAI key the Azure OpenAI
     * builder accepts.
     */
    static final Set<String> SECRET_PARAMETER_NAMES = Set.of("apikey", "nonazureapikey", "accesstoken", "authtoken", "secretkey",
            "secretaccesskey", "privatekey", "password", "clientsecret", "token");

    /**
     * Warns — rather than rejects — when an LLM task stores a credential in
     * plaintext. Same reasoning as {@code RestChannelIntegrationStore}: a plaintext
     * key is returned verbatim by {@code GET /llmstore/llms/{id}} and lands in
     * exports, but {@code setup_agent} deliberately falls back to plaintext when
     * the vault is not configured, so a rejection would break agent creation on
     * every vault-less instance. The log line names the parameter, never the value.
     */
    private static void warnOnPlaintextSecrets(LlmConfiguration config) {
        List<String> plaintext = plaintextSecretParameters(config);
        if (!plaintext.isEmpty()) {
            LOGGER.warnf("LLM configuration stores %s in plaintext — it is returned verbatim by GET /llmstore/llms/{id} and "
                    + "included in exports. Store the key in the secrets vault and reference it as ${vault:<key>} instead.",
                    String.join(", ", plaintext));
        }
    }

    /**
     * The credential parameters of {@code config} whose value is a literal rather
     * than a reference ({@code ${vault:…}}, {@code ${vars:…}},
     * {@code ${connection:…}}) or a template ({@code {properties.x}}).
     */
    static List<String> plaintextSecretParameters(LlmConfiguration config) {
        List<String> found = new ArrayList<>();
        if (config == null || config.tasks() == null) {
            return found;
        }
        for (int i = 0; i < config.tasks().size(); i++) {
            var task = config.tasks().get(i);
            if (task == null || task.getParameters() == null) {
                continue;
            }
            for (var parameter : task.getParameters().entrySet()) {
                String name = parameter.getKey();
                String value = parameter.getValue();
                if (name != null && SECRET_PARAMETER_NAMES.contains(name.toLowerCase(Locale.ROOT)) && value != null && !value.isBlank()
                        && !value.contains("{")) {
                    found.add("tasks[" + i + "].parameters." + name);
                }
            }
        }
        return found;
    }

    @Override
    public Response deleteLlm(String id, Integer version, Boolean permanent) {
        return restVersionInfo.delete(id, version, permanent);
    }

    @Override
    public Response duplicateLlm(String id, Integer version) {
        restVersionInfo.validateParameters(id, version);
        LlmConfiguration llmConfiguration = restVersionInfo.read(id, version);
        return restVersionInfo.create(llmConfiguration);
    }

    @Override
    public String getResourceURI() {
        return restVersionInfo.getResourceURI();
    }

    @Override
    public IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return httpCallsStore.getCurrentResourceId(id);
    }
}
