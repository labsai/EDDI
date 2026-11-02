/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.dictionary.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.configs.patch.PatchInstruction;
import ai.labs.eddi.configs.dictionary.IDictionaryStore;
import ai.labs.eddi.configs.dictionary.IRestDictionaryStore;
import ai.labs.eddi.configs.dictionary.model.DictionaryConfiguration;
import ai.labs.eddi.configs.rest.RestVersionInfo;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;

/**
 * @author ginccc
 */

@ApplicationScoped
public class RestDictionaryStore implements IRestDictionaryStore {
    private final IDictionaryStore regularDictionaryStore;
    private final IJsonSchemaCreator jsonSchemaCreator;
    private final RestVersionInfo<DictionaryConfiguration> restVersionInfo;

    @Inject
    public RestDictionaryStore(IDictionaryStore regularDictionaryStore, IDocumentDescriptorStore documentDescriptorStore,
            IJsonSchemaCreator jsonSchemaCreator,
            ResourceAccessGuard resourceAccessGuard) {
        restVersionInfo = new RestVersionInfo<>(resourceURI, regularDictionaryStore, documentDescriptorStore, resourceAccessGuard);
        this.regularDictionaryStore = regularDictionaryStore;
        this.jsonSchemaCreator = jsonSchemaCreator;
    }

    @Override
    public Response readJsonSchema() {
        try {
            return Response.ok(jsonSchemaCreator.generateSchema(DictionaryConfiguration.class)).build();
        } catch (Exception e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public List<DocumentDescriptor> readRegularDictionaryDescriptors(String filter, Integer index, Integer limit) {
        return restVersionInfo.readDescriptors(filter, index, limit);
    }

    @Override
    public DictionaryConfiguration readRegularDictionary(String id, Integer version, String filter, String order, Integer index, Integer limit) {
        return restVersionInfo.read(id, version);
    }

    @Override
    public List<String> readExpressions(String id, Integer version, String filter, String order, Integer index, Integer limit) {
        restVersionInfo.requireViewAccess(id);
        try {
            return regularDictionaryStore.readExpressions(id, version, filter, order, index, limit);
        } catch (IResourceStore.ResourceStoreException | IResourceStore.ResourceNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Response updateRegularDictionary(String id, Integer version, DictionaryConfiguration regularDictionaryConfiguration) {
        Response invalid = validateRegExs(regularDictionaryConfiguration);
        if (invalid != null) {
            return invalid;
        }
        return restVersionInfo.update(id, version, regularDictionaryConfiguration);
    }

    @Override
    public Response createRegularDictionary(DictionaryConfiguration regularDictionaryConfiguration) {
        Response invalid = validateRegExs(regularDictionaryConfiguration);
        if (invalid != null) {
            return invalid;
        }
        return restVersionInfo.create(regularDictionaryConfiguration);
    }

    /**
     * Refuses a dictionary whose {@code regExs} hold a pattern Java cannot compile.
     * <p>
     * The pattern is compiled when the agent is DEPLOYED, so a typo saved here
     * surfaced as a deployment failure of the whole agent, long after the editor
     * had moved on and with a stack trace in place of the column the typo is in.
     * Saying so at save time, with Java's own description of what is wrong, is the
     * honest place for it.
     *
     * @return a 400 naming the pattern and the syntax error, or null when every
     *         pattern compiles
     */
    private static Response validateRegExs(DictionaryConfiguration configuration) {
        if (configuration == null || configuration.getRegExs() == null) {
            return null;
        }
        for (var regExConfig : configuration.getRegExs()) {
            String regEx = regExConfig != null ? regExConfig.getRegEx() : null;
            if (regEx == null) {
                // The provider drops a null pattern with a WARN at deploy, so the entry
                // would be saved and then silently absent from the dictionary.
                return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "a regExs entry has no regEx")).build();
            }
            try {
                Pattern.compile(regEx);
            } catch (PatternSyntaxException e) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "regEx '" + regEx + "' is not a valid regular expression: " + e.getDescription()
                                + (e.getIndex() >= 0 ? " near index " + e.getIndex() : "")))
                        .build();
            }
        }
        return null;
    }

    @Override
    public Response deleteRegularDictionary(String id, Integer version, Boolean permanent) {
        return restVersionInfo.delete(id, version, permanent);
    }

    @Override
    public Response patchRegularDictionary(String id, Integer version, List<PatchInstruction<DictionaryConfiguration>> patchInstructions) {
        restVersionInfo.requireEditAccess(id);
        try {
            var currentDictionaryConfiguration = regularDictionaryStore.read(id, version);
            var patchedDictionaryConfiguration = patchDocument(currentDictionaryConfiguration, patchInstructions);

            return updateRegularDictionary(id, version, patchedDictionaryConfiguration);

        } catch (IResourceStore.ResourceStoreException | IResourceStore.ResourceNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    private DictionaryConfiguration patchDocument(DictionaryConfiguration currentDictionaryConfig,
                                                  List<PatchInstruction<DictionaryConfiguration>> patchInstructions)
            throws IResourceStore.ResourceStoreException {

        for (var patchInstruction : patchInstructions) {
            var regularConfigPatch = patchInstruction.getDocument();
            // A missing operation is a malformed request (400). Switching on null threw
            // a NullPointerException, reported as a 500.
            if (patchInstruction.getOperation() == null) {
                throw new IllegalArgumentException("Patch operation must be either SET or DELETE!");
            }
            switch (patchInstruction.getOperation()) {
                case SET -> {
                    currentDictionaryConfig.getWords().removeAll(regularConfigPatch.getWords());
                    currentDictionaryConfig.getWords().addAll(regularConfigPatch.getWords());
                    currentDictionaryConfig.getPhrases().removeAll(regularConfigPatch.getPhrases());
                    currentDictionaryConfig.getPhrases().addAll(regularConfigPatch.getPhrases());
                }
                case DELETE -> {
                    currentDictionaryConfig.getWords().removeAll(regularConfigPatch.getWords());
                    currentDictionaryConfig.getPhrases().removeAll(regularConfigPatch.getPhrases());
                }
                default -> throw new IResourceStore.ResourceStoreException("Patch operation must be either SET or DELETE!");
            }
        }

        return currentDictionaryConfig;
    }

    @Override
    public Response duplicateRegularDictionary(String id, Integer version) {
        restVersionInfo.requireViewAccess(id);
        restVersionInfo.validateParameters(id, version);
        try {
            var regularDictionaryConfiguration = regularDictionaryStore.read(id, version);
            // A duplicate is a create: a pattern stored before the check existed must
            // not become a new resource the create path refuses.
            return createRegularDictionary(regularDictionaryConfiguration);
        } catch (IResourceStore.ResourceNotFoundException | IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
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
        return regularDictionaryStore.getCurrentResourceId(id);
    }
}
