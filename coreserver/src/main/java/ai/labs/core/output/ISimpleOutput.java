package ai.labs.core.output;

import java.util.List;

/**
 * @author ginccc
 */
public interface ISimpleOutput {
    void addOutputEntry(OutputEntry outputEntry);

    List<List<OutputEntry>> getOutputs(List<IOutputFilter> outputFilter);
}
