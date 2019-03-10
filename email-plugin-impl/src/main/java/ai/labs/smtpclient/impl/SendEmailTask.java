package ai.labs.smtpclient.impl;

import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.memory.IConversationMemory;
import ai.labs.resources.rest.extensions.model.ExtensionDescriptor;

import java.util.List;
import java.util.Map;

public class SendEmailTask implements ILifecycleTask {
    private static final String ID = "ai.labs.sendmail";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public Object getComponent() {
        return null;
    }

    @Override
    public void executeTask(IConversationMemory memory) {
        //todo use configs stored from below to send mail

        List outputs = memory.getCurrentStep().getConversationOutput().get("output", List.class);
    }

    @Override
    public void configure(Map<String, Object> configuration) {
        //todo add the configs for smtp server to this class that will be defined in package
    }

    @Override
    public ExtensionDescriptor getExtensionDescriptor() {
        return new ExtensionDescriptor(ID);
    }
}
