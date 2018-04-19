package ai.labs.normalizer.impl;

import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.memory.Data;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;
import ai.labs.resources.rest.extensions.model.ExtensionDescriptor;
import ai.labs.resources.rest.extensions.model.ExtensionDescriptor.ConfigValue;

import java.util.Map;

import static ai.labs.resources.rest.extensions.model.ExtensionDescriptor.FieldType.BOOLEAN;
import static ai.labs.resources.rest.extensions.model.ExtensionDescriptor.FieldType.STRING;

/**
 * @author ginccc
 */

public class NormalizeInputTask implements ILifecycleTask {
    private static final String ID = "ai.labs.normalizer";
    private static final String ALLOWED_CHARS_IDENTIFIER = "allowedChars";
    private static final String CONVERT_SPECIAL_CHARACTER_IDENTIFIER = "convertSpecialCharacter";
    private InputNormalizer normalizer;
    private String allowedChars = InputNormalizer.DEFAULT_DEFINED_CHARS;
    private boolean convertSpecialCharacter = true;

    public NormalizeInputTask() {
    }

    public void init() {
        this.normalizer = new DefaultInputNormalizer();
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public Object getComponent() {
        return normalizer;
    }

    @Override
    public void executeTask(IConversationMemory memory) {
        IData<String> latestInput = memory.getCurrentStep().getLatestData("input");
        if (latestInput == null) {
            return;
        }

        String input = latestInput.getResult();
        String formattedInput = normalizer.normalizeInput(input, allowedChars, true, convertSpecialCharacter);
        memory.getCurrentStep().storeData(new Data<>("input:formatted", formattedInput));
    }

    @Override
    public void configure(Map<String, Object> configuration) {
        if (configuration.containsKey(ALLOWED_CHARS_IDENTIFIER)) {
            allowedChars = configuration.get(ALLOWED_CHARS_IDENTIFIER).toString();
        }

        if (configuration.containsKey(CONVERT_SPECIAL_CHARACTER_IDENTIFIER)) {
            convertSpecialCharacter = Boolean.parseBoolean(configuration.get(CONVERT_SPECIAL_CHARACTER_IDENTIFIER).toString());
        }
    }

    @Override
    public ExtensionDescriptor getExtensionDescriptor() {
        ExtensionDescriptor extensionDescriptor = new ExtensionDescriptor(ID);
        extensionDescriptor.setDisplayName("Input Normalizer");
        ConfigValue allowedCharacters = new ConfigValue("Allowed Characters", STRING, true, allowedChars);
        extensionDescriptor.getConfigs().put(ALLOWED_CHARS_IDENTIFIER, allowedCharacters);
        ConfigValue convertSpecialCharacters = new ConfigValue("Convert Special Characters", BOOLEAN, true, true);
        extensionDescriptor.getConfigs().put(CONVERT_SPECIAL_CHARACTER_IDENTIFIER, convertSpecialCharacters);
        return extensionDescriptor;
    }
}
