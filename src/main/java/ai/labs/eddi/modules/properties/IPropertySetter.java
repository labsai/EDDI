/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.properties;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.modules.properties.model.SetOnActions;
import ai.labs.eddi.modules.nlp.expressions.Expressions;

import java.util.List;

/**
 * @author ginccc
 */
public interface IPropertySetter {
    List<SetOnActions> getSetOnActionsList();

    /**
     * Whether a {@code properties*} context entry of type {@code expressions} sent
     * by a client is honoured. Default: no.
     */
    default boolean acceptsClientContextProperties() {
        return false;
    }

    List<Property> extractProperties(Expressions expressions);
}
