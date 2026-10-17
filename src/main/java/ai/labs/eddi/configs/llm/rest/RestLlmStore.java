/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.llm.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.configs.llm.ILlmStore;
import ai.labs.eddi.configs.llm.IRestLlmStore;
import ai.labs.eddi.configs.patch.ConfigJsonPatcher;
import ai.labs.eddi.engine.security.CallerTokenAuthorPolicy;
import ai.labs.eddi.configs.rest.RestVersionInfo;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * @author ginccc
 */
@ApplicationScoped
public class RestLlmStore implements IRestLlmStore {
    private static final Logger LOGGER = Logger.getLogger(RestLlmStore.class);

    /**
     * What a non-administrator reads in place of a plaintext credential. Carries no
     * {@code {}, so it can never be read as a template or a reference — and it is
     * not a key, so a document echoed back with it fails at the provider rather
     * than quietly keeping the literal.
     */
    static final String REDACTED = "<redacted: a plaintext credential - an eddi-admin must move it into the vault and reference it as ${vault:...}>";

    private static final ObjectMapper MAPPER = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final ILlmStore httpCallsStore;
    private final IJsonSchemaCreator jsonSchemaCreator;
    private final RestVersionInfo<LlmConfiguration> restVersionInfo;
    private final ResourceAccessGuard resourceAccessGuard;

    // Field-injected, like the other optional collaborators, so the direct
    // constructions in tests keep compiling.
    @Inject
    ConfigJsonPatcher jsonPatcher;

    // Field-injected so the direct constructions in tests keep compiling; null
    // there, which skips the check.
    @Inject
    CallerTokenAuthorPolicy callerTokenAuthorPolicy;

    private static final String KIND = "langchain";

    @Inject
    public RestLlmStore(ILlmStore httpCallsStore, IDocumentDescriptorStore documentDescriptorStore, IJsonSchemaCreator jsonSchemaCreator,
            ResourceAccessGuard resourceAccessGuard) {
        restVersionInfo = new RestVersionInfo<>(resourceURI, httpCallsStore, documentDescriptorStore, resourceAccessGuard);
        this.httpCallsStore = httpCallsStore;
        this.jsonSchemaCreator = jsonSchemaCreator;
        this.resourceAccessGuard = resourceAccessGuard;
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
        LlmConfiguration stored = restVersionInfo.read(id, version);
        // An administrator sees the document as stored, plaintext key included, so it
        // can be found and moved into the vault. Anyone else admitted here
        // (eddi-editor) gets a copy with such literals redacted: the store only warns
        // on a plaintext key at write time, and without this an editor who PUT one
        // could read it back — or read back one another editor saved. Same rule as
        // RestConnectionStore.readConnection. isAdmin() is true for everyone when
        // authorization.enabled=false, which is also when @RolesAllowed is off.
        return resourceAccessGuard.isAdmin() ? stored : redactPlaintextSecrets(stored);
    }

    /**
     * A copy of {@code stored} with every plaintext credential parameter (as
     * {@link #plaintextSecretParameters} finds them) replaced by {@link #REDACTED}.
     * The stored object is never touched, because the store may hand out a cached
     * instance.
     */
    static LlmConfiguration redactPlaintextSecrets(LlmConfiguration stored) {
        if (stored == null || plaintextSecretParameters(stored).isEmpty()) {
            return stored;
        }
        LlmConfiguration copy;
        try {
            copy = MAPPER.readValue(MAPPER.writeValueAsBytes(stored), LlmConfiguration.class);
        } catch (IOException e) {
            throw sneakyThrow(e);
        }
        for (var task : copy.tasks()) {
            if (task == null || task.getParameters() == null) {
                continue;
            }
            for (var parameter : task.getParameters().entrySet()) {
                if (isPlaintextSecret(parameter.getKey(), parameter.getValue())) {
                    parameter.setValue(REDACTED);
                }
            }
        }
        return copy;
    }

    @Override
    public Response updateLlm(String id, Integer version, LlmConfiguration llmConfiguration) {
        requireMayAuthorCallerToken(llmConfiguration);
        warnOnPlaintextSecrets(llmConfiguration);
        return restVersionInfo.update(id, version, llmConfiguration);
    }

    @Override
    public Response patchLlm(String id, Integer version, String jsonPatch) {
        return restVersionInfo.patch(id, version, jsonPatch, LlmConfiguration.class, jsonPatcher, patched -> {
            // The same pre-write checks updateLlm runs.
            requireMayAuthorCallerToken(patched);
            warnOnPlaintextSecrets(patched);
            return null;
        });
    }

    @Override
    public Response createLlm(LlmConfiguration llmConfiguration) {
        requireMayAuthorCallerToken(llmConfiguration);
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
                if (isPlaintextSecret(parameter.getKey(), parameter.getValue())) {
                    found.add("tasks[" + i + "].parameters." + parameter.getKey());
                }
            }
        }
        return found;
    }

    /**
     * A reference ({@code ${vault:…}}, {@code ${vars:…}}, {@code ${connection:…}},
     * …) or a Qute template ({@code {properties.key}}). Anchored to those shapes
     * rather than to a brace: a literal such as {@code hunter{two} is a credential
     * with a brace in it, not a template.
     */
    private static final Pattern REFERENCE_OR_TEMPLATE = Pattern.compile("\\$\\{[A-Za-z]+:[^}]*}|\\{[A-Za-z_][^{}]*}");

    /**
     * Whether one parameter is a credential by name holding a literal rather than a
     * reference or template.
     */
    private static boolean isPlaintextSecret(String name, String value) {
        return name != null && SECRET_PARAMETER_NAMES.contains(name.toLowerCase(Locale.ROOT)) && value != null && !value.isBlank()
                && !REFERENCE_OR_TEMPLATE.matcher(value).find();
    }

    @Override
    public Response deleteLlm(String id, Integer version, Boolean permanent) {
        return restVersionInfo.delete(id, version, permanent);
    }

    @Override
    public Response duplicateLlm(String id, Integer version) {
        restVersionInfo.validateParameters(id, version);
        LlmConfiguration llmConfiguration = restVersionInfo.read(id, version);
        // A copy is a new resource authored by whoever duplicates it.
        requireMayAuthorCallerToken(llmConfiguration);
        return restVersionInfo.create(llmConfiguration);
    }

    /**
     * Refuses {@code ${caller:token}} from a caller who may not author it — see
     * {@link CallerTokenAuthorPolicy}. Throws, never returns a response: the import
     * path treats a non-200 update as "create it directly instead".
     */
    private void requireMayAuthorCallerToken(Object configuration) {
        if (callerTokenAuthorPolicy != null) {
            callerTokenAuthorPolicy.requireMayAuthor(configuration, KIND);
        }
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
