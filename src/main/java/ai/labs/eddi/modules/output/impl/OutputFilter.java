/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.output.impl;

import ai.labs.eddi.modules.output.IOutputFilter;

/**
 * @author ginccc
 */

class OutputFilter implements IOutputFilter {
    private String action;
    private int occurred;

    public OutputFilter(String action, int occurred) {
        this.action = action;
        this.occurred = occurred;
    }

    public String getAction() {
        return action;
    }

    public int getOccurred() {
        return occurred;
    }
}
