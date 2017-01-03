package io.sls.core.normalizing;

import ai.labs.lifecycle.AbstractLifecycleTask;
import ai.labs.lifecycle.ILifecycleTask;
import io.sls.memory.IConversationMemory;
import io.sls.memory.IData;
import io.sls.memory.impl.Data;

import javax.inject.Singleton;
import java.util.Collections;
import java.util.List;

/**
 * @author ginccc
 */
@Singleton
public class NormalizeInputTask extends AbstractLifecycleTask implements ILifecycleTask {
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
        IData latestInput = memory.getCurrentStep().getLatestData("input");
        if (latestInput == null) {
            return;
        }
        String input = (String) latestInput.getResult();
        String formattedInput = normalizer.normalizeInput(input);
        memory.getCurrentStep().storeData(new Data("input:formatted", formattedInput));
    }
}
