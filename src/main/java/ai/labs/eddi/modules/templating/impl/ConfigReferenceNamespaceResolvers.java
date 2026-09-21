/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating.impl;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Pass-through resolvers for the configuration references that are resolved
 * after templating: {@code ${vault:...}}, {@code ${eddivault:...}},
 * {@code ${connection:...}} and {@code ${vars:...}}. See
 * {@link ReferencePassThroughNamespaceResolver}.
 * <p>
 * Quarkus registers every {@code NamespaceResolver} bean with the Qute engine,
 * so each namespace is its own bean.
 */
public final class ConfigReferenceNamespaceResolvers {

    private ConfigReferenceNamespaceResolvers() {
    }

    /** {@code ${vault:key}} and {@code ${vault:tenant/key}}. */
    @ApplicationScoped
    public static class Vault extends ReferencePassThroughNamespaceResolver {
        public Vault() {
            super("vault");
        }
    }

    /** The legacy {@code ${eddivault:...}} form, still accepted by the vault. */
    @ApplicationScoped
    public static class LegacyVault extends ReferencePassThroughNamespaceResolver {
        public LegacyVault() {
            super("eddivault");
        }
    }

    /** {@code ${connection:name}}, resolved to a credential header. */
    @ApplicationScoped
    public static class Connection extends ReferencePassThroughNamespaceResolver {
        public Connection() {
            super("connection");
        }
    }

    /** {@code ${vars:name}}, resolved from the global variable store. */
    @ApplicationScoped
    public static class GlobalVariable extends ReferencePassThroughNamespaceResolver {
        public GlobalVariable() {
            super("vars");
        }
    }
}
