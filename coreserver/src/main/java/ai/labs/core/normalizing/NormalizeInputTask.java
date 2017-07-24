package ai.labs.core.normalizing;

import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.memory.Data;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;

import javax.inject.Singleton;
import java.util.Collections;
import java.util.List;

/**
 * @author ginccc
 */
@Singleton
public class NormalizeInputTask implements ILifecycleTask {
    private InputNormalizer normalizer;

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
        IData<String> latestInput = memory.getCurrentStep().getLatestData("input");
        if (latestInput == null) {
            return;
        }
        String input = latestInput.getResult();
        String formattedInput = normalizer.normalizeInput(input);
        memory.getCurrentStep().storeData(new Data<>("input:formatted", formattedInput));
    }
}
