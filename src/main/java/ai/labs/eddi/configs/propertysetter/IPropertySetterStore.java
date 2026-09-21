/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.propertysetter;

import ai.labs.eddi.configs.propertysetter.model.PropertySetterConfiguration;
import ai.labs.eddi.datastore.IResourceStore;

/**
 * Persistence store for property-setter configurations, which assign
 * conversation properties when named actions fire. Loaded by
 * {@code ResourceClientLibrary} into the lifecycle pipeline and edited through
 * the REST property-setter API.
 */
public interface IPropertySetterStore extends IResourceStore<PropertySetterConfiguration> {
    // class for reflection purposes
}
