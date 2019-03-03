package ai.labs.smtpclient.impl;

import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.lifecycle.LifecycleException;
import ai.labs.lifecycle.PackageConfigurationException;
import ai.labs.memory.IConversationMemory;
import ai.labs.resources.rest.extensions.model.ExtensionDescriptor;

import java.util.Map;

public class SendEmailTask implements ILifecycleTask {
    @Override
    public String getId() {
        return null;
    }

    @Override
    public Object getComponent() {
        return null;
    }

    @Override
    public void executeTask(IConversationMemory memory) throws LifecycleException {

    }

    @Override
    public void configure(Map<String, Object> configuration) throws PackageConfigurationException {

    }

    @Override
    public ExtensionDescriptor getExtensionDescriptor() {
        return null;
    }
}
