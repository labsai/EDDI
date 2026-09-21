/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating.impl;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Lets {@code ${caller:...}} references survive Qute templating unchanged, so a
 * header of {@code "Bearer ${caller:token}"} reaches
 * {@code CallerIdentityResolver}. See
 * {@link ReferencePassThroughNamespaceResolver}.
 *
 * @author ginccc
 * @since 6.2.0
 */
@ApplicationScoped
public class CallerNamespaceResolver extends ReferencePassThroughNamespaceResolver {

    public static final String NAMESPACE = "caller";

    public CallerNamespaceResolver() {
        super(NAMESPACE);
    }
}
