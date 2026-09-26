/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.propertysetter.mongo;

import ai.labs.eddi.configs.properties.SecretScopeValidation;
import ai.labs.eddi.configs.propertysetter.IPropertySetterStore;
import ai.labs.eddi.configs.propertysetter.model.PropertySetterConfiguration;
import ai.labs.eddi.datastore.AbstractResourceStore;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * @author ginccc
 */
@ApplicationScoped
public class PropertySetterStore extends AbstractResourceStore<PropertySetterConfiguration> implements IPropertySetterStore {

    @Inject
    public PropertySetterStore(IResourceStorageFactory storageFactory, IDocumentBuilder documentBuilder) {
        super(storageFactory, "propertysetter", documentBuilder, PropertySetterConfiguration.class);
    }

    /**
     * Rejects {@code scope: "secret"} instructions that can never be vaulted — see
     * {@link SecretScopeValidation}.
     */
    @Override
    protected void validate(PropertySetterConfiguration content) {
        if (content == null || content.getSetOnActions() == null) {
            return;
        }
        for (int i = 0; i < content.getSetOnActions().size(); i++) {
            var setOnActions = content.getSetOnActions().get(i);
            if (setOnActions != null) {
                SecretScopeValidation.validate(setOnActions.getSetProperties(), "setOnActions[" + i + "].setProperties");
            }
        }
    }
}
