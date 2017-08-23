package ai.labs.output;

import ai.labs.output.model.OutputEntry;

import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */
public interface IOutputGeneration {
    void addOutputEntry(OutputEntry outputEntry);

    Map<String, List<OutputEntry>> getOutputs(List<IOutputFilter> outputFilter);
}
