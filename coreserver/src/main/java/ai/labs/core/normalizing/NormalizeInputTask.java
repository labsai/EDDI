package ai.labs.core.normalizing;

import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.lifecycle.IllegalExtensionConfigurationException;
import ai.labs.lifecycle.PackageConfigurationException;
import ai.labs.lifecycle.UnrecognizedExtensionException;
import ai.labs.memory.Data;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */

public class NormalizeInputTask implements ILifecycleTask {
    private static final String ALLOWED_CHARS_IDENTIFIER = "allowedChars";
    private static final String CONVERT_UMLAUTE_IDENTIFIER = "convertUmlaute";
    private InputNormalizer normalizer;
    private String allowedChars = InputNormalizer.DEFAULT_DEFINED_CHARS;
    private boolean convertUmlaute = true;

    public NormalizeInputTask() {
    }

    public void init() {
        this.normalizer = new DefaultInputNormalizer();
    }

    @Override
    public String getId() {
        return normalizer.getClass().toString();
    }

    @Override
    public Object getComponent() {
        return normalizer;
    }

    @Override
    public List<String> getComponentDependencies() {
        return Collections.emptyList();
    }

    @Override
    public List<String> getOutputDependencies() {
        return Collections.emptyList();
    }

    @Override
    public void executeTask(IConversationMemory memory) {
        IData latestInput = memory.getCurrentStep().getLatestData("input");
        if (latestInput == null) {
            return;
        }
        String input = (String) latestInput.getResult();
        String formattedInput = normalizer.normalizeInput(input, allowedChars, true, convertUmlaute);
        memory.getCurrentStep().storeData(new Data("input:formatted", formattedInput));
    }

    @Override
    public void configure(Map<String, Object> configuration) throws PackageConfigurationException {
        if (configuration.containsKey(ALLOWED_CHARS_IDENTIFIER)) {
            allowedChars = configuration.get(ALLOWED_CHARS_IDENTIFIER).toString();
        }

        if (configuration.containsKey(CONVERT_UMLAUTE_IDENTIFIER)) {
            convertUmlaute = Boolean.parseBoolean(configuration.get(CONVERT_UMLAUTE_IDENTIFIER).toString());
        }
    }

    @Override
    public void setExtensions(Map<String, Object> extensions) throws UnrecognizedExtensionException, IllegalExtensionConfigurationException {
        // not implemented
    }
}
