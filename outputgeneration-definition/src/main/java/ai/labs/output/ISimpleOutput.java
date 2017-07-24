package ai.labs.output;

import ai.labs.output.model.OutputEntry;

import java.util.List;

/**
 * @author ginccc
 */
public interface ISimpleOutput {
    void addOutputEntry(OutputEntry outputEntry);

    List<List<OutputEntry>> getOutputs(List<IOutputFilter> outputFilter);
}
