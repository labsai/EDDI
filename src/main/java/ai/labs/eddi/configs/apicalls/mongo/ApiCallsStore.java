/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.apicalls.mongo;

import ai.labs.eddi.configs.apicalls.IApiCallsStore;
import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.properties.SecretScopeValidation;
import ai.labs.eddi.datastore.AbstractResourceStore;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author ginccc
 */
@ApplicationScoped
public class ApiCallsStore extends AbstractResourceStore<ApiCallsConfiguration> implements IApiCallsStore {

    @Inject
    public ApiCallsStore(IResourceStorageFactory storageFactory, IDocumentBuilder documentBuilder) {
        super(storageFactory, "apicalls", documentBuilder, ApiCallsConfiguration.class);
    }

    /**
     * Rejects {@code scope: "secret"} property instructions that can never be
     * vaulted — see {@link SecretScopeValidation}.
     */
    @Override
    protected void validate(ApiCallsConfiguration content) {
        if (content == null || content.getHttpCalls() == null) {
            return;
        }
        for (int i = 0; i < content.getHttpCalls().size(); i++) {
            ApiCall call = content.getHttpCalls().get(i);
            if (call != null) {
                SecretScopeValidation.validate(call.getPreRequest(), "httpCalls[" + i + "].preRequest");
                SecretScopeValidation.validate(call.getPostResponse(), "httpCalls[" + i + "].postResponse");
            }
        }
    }

    @Override
    public List<String> readActions(String id, Integer version, String filter, Integer limit)
            throws ResourceNotFoundException, ResourceStoreException {

        List<String> actions = read(id, version).getHttpCalls().stream().map(ApiCall::getActions).flatMap(Collection::stream)
                .collect(Collectors.toList());

        return limit > 0 ? actions.subList(0, Math.min(limit, actions.size())) : actions;
    }
}
