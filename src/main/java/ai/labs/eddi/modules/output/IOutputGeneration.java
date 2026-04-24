/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.output;

import ai.labs.eddi.modules.output.model.OutputEntry;

import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */
public interface IOutputGeneration {

    String getLanguage();

    void addOutputEntry(OutputEntry outputEntry);

    Map<String, List<OutputEntry>> getOutputs(List<IOutputFilter> outputFilter);
}
