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
import ai.labs.eddi.secrets.sanitize.ConfigSecretRedactor;
import ai.labs.eddi.configs.rest.RedactionMarkerGuard;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;

import java.util.List;
import java.util.Set;

/**
 * @author ginccc
 */
@ApplicationScoped
public class RestLlmStore implements IRestLlmStore {
    private static final Logger LOGGER = Logger.getLogger(RestLlmStore.class);

    /**
     * What a non-administrator reads in place of a plaintext credential — the
     * shared {@link ConfigSecretRedactor#REDACTED} marker.
     */
    static final String REDACTED = ConfigSecretRedactor.REDACTED;

    /**
     * Maps keyed by tool names rather than parameter names: a tool called
     * {@code get_token} is not a credential field.
     */
    public static final Set<String> NOT_CONFIGURATION = Set.of("tasks[].toolCacheScopes");

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
     * {@link #readLlm} without the read-redaction, for server-side work an editor's
     * request starts whose result never reaches the caller as this document —
     * upgrade and sync compare against it and write it back
     * ({@code StructuralMatcher}, {@code UpgradeExecutor}). Comparing against the
     * redacted copy would diff against the marker and then write it over the stored
     * credential. The same access check applies.
     */
    public LlmConfiguration readUnredacted(String id, Integer version) {
        return restVersionInfo.read(id, version);
    }

    /**
     * A copy of {@code stored} with every plaintext credential redacted by the
     * shared {@link ConfigSecretRedactor} — task parameters, but also
     * {@code a2aAgents[].apiKey}, {@code mcpServers[].apiKey},
     * {@code modelCascade.steps[].parameters} and
     * {@code modelCascade.judgeModel.parameters}, which the first version of this
     * redaction did not reach (M1 of the 2026-10-17 review). The stored object is
     * never touched, because the store may hand out a cached instance.
     */
    static LlmConfiguration redactPlaintextSecrets(LlmConfiguration stored) {
        return ConfigSecretRedactor.redact(stored, LlmConfiguration.class, NOT_CONFIGURATION);
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
            LOGGER.warnf("LLM configuration stores %s in plaintext — GET /llmstore/llms/{id} returns it to administrators "
                    + "(redacted for everyone else) and it is scrubbed from exports. Store the key in the secrets vault and "
                    + "reference it as ${vault:<key>} instead.",
                    String.join(", ", plaintext));
        }
    }

    /**
     * Where {@code config} stores a credential as a literal rather than a reference
     * ({@code ${vault:…}}, {@code ${vars:…}}, {@code ${connection:…}}) or a
     * template ({@code {properties.x}}), as paths such as
     * {@code tasks[0].parameters.apiKey}. The same rules the read redaction
     * applies, so what is warned about on write is exactly what an editor cannot
     * read back.
     */
    static List<String> plaintextSecretParameters(LlmConfiguration config) {
        return ConfigSecretRedactor.plaintextSecretPaths(config, NOT_CONFIGURATION);
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
        // A document read through the redacted view must not be stored with the
        // marker in place of the credential — see RedactionMarkerGuard.
        RedactionMarkerGuard.requireNone(configuration);
        if (callerTokenAuthorPolicy != null) {
            callerTokenAuthorPolicy.requireMayAuthor(configuration, KIND);
        }
    }

    @Override
    public String getResourceURI() {
        return restVersionInfo.getResourceURI();
    }

    @Override
    public void requireCurrentVersionAccess(String id) {
        restVersionInfo.requireViewAccess(id);
    }

    @Override
    public IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return httpCallsStore.getCurrentResourceId(id);
    }
}
