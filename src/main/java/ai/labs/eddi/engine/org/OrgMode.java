/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

/**
 * {@code eddi.org.mode}. {@link #SINGLE} is the default and the only mode this
 * release starts in: exactly one org, {@link OrgId#DEFAULT}, and no behaviour
 * change. {@link #MULTI} becomes available once the enablement migration and
 * its startup preconditions exist (plan §5.1); until then {@link OrgConfig}
 * refuses it at startup.
 */
public enum OrgMode {
    SINGLE, MULTI
}
