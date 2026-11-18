/*
 * Copyright EDDI contributors
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

    /**
     * Whether the caller should log that this output set's language did not match
     * the conversation's. True once per instance — that is, once per deployed
     * configuration; the default never asks for a log.
     */
    default boolean claimLanguageMismatchLog() {
        return false;
    }

    void addOutputEntry(OutputEntry outputEntry);

    Map<String, List<OutputEntry>> getOutputs(List<IOutputFilter> outputFilter);
}
