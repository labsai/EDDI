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
