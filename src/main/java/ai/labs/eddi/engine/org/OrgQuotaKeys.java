/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Objects;

/**
 * The quota and usage counter key of an org (plan §12).
 * <p>
 * In single mode it is the configured {@code eddi.tenant.default-id}, exactly
 * the key every counter already uses, so nothing is re-keyed. In multi mode it
 * is the org id. A quota key is not an org id: do not use it for anything else.
 */
@ApplicationScoped
public class OrgQuotaKeys {

    private final String defaultTenantId;

    @Inject
    public OrgQuotaKeys(@ConfigProperty(name = "eddi.tenant.default-id", defaultValue = "default") String defaultTenantId) {
        this.defaultTenantId = defaultTenantId;
    }

    /** The quota key of {@code org}. */
    public String keyFor(OrgId org) {
        Objects.requireNonNull(org, "org");
        return OrgContext.mode() == OrgMode.SINGLE ? defaultTenantId : org.value();
    }

    /** The quota key of the bound org ({@link OrgContext#require()}). */
    public String current() {
        return keyFor(OrgContext.require());
    }
}
