/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.ui;

import ai.labs.eddi.configs.branding.BrandingService;
import ai.labs.eddi.configs.branding.BrandingTestSupport;

/** A shell renderer over an empty brand store, for the shell resource tests. */
final class ShellTestSupport {

    private ShellTestSupport() {
    }

    static BrandedShellRenderer renderer() {
        return new BrandedShellRenderer(new BrandingService(new BrandingTestSupport.MemoryStore(), new BrandingTestSupport.Config()));
    }
}
