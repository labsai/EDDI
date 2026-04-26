/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.output;

/**
 * @author ginccc
 */
public interface IOutputFilter {
    String getAction();

    int getOccurred();
}
